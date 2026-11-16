"""Knowledge base: article CRUD, search, voting, view counting and draft-from-ticket."""
from __future__ import annotations

from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Path, Query, status
from sqlalchemy import cast, func, or_, select, update
from sqlalchemy.dialects.postgresql import ARRAY
from sqlalchemy.orm import joinedload
from sqlalchemy import String

from ...ai.engine import generate_article_draft
from ...db.models import Account, Category, KnowledgeArticle, Ticket, User
from ...db.session import tenant_session
from ...services import commit_hooks
from ..schemas.kb import (ArticleCreateRequest, ArticleFromTicketRequest, ArticleResponse, ArticleUpdateRequest,
                          ArticleVoteRequest)
from ..security import AuthenticatedUser, get_authenticated_user, get_current_user

router = APIRouter(prefix="/api/kb", tags=["kb"])


def _requester_can_read(article: KnowledgeArticle, user: Optional[User]) -> bool:
    """Requesters see published, public articles that are shared or belong to their own account. Read, search and
    vote all use this rule."""
    if article.status != "published" or article.visibility != "public":
        return False
    return not article.account_id or article.account_id == (user.account_id if user else None)


def _article_payload(a: KnowledgeArticle) -> ArticleResponse:
    return ArticleResponse(
        id=a.id, title=a.title, body=a.body, category=a.category_id, account=a.account_id, visibility=a.visibility,
        status=a.status, tags=a.tags or [], views=a.views, helpful_votes=a.helpful_votes,
        unhelpful_votes=a.unhelpful_votes, author=a.author.display_name if a.author_id else None,
        source_ticket=a.source_ticket_id, created_at=a.created_at.isoformat() if a.created_at else "",
        updated_at=a.updated_at.isoformat() if a.updated_at else "")


def _embed_later(session, article: KnowledgeArticle) -> None:
    tenant_id, article_id = article.tenant_id, article.id

    def _go() -> None:
        from ...worker.tasks import embed_article
        embed_article.delay(tenant_id, article_id)

    commit_hooks.on_commit(session, _go, robust=True)


def _caller(s, user: AuthenticatedUser) -> Optional[User]:
    return s.scalars(select(User).where(User.id == user.user_id, User.tenant_id == user.tenant_id)).first()


def _forbidden(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=detail)


def _nf() -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found.")


def _bad(detail: str) -> HTTPException:
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=detail)


# The values the article form offers (src/pages/articleHelpers.js) and the widths of the columns.
VISIBILITIES = ("public", "internal")
STATUSES = ("draft", "published", "retired")
TITLE_MAX = 300
TAG_MAX = 50
_REFERENCES = {"category": Category, "account": Account}


def _clean_tags(tags) -> list:
    clean = list(dict.fromkeys(t.strip() for t in (tags or []) if t and t.strip()))
    if any(len(t) > TAG_MAX for t in clean):
        raise _bad(f"A tag can be at most {TAG_MAX} characters.")
    return clean


def _clean_article(s, data: dict, tenant_id: int) -> dict:
    """Check and tidy the fields an article request carries (only those present), so a bad value is a 400 with a
    message instead of a database error, and a category or account can only be one of the caller's own tenant."""
    out = dict(data)
    if "title" in out:
        out["title"] = (out["title"] or "").strip()
        if not out["title"]:
            raise _bad("Give the article a title.")
        if len(out["title"]) > TITLE_MAX:
            raise _bad(f"The title can be at most {TITLE_MAX} characters.")
    if "body" in out and not (out["body"] or "").strip():
        raise _bad("Write the article text.")
    for field, allowed in (("visibility", VISIBILITIES), ("status", STATUSES)):
        if field in out and out[field] not in allowed:
            raise _bad(f"Invalid {field}.")
    if "tags" in out:
        out["tags"] = _clean_tags(out["tags"])
    for field, model in _REFERENCES.items():
        pk = out.get(field)
        if pk is not None and s.scalars(select(model.id).where(model.id == pk, model.tenant_id == tenant_id)).first() is None:
            raise _bad(f"Invalid {field}.")
    return out


@router.get("/articles/", response_model=List[ArticleResponse])
def list_articles(q: Optional[str] = Query(None, description="Search query"),
                  user: AuthenticatedUser = Depends(get_authenticated_user)):
    """Published/public articles for requesters, or all articles for staff."""
    with tenant_session(user.tenant_id, commit=False) as s:
        me = _caller(s, user)
        stmt = (select(KnowledgeArticle).options(joinedload(KnowledgeArticle.author))
                .where(KnowledgeArticle.tenant_id == user.tenant_id).order_by(KnowledgeArticle.updated_at.desc()))
        if not user.is_agent:
            acct = me.account_id if me else None
            stmt = stmt.where(KnowledgeArticle.status == "published", KnowledgeArticle.visibility == "public",
                              or_(KnowledgeArticle.account_id.is_(None), KnowledgeArticle.account_id == acct))
        if q and q.strip():
            term = q.strip()
            like = f"%{term}%"
            stmt = stmt.where(or_(KnowledgeArticle.title.ilike(like), KnowledgeArticle.body.ilike(like),
                                  KnowledgeArticle.tags.contains([term.lower()])))
        return [_article_payload(a) for a in s.scalars(stmt).unique()]


@router.post("/articles/", response_model=ArticleResponse, status_code=status.HTTP_201_CREATED)
def create_article(payload: ArticleCreateRequest, user: AuthenticatedUser = Depends(get_current_user)):
    """Create a knowledge base article (staff role required)."""
    if not user.is_agent:
        raise _forbidden("Only agents can edit articles.")
    with tenant_session(user.tenant_id) as s:
        me = _caller(s, user)
        v = _clean_article(s, payload.model_dump(), user.tenant_id)
        a = KnowledgeArticle(tenant_id=user.tenant_id, title=v["title"], body=v["body"],
                             category_id=v["category"], account_id=v["account"], visibility=v["visibility"],
                             status=v["status"], tags=v["tags"], author_id=me.id if me else None)
        s.add(a)
        s.flush()
        s.refresh(a)
        _embed_later(s, a)
        return _article_payload(a)


@router.get("/articles/{id}/", response_model=ArticleResponse)
def get_article(id: int = Path(..., ge=1), user: AuthenticatedUser = Depends(get_authenticated_user)):
    """Article detail; the view counter is incremented atomically."""
    with tenant_session(user.tenant_id) as s:
        me = _caller(s, user)
        a = s.scalars(select(KnowledgeArticle).options(joinedload(KnowledgeArticle.author)).where(
            KnowledgeArticle.id == id, KnowledgeArticle.tenant_id == user.tenant_id)).first()
        if not a:
            raise _nf()
        if not user.is_agent and not _requester_can_read(a, me):
            raise _nf()
        s.execute(update(KnowledgeArticle).where(KnowledgeArticle.id == a.id).values(views=KnowledgeArticle.views + 1))
        s.refresh(a)
        return _article_payload(a)


@router.patch("/articles/{id}/", response_model=ArticleResponse)
def update_article(payload: ArticleUpdateRequest, id: int = Path(..., ge=1),
                   user: AuthenticatedUser = Depends(get_current_user)):
    """Update an article (staff role required)."""
    if not user.is_agent:
        raise _forbidden("Only agents can edit articles.")
    with tenant_session(user.tenant_id) as s:
        a = s.scalars(select(KnowledgeArticle).options(joinedload(KnowledgeArticle.author)).where(
            KnowledgeArticle.id == id, KnowledgeArticle.tenant_id == user.tenant_id)).first()
        if not a:
            raise _nf()
        for k, v in _clean_article(s, payload.model_dump(exclude_unset=True), user.tenant_id).items():
            if k == "category":
                k = "category_id"
            elif k == "account":
                k = "account_id"
            setattr(a, k, v)
        s.flush()
        s.refresh(a)
        _embed_later(s, a)
        return _article_payload(a)


@router.delete("/articles/{id}/", status_code=status.HTTP_204_NO_CONTENT)
def delete_article(id: int = Path(..., ge=1), user: AuthenticatedUser = Depends(get_current_user)):
    """Delete an article (staff role required)."""
    if not user.is_agent:
        raise _forbidden("Only agents can edit articles.")
    with tenant_session(user.tenant_id) as s:
        a = s.scalars(select(KnowledgeArticle).where(KnowledgeArticle.id == id,
                                                     KnowledgeArticle.tenant_id == user.tenant_id)).first()
        if not a:
            raise _nf()
        s.delete(a)
    return None


@router.post("/articles/{id}/vote/")
def vote_article(payload: ArticleVoteRequest, id: int = Path(..., ge=1),
                 user: AuthenticatedUser = Depends(get_authenticated_user)):
    """Record a helpful or unhelpful vote."""
    with tenant_session(user.tenant_id) as s:
        a = s.scalars(select(KnowledgeArticle).where(KnowledgeArticle.id == id,
                                                     KnowledgeArticle.tenant_id == user.tenant_id)).first()
        if not a:
            raise _nf()
        if not user.is_agent and not _requester_can_read(a, _caller(s, user)):  # no voting on what one cannot read
            raise _nf()
        col = KnowledgeArticle.helpful_votes if payload.helpful else KnowledgeArticle.unhelpful_votes
        s.execute(update(KnowledgeArticle).where(KnowledgeArticle.id == a.id).values({col.key: col + 1}))
        return {"ok": True}


@router.post("/articles/from_ticket/", response_model=ArticleResponse, status_code=status.HTTP_201_CREATED)
def create_article_from_ticket(payload: ArticleFromTicketRequest, user: AuthenticatedUser = Depends(get_current_user)):
    """Generate an article draft from a resolved or closed ticket."""
    if not user.is_agent:
        raise _forbidden("Only staff can create articles from tickets.")
    with tenant_session(user.tenant_id) as s:
        me = _caller(s, user)
        ticket = s.scalars(select(Ticket).where(Ticket.id == payload.ticket_id,
                                                Ticket.tenant_id == user.tenant_id)).first()
        if not ticket or not me:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Ticket not found.")
        if ticket.state.category not in ("resolved", "closed"):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST,
                                detail="Only resolved or closed tickets can be converted into Knowledge Base articles.")
        existing = s.scalars(select(KnowledgeArticle).options(joinedload(KnowledgeArticle.author)).where(
            KnowledgeArticle.tenant_id == ticket.tenant_id, KnowledgeArticle.source_ticket_id == ticket.id)).first()
        if existing:
            res = _article_payload(existing)
            res.duplicate = True
            return res
        draft = generate_article_draft(s, ticket)
        a = KnowledgeArticle(tenant_id=ticket.tenant_id, title=draft["title"], body=draft["body"],
                             category_id=ticket.category_id, account_id=None, status="draft", author_id=me.id,
                             source_ticket_id=ticket.id, tags=[])
        s.add(a)
        s.flush()
        s.refresh(a)
        _embed_later(s, a)
        res = _article_payload(a)
        res.generation_method = draft.get("method", "template")
        return res

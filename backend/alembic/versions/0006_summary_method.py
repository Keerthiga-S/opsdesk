"""A ticket remembers whether its stored AI summary was written by a language model or by the built-in template.

One nullable column on ``tickets_ticket``. Additive: summaries stored before this have no recorded origin (NULL).

Revision ID: 0006
Revises: 0005
"""
import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("tickets_ticket", sa.Column("ai_summary_method", sa.String(length=10), nullable=True))


def downgrade() -> None:
    op.drop_column("tickets_ticket", "ai_summary_method")

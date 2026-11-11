
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app

# Update OS packages and install required runtime dependencies
RUN apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends libpq5 curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
COPY requirements.txt .
RUN pip install -r requirements.txt

# Copy application files
COPY . .

# Create non-root application user
RUN useradd --create-home --uid 10001 opsdesk \
    && mkdir -p /app/media \
    && chown -R opsdesk /app \
    && chmod +x /app/entrypoint.sh

USER opsdesk

EXPOSE 8001

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD curl -fsS http://localhost:${API_PORT:-8001}/healthz || exit 1

ENTRYPOINT ["./entrypoint.sh"]
CMD ["api"]

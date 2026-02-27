ARG PYTHON_VERSION=3.12

# =============================================================================
# Base
# =============================================================================
FROM python:${PYTHON_VERSION}-slim AS base
WORKDIR /app

COPY ciqual/pyproject.toml ./
RUN pip install --no-cache-dir .

COPY ciqual/app/ ./app/

# =============================================================================
# Builder: generate SQLite DB from XML
# =============================================================================
FROM base AS builder

COPY ciqual/data/ ./data/
RUN python -m app.import_ciqual

# =============================================================================
# Production
# =============================================================================
FROM base AS prod

COPY --from=builder /app/db/ciqual.db ./db/ciqual.db

EXPOSE 8002
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002"]

# =============================================================================
# Development
# =============================================================================
FROM base AS dev

ARG UID=1000
ARG GID=1000

RUN pip install --no-cache-dir ".[dev]" && \
    groupadd -g ${GID} app || true && \
    useradd -u ${UID} -g ${GID} -m app || true

USER app

EXPOSE 8002
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002", "--reload"]

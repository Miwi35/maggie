ARG PYTHON_VERSION=3.12

# =============================================================================
# Base stage - shared configuration
# =============================================================================
FROM python:${PYTHON_VERSION}-slim AS base

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install uv for fast dependency management
COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

# Set working directory
WORKDIR /app

# Create non-root user
ARG UID=1000
ARG GID=1000
RUN groupadd -g ${GID} app \
    && useradd -u ${UID} -g app -s /bin/bash -m app \
    && chown -R app:app /app

# Allow app user to install Python packages system-wide
RUN chown -R app:app /usr/local/lib/python3.12/site-packages /usr/local/bin /usr/local/include/python3.12

EXPOSE 8001

# =============================================================================
# Development stage
# =============================================================================
FROM base AS dev

USER app

# In dev, the volume mount provides the source code.
# Dependencies are installed via `task agent:install` after container starts.
CMD ["sh", "-c", "uv pip install --system -e '.[dev]' && uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload"]

# =============================================================================
# Production stage
# =============================================================================
FROM base AS prod

# Copy application source
COPY --chown=app:app ./agent .

# Install production dependencies only
RUN uv pip install --system .

USER app

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8001/agent/docs || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "2"]

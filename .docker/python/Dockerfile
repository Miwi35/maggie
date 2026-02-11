ARG PYTHON_VERSION=3.12

FROM python:${PYTHON_VERSION}-slim

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
RUN chown -R app:app /usr/local/lib/python3.12/site-packages /usr/local/bin

USER app

EXPOSE 8001

# In dev, the volume mount provides the source code.
# Dependencies are installed via `task agent:install` after container starts.
CMD ["sh", "-c", "uv pip install --system -e '.[dev]' && uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload"]

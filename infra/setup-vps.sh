#!/usr/bin/env bash
set -euo pipefail

# Maggie v3 — One-time VPS bootstrap
# Run as the deploy user on Debian 12 with Docker already installed.

echo "=== Creating traefik-public network ==="
docker network create traefik-public 2>/dev/null || echo "Network already exists"

echo "=== Creating directories ==="
mkdir -p /opt/maggie
mkdir -p /opt/traefik

echo "=== Deploying Traefik ==="
if [ ! -f /opt/traefik/docker-compose.yml ]; then
    echo "Copy infra/traefik/docker-compose.yml to /opt/traefik/ first"
    exit 1
fi

if [ ! -f /opt/traefik/.env ]; then
    echo "Create /opt/traefik/.env with:"
    echo "  ACME_EMAIL=your-email@example.com"
    echo "  DASHBOARD_AUTH=user:\$(htpasswd -nB user)"
    exit 1
fi

cd /opt/traefik
docker compose up -d

echo ""
echo "=== Traefik is running ==="
echo ""
echo "Next steps:"
echo "  1. Copy .env.prod.example → /opt/maggie/.env.prod and fill in real values"
echo "  2. Set GitHub Actions secrets: VPS_HOST, VPS_USER, VPS_SSH_KEY"
echo "  3. Push to main to trigger the first deploy"

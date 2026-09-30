#!/usr/bin/env bash
#
# Prompt lab's door onto Maggie's dev stack (MAG-136).
#
#   mcp-session.sh login
#   mcp-session.sh context [chat|proaction [planning|execution]]
#   mcp-session.sh personality | instructions | skills | tools
#   mcp-session.sh call <tool_name> '<json_args>'
#
# Talks to the dev stack, never the e2e one. No runtime on the host: login goes
# through the php container, everything else through session.py run inside the
# agent container, so prompts are assembled by the very code production runs.
#
# Environment:
#   PROMPT_LAB_USER_EMAIL  user to act as (default: the only user in the dev DB)
#   PROMPT_LAB_TOKEN_FILE  JWT cache (default: scripts/prompt-lab/.token, gitignored)
#   COMPOSE_PROJECT_NAME   compose project of the dev stack, if not the default

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TOKEN_FILE="${PROMPT_LAB_TOKEN_FILE:-$SCRIPT_DIR/.token}"
REQUIRED_SERVICES=(php nginx agent database)

die() {
  printf 'Erreur : %s\n' "$*" >&2
  exit 1
}

# Compose warns about every variable a missing .env leaves unset; that noise
# would bury the output a subagent has to read.
dc() {
  docker compose -f "$REPO_ROOT/docker-compose.yml" "$@" 2> >(grep -v 'level=warning' >&2)
}

require_tools() {
  command -v docker >/dev/null || die "docker est introuvable."
  command -v jq >/dev/null || die "jq est introuvable."
}

require_stack() {
  local running service
  running="$(dc ps --status running --services 2>/dev/null || true)"
  for service in "${REQUIRED_SERVICES[@]}"; do
    if ! grep -qx "$service" <<<"$running"; then
      die "la stack dev n'est pas démarrée (service « $service » absent). Lancez : task up"
    fi
  done
}

# Seconds of validity left in a JWT, 0 when it cannot be read.
token_ttl() {
  local payload exp
  payload="$(cut -d. -f2 <<<"$1" | tr '_-' '/+')"
  while (( ${#payload} % 4 )); do payload+='='; done
  exp="$(base64 -d <<<"$payload" 2>/dev/null | jq -r '.exp // 0' 2>/dev/null || echo 0)"
  echo $(( exp - $(date +%s) ))
}

resolve_email() {
  if [ -n "${PROMPT_LAB_USER_EMAIL:-}" ]; then
    printf '%s' "$PROMPT_LAB_USER_EMAIL"
    return
  fi

  local emails count
  emails="$(dc exec -T database sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT email FROM \"user\" ORDER BY email"' 2>/dev/null)" \
    || die "impossible de lire les utilisateurs de la base dev."
  count="$(grep -c . <<<"$emails" || true)"

  [ "$count" -gt 0 ] || die "aucun utilisateur dans la base dev — connectez-vous une fois à l'admin, ou définissez PROMPT_LAB_USER_EMAIL."
  [ "$count" -eq 1 ] || die "plusieurs utilisateurs en base dev ($(tr '\n' ' ' <<<"$emails")) — choisissez avec PROMPT_LAB_USER_EMAIL."
  printf '%s' "$emails"
}

cmd_login() {
  require_stack
  local email output jwt
  email="$(resolve_email)"
  # Symfony logs deprecations into the same stream, so the token is picked out
  # by its shape (three base64url segments), not by position.
  output="$(dc exec -T php php bin/console lexik:jwt:generate-token "$email" 2>&1 | tr -d '\r' || true)"
  jwt="$(grep -E '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$' <<<"$output" | tail -n 1 || true)"

  if [ -z "$jwt" ] || [ "$(token_ttl "$jwt")" -le 0 ]; then
    die "« lexik:jwt:generate-token $email » n'a pas rendu de JWT exploitable : $(tail -n 3 <<<"$output" | cut -c1-300)"
  fi

  mkdir -p "$(dirname "$TOKEN_FILE")" || die "impossible de créer le dossier de $TOKEN_FILE."
  (umask 077 && printf '%s\n' "$jwt" >"$TOKEN_FILE" && chmod 600 "$TOKEN_FILE") \
    || die "impossible d'écrire le jeton dans $TOKEN_FILE."
  printf 'Connecté en tant que %s — jeton valide %dh, en cache dans %s\n' \
    "$email" "$(( $(token_ttl "$jwt") / 3600 ))" "${TOKEN_FILE#"$REPO_ROOT"/}"
}

# Reads the cached JWT, logging in again when it is missing or about to expire.
ensure_token() {
  local jwt=""
  [ -f "$TOKEN_FILE" ] && jwt="$(tr -d '[:space:]' <"$TOKEN_FILE")"
  if [ -z "$jwt" ] || [ "$(token_ttl "$jwt")" -lt 300 ]; then
    cmd_login >&2
    jwt="$(tr -d '[:space:]' <"$TOKEN_FILE")"
  fi
  printf '%s' "$jwt"
}

run_in_agent() {
  local jwt
  jwt="$(ensure_token)"
  # Name only after -e: docker reads the value from the environment, so the
  # token never shows up in `ps`.
  PROMPT_LAB_TOKEN="$jwt" dc exec -T -e PROMPT_LAB_TOKEN agent python - "$@" <"$SCRIPT_DIR/session.py"
}

usage() {
  sed -n '3,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

require_tools

case "${1:-}" in
  login)
    cmd_login
    ;;
  context | personality | instructions | skills | tools)
    require_stack
    run_in_agent "$@"
    ;;
  call)
    [ $# -ge 2 ] || die "usage : mcp-session.sh call <tool_name> '<json_args>'"
    require_stack
    run_in_agent "$@"
    ;;
  -h | --help | help | "")
    usage
    ;;
  *)
    usage >&2
    die "sous-commande inconnue « $1 »."
    ;;
esac

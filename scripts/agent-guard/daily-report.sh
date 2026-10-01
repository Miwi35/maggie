#!/usr/bin/env bash
#
# The agent's day, as Markdown (MAG-128): the pull requests on `cyrus/*` branches
# opened, merged, handed to a human and still open over the last <hours> (24).
# The ticket is read from the branch name (cyrus/mag-128-… is MAG-128).
#
#   scripts/agent-guard/daily-report.sh [hours]
#
# Needs GH_TOKEN (or a logged-in gh) and GH_REPO outside a checkout.

set -euo pipefail

HOURS="${1:-24}"
SINCE="$(date -u -d "-$HOURS hours" +%Y-%m-%dT%H:%M:%SZ)"

gh pr list --state all --limit 200 \
  --json number,title,headRefName,state,createdAt,mergedAt,closedAt,labels,url \
  | jq -r --arg since "$SINCE" --arg hours "$HOURS" '
    def ticket: (try (.headRefName | capture("^cyrus/(?<k>[a-z]+-[0-9]+)").k | ascii_upcase) catch "?");
    def line: "- \(ticket) — \(.title) ([#\(.number)](\(.url)))";
    def section($title; $rows):
      "## \($title) (\($rows | length))\n" + (if ($rows | length) == 0 then "Aucun.\n" else ($rows | map(line) | join("\n")) + "\n" end);
    [.[] | select(.headRefName | startswith("cyrus/"))] as $all
    | ([$all[] | select(.createdAt >= $since)]) as $opened
    | ([$all[] | select(.mergedAt != null and .mergedAt >= $since)]) as $merged
    | ([$all[] | select(.state == "CLOSED" and .mergedAt == null and .closedAt >= $since)]) as $abandoned
    | ([$all[] | select(.state == "OPEN" and any(.labels[]; .name == "needs-human"))]) as $human
    | ([$all[] | select(.state == "OPEN" and (any(.labels[]; .name == "needs-human") | not))]) as $open
    | "# Rapport de l\u0027équipe agent — \($hours) dernières heures\n\n"
      + section("PR ouvertes"; $opened) + "\n"
      + section("PR mergées"; $merged) + "\n"
      + section("En attente d\u0027un humain (needs-human)"; $human) + "\n"
      + section("PR ouvertes sans blocage"; $open) + "\n"
      + section("PR abandonnées"; $abandoned) + "\n"
      + "## Coût\nNon mesuré : la facturation n\u0027est pas lisible depuis GitHub.\n\n"
      + "_Les tickets pris sans PR (bloqués avant d\u0027avoir codé) ne sont visibles que dans Linear._\n"
  '

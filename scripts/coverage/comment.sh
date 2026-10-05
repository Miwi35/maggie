#!/usr/bin/env bash
#
# Post the coverage report on a pull request, or update the one already there
# (MAG-105). The comment is found by its first line, the marker report.sh writes.
#
#   scripts/coverage/comment.sh <pr number> <marker> <body file>
#
# Needs GH_TOKEN with `pull-requests: write` and GITHUB_REPOSITORY. A fork's token
# cannot comment: that is a warning, not a failure — the report stays in the job
# summary, and a red CI because nobody could be told would be worse than silence.

set -euo pipefail

[ "$#" -eq 3 ] || { echo "usage: comment.sh <pr> <marker> <body file>" >&2; exit 64; }
pr="$1" marker="$2" body="$3"
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"

ids="$(gh api --paginate "repos/$repo/issues/$pr/comments" \
  --jq ".[] | select(.body | startswith(\"<!-- $marker -->\")) | .id" || true)"
existing="$(head -1 <<<"$ids")"

if [ -n "$existing" ]; then
  gh api -X PATCH "repos/$repo/issues/comments/$existing" -F "body=@$body" > /dev/null \
    || echo "::warning::Could not update the coverage comment on #$pr"
else
  gh api -X POST "repos/$repo/issues/$pr/comments" -F "body=@$body" > /dev/null \
    || echo "::warning::Could not post the coverage comment on #$pr"
fi

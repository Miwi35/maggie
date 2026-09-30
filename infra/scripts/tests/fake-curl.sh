#!/usr/bin/env bash
# A curl that stands in for the Linear API in open-incident.test.sh. It logs the
# Authorization header and the JSON body of every request to $FAKE_CURL_DIR, and
# answers with `lookup-response` or `create-response` from the same directory
# depending on the GraphQL operation.
set -euo pipefail

dir="${FAKE_CURL_DIR:?}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    -H)
      case "$2" in Authorization:*) echo "$2" >> "$dir/auth" ;; esac
      shift 2
      ;;
    *) shift ;;
  esac
done

body=$(cat)
echo "$body" | jq -c . >> "$dir/requests"

if jq -e '.query | contains("issueCreate")' >/dev/null <<<"$body"; then
  cat "$dir/create-response"
else
  cat "$dir/lookup-response"
fi

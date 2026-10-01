#!/usr/bin/env bash
# A curl that stands in for the Linear API in the Linear scripts' tests. It logs the
# Authorization header and the JSON body of every request to $FAKE_CURL_DIR, and
# answers from the same directory depending on the GraphQL operation:
#   issueCreate           create-response
#   issueUpdate           update-response    (success when absent)
#   commentCreate         comment-response   (success when absent)
#   issueRelationCreate   relation-response  (success when absent)
#   issue(id: …)          issue-<id>         (not found when absent)
#   anything else         lookup-response
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

# answer <file> <default>
answer() { if [ -f "$dir/$1" ]; then cat "$dir/$1"; else printf '%s' "$2"; fi; }

query=$(jq -r .query <<<"$body")
case "$query" in
  *issueCreate*) cat "$dir/create-response" ;;
  *issueUpdate*) answer update-response '{"data":{"issueUpdate":{"success":true}}}' ;;
  *commentCreate*) answer comment-response '{"data":{"commentCreate":{"success":true}}}' ;;
  *issueRelationCreate*) answer relation-response '{"data":{"issueRelationCreate":{"success":true}}}' ;;
  *'issue(id:'*)
    answer "issue-$(jq -r .variables.id <<<"$body")" '{"data":{"issue":null},"errors":[{"message":"Entity not found"}]}'
    ;;
  *) cat "$dir/lookup-response" ;;
esac

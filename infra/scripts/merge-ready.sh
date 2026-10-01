#!/usr/bin/env bash
#
# Is this armed pull request stuck although it could merge? (MAG-200)
#
# Reads on stdin the JSON of `gh pr view --json state,autoMergeRequest,labels,statusCheckRollup`
# plus a `required` array: the required status checks of the base branch.
# Exit 0 = auto-merge is armed, nobody handed the PR to a human, and every
# required check is green on the head: it can be merged now. Exit 1 = leave it,
# the reason on stdout. A skipped or neutral job counts as green, as for GitHub.

set -euo pipefail

jq -r '
  def green: (.conclusion // "") as $c | (.state // "") as $s
    | ($c == "SUCCESS" or $c == "SKIPPED" or $c == "NEUTRAL" or $s == "SUCCESS");
  . as $pr
  | if $pr.state != "OPEN" then "not open (\($pr.state))"
    elif $pr.autoMergeRequest == null then "auto-merge is not armed"
    elif ($pr.labels // [] | map(.name) | index("needs-human")) != null then "labelled needs-human"
    elif ($pr.required // [] | length) == 0 then "no required check known: refusing to merge blind"
    else
      [ $pr.required[] as $ctx
        | ([ $pr.statusCheckRollup[]? | select((.name // .context) == $ctx) ] | last) as $check
        | select($check == null or ($check | green | not))
        | if $check == null then "\($ctx): never reported"
          else "\($ctx): \([$check.conclusion, $check.state, $check.status] | map(select(. != null and . != "")) | first)" end
      ] | if length == 0 then "ready" else join(", ") end
    end
' | {
  read -r verdict
  echo "$verdict"
  [ "$verdict" = ready ]
}

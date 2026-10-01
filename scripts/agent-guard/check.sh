#!/usr/bin/env bash
#
# Guard rails for agent pull requests (MAG-128).
#
# Reads a unified diff on stdin and prints one finding per line, `<code>: <why>`.
# Exit 0: nothing found, the PR may merge itself. Exit 10: a human must merge it.
#
#   gh pr diff 42 | scripts/agent-guard/check.sh
#   git diff origin/main...HEAD | scripts/agent-guard/check.sh
#
# Codes: sensitive-path, infra-path, permissions, destructive-migration,
# oversize, disabled-test, no-verify. infra-path alone is the one finding
# the fix of an « Emergency » ticket may merge past (emergency.sh, MAG-184). AGENT_MAX_DIFF_LINES (default 800) is the size limit,
# tests, lockfiles, generated contracts and spec folders not counted.
#
# Patterns that name a forbidden string are written so the source line does not
# match itself (`[y]`), because this file is part of the diffs it checks.

set -euo pipefail

MAX="${AGENT_MAX_DIFF_LINES:-800}"

exec awk -v max="$MAX" -v q="'" '
function is_test(f) {
  return f ~ /(^|\/)(tests?|__tests__|androidTest|fixtures)\// || f ~ /^e2e\// \
    || f ~ /\.(test|spec)\.[jt]sx?$/ || f ~ /Test\.(php|kt)$/ \
    || f ~ /(^|\/)test_[^\/]*\.py$/ || f ~ /_test\.py$/
}
function not_counted(f) {
  return is_test(f) || f ~ /(^|\/)(package-lock\.json|composer\.lock|symfony\.lock|uv\.lock)$/ \
    || f ~ /^api\/contract\// || f ~ /^agent-os\/specs\//
}
# Secrets, policy, the guard and the freeze themselves: always a human.
function sensitive(f) {
  return f ~ /^scripts\/agent-guard\// || f ~ /^\.github\/workflows\/(agent-guard|incident-gate-release)\.ya?ml$/ \
    || f ~ /^infra\/scripts\/(incident-gate|rerun-incident-gates|linear)\.sh$/ \
    || f ~ /\.(pem|jks|keystore)$/ || f ~ /(^|\/)\.env(\.[^\/]*)?$/ && f !~ /\.example$/ \
    || f ~ /(^|\/)secrets?([.\/]|$)/ || f ~ /(^|[\/_.-])secrets?([_.-][^\/]*)?\.ya?ml$/ || f ~ /^api\/config\/jwt\// \
    || f ~ /(^|\/)policy\.ya?ml$/
}
# Infra and CD: a human, unless the PR is the fix of a ticket in « Emergency ».
function infra(f) {
  return f ~ /^infra\// || f ~ /^\.github\//
}
function auth_file(f) {
  return f ~ /^api\/config\/packages\/(security|lexik_jwt_authentication|gesdinet_jwt_refresh_token)\.yaml$/ \
    || f ~ /^api\/config\/routes\/security\.yaml$/ \
    || f ~ /^api\/modules\/core\/src\/(Controller\/GoogleAuthController|EventListener\/JWTCreatedListener|Mcp\/McpAccessListener)\.php$/ \
    || f ~ /^api\/modules\/[^\/]+\/src\/(Security|Voter)\// || f ~ /Voter\.php$/ \
    || f ~ /^admin\/src\/auth\// || f ~ /^agent\/app\/auth\.py$/ || f ~ /^mobile\/.*\/data\/auth\//
}
function flag(code, msg, key) {
  key = code SUBSEP msg
  if (!(key in seen)) { seen[key] = 1; out[++n] = code ": " msg }
}
function destructive(l, rest) {
  if (l ~ /drop[[:space:]]+(table|column|schema|database)/ || l ~ /truncate|delete[[:space:]]+from/) return 1
  if (match(l, /alter table .* drop[[:space:]]+/)) {
    rest = substr(l, RSTART + RLENGTH)
    return rest !~ /^(constraint|index|default|not |identity|expression|if exists (constraint|index))/
  }
  return 0
}

/^diff --git / {
  file = $4; sub(/^b\//, "", file)
  old = $3; sub(/^a\//, "", old)
  in_down = 0
  if (sensitive(file) || sensitive(old)) flag("sensitive-path", file)
  else if (infra(file) || infra(old)) flag("infra-path", file)
  if (!is_test(file) && auth_file(file)) flag("permissions", file)
  next
}
/^(\+\+\+|---) / { next }
{
  c = substr($0, 1, 1)
  if (c != "+" && c != "-" && c != " ") next
  line = substr($0, 2)
  low = tolower(line)
  if (file ~ /(^|\/)(api\/)?migrations\/Version[^\/]*\.php$/) {
    if (low ~ /function[[:space:]]+down[[:space:]]*\(/) in_down = 1
    if (low ~ /function[[:space:]]+up[[:space:]]*\(/) in_down = 0
    if (c == "+" && !in_down && destructive(low)) flag("destructive-migration", file ": " line)
  }
  if (c == "+" || c == "-") {
    if (!not_counted(file)) size++
    if (!is_test(file) && file ~ /\.(php|ya?ml|tsx?|py|kt)$/ && line ~ perm_re)
      flag("permissions", file ": " line)
    # A workflow that changes the rights of its token or reaches for a secret is never waived.
    if (file ~ /^\.github\// && line ~ /^[[:space:]]*permissions:|:[[:space:]]*write([^[:alnum:]_-]|$)|write-all/)
      flag("permissions", file ": " line)
    if (file ~ /^\.github\// && line ~ /(^|[^[:alnum:]_])secrets([^[:alnum:]_-]|$)/) flag("sensitive-path", file ": uses a secret")
    # The Incident gate job: the ambulance must not loosen the freeze it is held by.
    if (file ~ /^\.github\/workflows\/ci\.ya?ml$/ && line ~ /[Ii]ncident.gate/) flag("sensitive-path", file ": the Incident gate")
  }
  if (c == "+") {
    if (file !~ /\.md$/ && line ~ /--no-verif[y]|--no-gpg-sig[n]/) flag("no-verify", file)
    if (is_test(file) && line ~ /markTestSkippe[d]|markTestIncomplet[e]|@Disable[d]|@Ignor[e]|#\[Group\(.quarantin[e]|\.skip\(|\.fixme\(|(^|[^[:alnum:]_])(xit|xdescribe|xtest)\(|pytest\.mark\.(skip|xfail)|pytest\.ski[p]\(/)
      flag("disabled-test", file ": " line)
  }
}
BEGIN {
  perm_re = "IsGranted|isGranted|denyAccessUnlessGranted|access_control|ROLE_|security(PostDenormalize)?:[[:space:]]*[" q "\"]"
}
END {
  if (size > max) flag("oversize", size " lines outside tests, limit " max ": split the ticket")
  for (i = 1; i <= n; i++) print out[i]
  print "info: " (size + 0) " counted lines, limit " max
  exit (n > 0 ? 10 : 0)
}
'

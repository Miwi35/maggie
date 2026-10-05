#!/usr/bin/env bash
# shellcheck disable=SC2015 # `cond && ok || bad`: ok and bad cannot fail
#
# Tests of scripts/agent-guard/check.sh (MAG-128): what sends a pull request to
# a human, and what must not.
#
# Usage: infra/scripts/tests/agent-guard.test.sh

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$HERE/../../../scripts/agent-guard/check.sh"
# `expect` reads a pipe, so it runs in a subshell: a counter would never reach
# this shell. Failures are counted in a file instead.
FAILED="$(mktemp)"
trap 'rm -f "$FAILED"' EXIT

ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; echo x >> "$FAILED"; }

# section <path> <line>... — one file of a diff, every line added.
section() {
  local path="$1"
  shift
  printf 'diff --git a/%s b/%s\n--- /dev/null\n+++ b/%s\n@@ -0,0 +1,%d @@\n' "$path" "$path" "$path" "$#"
  printf '+%s\n' "$@"
}

# bulk <path> <n> — a file of <n> added lines.
bulk() {
  local lines=() i
  for ((i = 0; i < $2; i++)); do lines+=("line $i"); done
  section "$1" "${lines[@]}"
}

# expect <name> <code|clean> — the diff on stdin must raise <code> (or nothing).
expect() {
  local name="$1" code="$2" out status
  out="$("$CHECK")"
  status=$?
  if [ "$code" = clean ]; then
    [ "$status" -eq 0 ] && ok "$name" || bad "$name — expected no finding, got: $out"
  else
    [ "$status" -eq 10 ] && grep -q "^$code: " <<< "$out" && ok "$name" || bad "$name — expected $code, got ($status): $out"
  fi
}

# exact <name> <findings> — the diff on stdin must raise exactly these findings
# (`info:` lines aside): what the Emergency waiver relies on.
exact() {
  local name="$1" want="$2" out
  out="$("$CHECK" | grep -v '^info: ')"
  [ "$out" = "$want" ] && ok "$name" || bad "$name — expected '$want', got: $out"
}

echo "Sensitive paths"
section infra/k8s/php-deployment.yaml 'replicas: 3' | expect "infra/k8s needs a human" infra-path
section .github/workflows/main.yml 'name: Main' | expect "a workflow needs a human" infra-path
section infra/scripts/deploy-k3s.sh 'echo deploy' | expect "the deploy scripts need a human" infra-path
section .github/workflows/agent-guard.yml 'name: x' | expect "the guard's workflow is never mere infra" sensitive-path
section infra/k8s/secrets.yaml 'kind: Secret' | expect "a secret under infra is never mere infra" sensitive-path
section .github/workflows/main.yml '    permissions:' '      contents: write' | expect "a workflow's token rights need a human" permissions
section .github/workflows/main.yml '          TOKEN: ${{ secrets.VPS_SSH_KEY }}' | expect "a workflow reaching for a secret needs a human" sensitive-path
section .github/workflows/main.yml '    secrets: inherit' | expect "secrets: inherit is a secret" sensitive-path
section .github/workflows/main.yml "          K: \${{ secrets['VPS'] }}" | expect "secrets[...] is a secret" sensitive-path
section .github/workflows/main.yml '          ALL: ${{ toJSON(secrets) }}' | expect "toJSON(secrets) is a secret" sensitive-path
section infra/k8s/maggie-sealed-secret.yaml 'kind: SealedSecret' | expect "a sealed secret manifest is a secret" sensitive-path
section infra/scripts/incident-gate.sh 'exit 0' | expect "the freeze cannot be loosened by an agent" sensitive-path
section .github/workflows/ci.yml '  incident-gate:' | expect "nor its CI job" sensitive-path
section .github/workflows/ci.yml '        run: scripts/agent-guard/check.sh' | expect "nor the guard job of ci.yml" sensitive-path
section .github/workflows/main.yml '  release-gates:' | expect "nor the job of main.yml that releases it" sensitive-path
section infra/k8s/php-deployment.yaml 'image: x' | exact "plain infra raises infra-path alone" "infra-path: infra/k8s/php-deployment.yaml"
section .github/workflows/main.yml '      - run: echo deploy' | exact "a plain workflow step raises infra-path alone" "infra-path: .github/workflows/main.yml"
section api/config/jwt/private.pem 'x' | expect "a key needs a human" sensitive-path
section .env.prod 'TOKEN=x' | expect "a prod env file needs a human" sensitive-path
section .env.prod.example 'TOKEN=' | expect "an env example does not" clean
section scripts/agent-guard/check.sh 'MAX=100000' | expect "the guard cannot be loosened by an agent" sensitive-path
section agent/data/policy.yaml 'default: ask' | expect "policy.yaml needs a human" sensitive-path
section api/modules/cookbook/src/Entity/Recipe.php 'private string $name;' | expect "an ordinary change is left alone" clean

echo "Auth and permissions"
section api/config/packages/security.yaml 'x: y' | expect "security.yaml needs a human" permissions
section admin/src/auth/authProvider.ts 'export const x = 1' | expect "the admin auth provider needs a human" permissions
section api/modules/cookbook/src/Entity/Recipe.php '#[IsGranted("ROLE_ADMIN")]' | expect "a new access rule needs a human" permissions
section admin/src/auth/authProvider.test.ts 'it("logs in")' | expect "a test of auth does not" clean

echo "Doctrine migrations"
section api/migrations/Version20261001000000.php \
  'public function up(Schema $schema): void' '{' '$this->addSql("CREATE TABLE note (id INT)");' '}' \
  'public function down(Schema $schema): void' '{' '$this->addSql("DROP TABLE note");' '}' | expect "the down of a new table is not destructive" clean
section api/migrations/Version20261001000000.php \
  'public function up(Schema $schema): void' '{' '$this->addSql("ALTER TABLE note DROP COLUMN body");' '}' | expect "dropping a column needs a human" destructive-migration
section api/migrations/Version20261001000000.php \
  'public function up(Schema $schema): void' '{' '$this->addSql("DROP TABLE note");' '}' | expect "dropping a table needs a human" destructive-migration
section api/migrations/Version20261001000000.php \
  'public function up(Schema $schema): void' '{' '$this->addSql("ALTER TABLE note DROP body");' '}' | expect "dropping a column without COLUMN needs a human" destructive-migration
section api/migrations/Version20261001000000.php \
  'public function up(Schema $schema): void' '{' '$this->addSql("ALTER TABLE note DROP CONSTRAINT fk_note");' '}' | expect "dropping a constraint is not destructive" clean

echo "Diff size"
bulk api/src/Big.php 801 | expect "801 lines of code are too many" oversize
bulk api/src/Big.php 800 | expect "800 lines of code are fine" clean
bulk api/tests/BigTest.php 2000 | expect "tests are not counted" clean
bulk api/composer.lock 2000 | expect "lockfiles are not counted" clean
bulk api/src/Big.php 11 | AGENT_MAX_DIFF_LINES=10 expect "the limit can be changed" oversize

echo "Never disable a test, never skip the hooks"
# The forbidden words are assembled here: this file is a test, and would
# otherwise be flagged by the guard each time it is edited.
SKIPPED="markTestSkippe""d"; FIXME="test.fixm""e"; SKIP="it.ski""p"; PYSKIP="pytest.mark.ski""p"; NOVERIFY="--no-verif""y"
section api/tests/FooTest.php "\$this->$SKIPPED('later');" | expect "a skipped PHPUnit test needs a human" disabled-test
section e2e/web/tests/a.spec.ts "$FIXME(true, 'later')" | expect "a fixme journey needs a human" disabled-test
section admin/src/a.test.tsx "$SKIP('x', () => {})" | expect "a skipped Vitest test needs a human" disabled-test
section agent/tests/test_a.py "@$PYSKIP" | expect "a skipped pytest test needs a human" disabled-test
section api/src/Foo.php '$x->skip();' | expect "skip outside a test is fine" clean
section scripts/x.sh "git commit $NOVERIFY" | expect "skipping the hooks needs a human" no-verify
section docs/x.md "never use $NOVERIFY" | expect "saying it in a doc is fine" clean

failures=$(wc -l < "$FAILED")
[ "$failures" -eq 0 ] && echo "All good." || { echo "$failures failed."; exit 1; }

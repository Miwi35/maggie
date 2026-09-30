#!/usr/bin/env bash
#
# The PHP files PHPStan should look at for this branch: everything changed since
# it left origin/main — committed, staged, unstaged and untracked — restricted
# to what api/phpstan.neon analyses. Paths are relative to api/, one per line.
#
# The restriction matters: PHPStan analyses a file passed by name even when
# phpstan.neon leaves it out (tests, a module that is not configured), which
# would report violations CI never sees.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! base="$(git merge-base origin/main HEAD 2>/dev/null)"; then
    echo "changed-php.sh: no origin/main to compare with — run: git fetch origin main" >&2
    exit 1
fi

# Entries of a list under `parameters:` in phpstan.neon (4-space keys, 8-space
# items), e.g. `paths` or `excludePaths`.
neon_list() {
    awk -v key="$1" '
        $0 ~ "^    " key ":" { in_list = 1; next }
        /^    [A-Za-z]/ { in_list = 0 }
        in_list && /^        - / { sub(/^        - /, ""); print }
    ' api/phpstan.neon
}

paths=()
while IFS= read -r entry; do paths+=("$entry"); done < <(neon_list paths)
excluded=()
while IFS= read -r entry; do excluded+=("$entry"); done < <(neon_list excludePaths)

{
    git diff --name-only --diff-filter=ACMR "$base" -- 'api/*.php'
    git ls-files --others --exclude-standard -- 'api/*.php'
} | sort -u | sed 's#^api/##' | while IFS= read -r file; do
    keep=0
    for path in ${paths[@]+"${paths[@]}"}; do
        if [[ "$file" == "$path"* ]]; then keep=1; fi
    done
    for path in ${excluded[@]+"${excluded[@]}"}; do
        if [[ "$file" == "$path"* ]]; then keep=0; fi
    done
    if [ "$keep" = 1 ]; then printf '%s\n' "$file"; fi
done

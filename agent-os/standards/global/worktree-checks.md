# Worktree-Local Checks (`task wt:*`)

The fast path for an agent verifying its own change, on a machine shared with
the dev stack and with other agents.

| Command | Runs on |
|---|---|
| **`task fix:all`** | every fixer in write mode, every linter, PHPStan on changed files — one container at a time |
| `task wt:phpstan -- <files>` | one PHP container, no database |
| `task wt:phpstan:changed` | same, on the PHP files changed since `origin/main` |
| `task wt:cs:fix` / `task wt:cs:check` | one PHP container, PHP-CS-Fixer write / dry run |
| `task wt:up` / `task wt:down` | keep / remove this worktree's Postgres for a test loop |
| `task wt:test:api -- <args>` | PHP + the kept Postgres if `wt:up` started it, else a throwaway one |
| `task wt:ps` / `task wt:prune` | list the kept stacks with their idle time / remove the idle ones |
| `task wt:fix:admin` / `task wt:lint:admin` / `task wt:test:admin` | one Node container |
| `task wt:fix:agent` / `task wt:lint:agent` / `task wt:test:agent` | one uv container |
| `task wt:fix:ciqual` / `task wt:lint:ciqual` / `task wt:test:ciqual` | one uv container |
| `task wt:guard` | the load check the others run first |
| `task wt:cache:prune` | housekeeping |

Measured on the reference machine (16 cores, 30 GB): `wt:phpstan` on a handful
of files, 1.9 s warm. `wt:test:api`, whole suite, 30 s. Neither leaves a
container, a volume or a network behind.

## The test loop: keep the stack, then stop it (MAG-137)

A `wt:test:api` from cold paid ~40 s before the first test (image build, Compose,
Postgres boot). A loop of 41 runs made that 27 minutes. So:

```
task wt:up                                   # once: Postgres up, database created
task wt:test:api -- --testsuite Contract     # as many times as needed: ~2 s before the tests
task wt:down                                 # the moment verification is over, before the PR
```

- **Reuse**: with the stack running, `wt:test:api` drops and recreates the schema
  (two console boots) and runs PHPUnit in a fresh one-off php container. Without
  it, nothing changed: Postgres up, run, `down -v` from a trap.
- **One per worktree.** The Compose project is `maggie-wt-<worktree>-<hash>`;
  both services carry the label `maggie.wt.stack=<project>`. `wt:down` removes
  containers with that exact label and that network — `wt/stack.sh` refuses any
  project not starting with `maggie-wt-`, so dev, e2e and other worktrees are
  out of reach.
- **Safety net, `WT_IDLE_MINUTES` (15).** Activity is a stamp file under
  `~/.cache/maggie/wt-stacks/`, touched by every `wt:*` launch (through the
  guard) and at the end of each `wt:test:api`. An idle stack is removed by
  `wt:guard` (it prunes before measuring, so `wt:prune` also runs on every
  task), and by a detached reaper that `wt:up` starts (one per project, polls
  every minute, exits when the stack is gone): worst case IDLE + 1 min.
- **The guard counts stacks**: it waits, then exits 75, when `WT_MAX_STACKS` (3)
  other worktrees have one up. Your own does not count against you.
- **The php image is tagged by hash**, `maggie-wt-php:<hash>` — `e2e/images.sh
  hash php`, the inputs CI hashes (`.docker/php/**`, the resolved build stanza,
  host UID/GID). Built only when that tag is missing, and CI's image for the
  same hash (`ghcr.io/miwi35/maggie-e2e-php:<hash>`) is pulled first. `task
  wt:image` on an unchanged tree runs `docker image inspect` and nothing else.
  `task wt:cache:prune` drops the old per-worktree tags.

## Memory budget of the stacks

The e2e stack (`docker-compose.e2e.yml`) gives every service a `mem_limit`, and a
`memswap_limit` equal to it: about 2.6 GiB in steady state, Elasticsearch
included (1 GiB, 384 MB heap, ML off — 1.3 GB unbounded before), plus two
one-shots outside it, `admin-build` and the Playwright profile. An overflow is an
OOM kill inside the faulty container, not on the workstation. The wt stack is
2 GB for php (`WT_MEMORY`) and 512 MB for Postgres (`WT_DB_MEMORY`). Raise a
limit in the file; do not remove it.

`task e2e:up` runs the guard first (`wt/guard.sh e2e`): at least
`E2E_MIN_AVAILABLE_MB` (5 GB, against 3 GB for `wt:*`) available and load under
0.8 × cores, else it waits, then exits 75 — use CI. Skipped when `CI` is set.

## Which runner to use

| | Use | Why |
|---|---|---|
| Checking your own change in a worktree | **`task wt:*`** | minimal, one shot, guarded |
| A journey, or anything needing the whole system | `task e2e:*` | the full stack, seeded |
| Working in the main checkout with the dev stack up | `task api:*` | already running |

`task api:test` runs against the dev stack, which mounts the **main checkout** —
in a worktree it tests the wrong code. That is the mistake `wt:*` exists to make
unnecessary.

## The five rules

Everything under `wt:` obeys these. Breaking one hurts the other agents, not
just you.

1. **Minimal.** Only the services the command needs. PHPStan and the linters get
   a single container with no database; only the API suite gets Postgres. Never
   the whole stack for a lint.
2. **One shot.** `docker run --rm`, or Compose up followed by `down -v` from a
   `trap` registered before anything starts — so teardown happens on failure and
   on Ctrl-C too.
3. **No fixed host port.** Nothing is published at all here; nothing needs to be.
4. **Guarded.** `wt/guard.sh` refuses to start below `WT_MIN_AVAILABLE_MB` of
   available RAM or above `WT_MAX_LOAD_RATIO × cores` of 1-minute load. It waits
   a few short rounds, then exits 75 and tells you to use CI. Thresholds live in
   `wt/limits.env`, not in the tasks.
5. **Released.** Resources come back when the command ends, not when the session
   does — except the stack you keep on purpose with `wt:up`, which you stop with
   `wt:down` and which expires after 15 idle minutes.

## The dependency cache

Same idea as `actions/cache` in `ci.yml`, on this machine: install once per
lockfile, share it across worktrees, mount it **read-only**.

```
~/.cache/maggie/
  api/php84-<sha256(api/composer.lock)>/        → /app/vendor      (read-only)
  admin/node-22-alpine-<sha256(package-lock)>/  → /app/node_modules (read-only)
  composer/  npm/  uv/  phpstan/                tool caches
```

- **No per-worktree install for api and admin.** A fresh worktree is ready in
  seconds. The agent and ciqual are the exception: uv builds `.venv` inside the
  project, and pointing two worktrees at one environment would have branches
  with different dependencies overwrite each other. What is shared there is the
  download cache, so building the venv is seconds rather than minutes.
- **Atomic filling** — install into a temp directory, then `mv`. Two agents
  starting at once cannot read a half-written cache; the loser of the rename
  deletes its own copy rather than leaving it behind.
- **A changed lockfile makes a new entry**, exactly like a new CI cache key.
- `task wt:cache:prune` keeps the three newest per component and drops anything
  untouched for 14 days.

Two things that make the read-only mount work, worth knowing before you change
them:

- `api/composer.json` declares the six bundles as `path` repositories with
  `symlink: true`, so `vendor/maggie/core` is a *relative* symlink to
  `../../modules/core`. It resolves against whichever `/app` the container
  mounts — which is why one cache serves every worktree.
- Vite writes into `node_modules/.vite-temp`, a path hard-coded relative to the
  project root. The cache creates those directories at fill time and the task
  covers them with a `mode=1777` tmpfs; a Docker tmpfs belongs to root, and
  these containers run as the host user.

## Caveats

- **`wt:phpstan` on a subset reports `trait.unused`** for a trait whose users are
  outside the file list. That is an artefact of the subset, not a finding. Re-run
  without arguments before believing it.
- **The guard's exit code 75 is not a failure of your change.** It means the
  machine is busy. Push and let CI run it.
- `wt:test:api` and `task e2e:test:api` share one JWT key pair, generated by
  `task e2e:keys` into `api/config/jwt/e2e/` — one generator, one passphrase.
- **The agent tasks use `--frozen`, matching `ci.yml`.** Never drop it: without
  it, `uv run` re-resolves and rewrites the tracked `agent/uv.lock`, so a
  command whose whole job is to *check* the code ends up changing it. Not
  `--locked` either — `agent/uv.lock` is currently stale against
  `pyproject.toml`, on `main` as well, which needs its own ticket. Until then,
  a dependency added to `pyproject.toml` is invisible to both these tasks and
  CI, since CI is `--frozen` too.
- **The ciqual tasks call the venv binaries directly**, not `uv run`: ciqual has
  no lockfile, and `uv run` would create one as a side effect. They also reuse
  the venv (`--allow-existing`), and `uv pip install` only adds — so a
  dependency *removed* from `ciqual/pyproject.toml` stays installed locally and
  passes here while failing CI's fresh venv. Delete `ciqual/.venv` after
  removing a dependency.
- **Editing `wt/limits.env` is how you change the limits.** Exporting
  `WT_MEMORY` or `WT_CPUS` does not override them: Task's `dotenv` wins over the
  process environment for template values. The guard thresholds do honour an
  export, because `guard.sh` reads them from the environment.

## `task fix:all`

The one command before every push. Order matters: the fixers first (PHP-CS-Fixer,
ruff `--fix` then `format` for agent and ciqual, ESLint `--fix`), so the linters
see the final tree; then the linters; then PHPStan on the PHP files changed since
`origin/main`. It stops at the first failure, and exit 75 from the guard means the
machine is busy — push and let CI run it.

- **Everything goes through `wt:*`**, so it fixes and checks the code of the
  checkout it is launched from. The dev-stack `api:cs:fix` and `admin:lint:fix`
  mount the main checkout: from a worktree they would rewrite the owner's files.
- **"Changed" is computed by `wt/changed-php.sh`**: merge-base with `origin/main`,
  committed, staged, unstaged and untracked files, restricted to the `paths` of
  `api/phpstan.neon` (minus `excludePaths`). PHPStan analyses a file passed by name
  even when its config leaves it out, which would report violations CI never sees.
  Run `git fetch origin main` first if `origin/main` is stale or missing.
- Do not add a second way to run a container from a worktree: new tasks go through
  `_php` / `_uv` or the Node block above.

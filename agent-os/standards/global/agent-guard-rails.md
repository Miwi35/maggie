# Guard rails of the autonomous agent (MAG-128)

Agents take tickets unattended and merging deploys to production. These rails decide what an agent may merge on its own. A tripped rail never blocks a merge button: it hands the PR to a human — label `needs-human`, auto-merge off, the reason in a comment — and the owner merges or not.

## What needs a human

`scripts/agent-guard/check.sh` reads a diff and prints one `code: why` per finding (exit 10 = a human merges). `.github/workflows/agent-guard.yml` runs it on every PR, and again when auto-merge is switched on, from the default branch's code (`pull_request_target`, the PR is never checked out: a PR cannot edit its own guard).

| Code | Trips when the diff… |
|---|---|
| `sensitive-path` | touches `infra/`, `.github/`, a secret (`*.pem`, `.env*` but `*.example`, `secrets*`, `api/config/jwt/`), any `policy.yaml`, or the guard itself (`scripts/agent-guard/`) |
| `permissions` | touches the auth files (`security.yaml`, JWT config, Google OAuth controller, MCP access listener, `Voter`s, `admin/src/auth`, `agent/app/auth.py`, mobile `data/auth`), or adds/removes an access rule (`IsGranted`, `access_control`, `ROLE_`, `security:`) outside tests |
| `destructive-migration` | has an `up()` that drops a table or column, truncates, or deletes rows (the `down()` of a new table drops it: ignored) |
| `oversize` | changes more than 800 lines outside tests, lockfiles, `api/contract/` and `agent-os/specs/` (`AGENT_MAX_DIFF_LINES`) — split the ticket |
| `disabled-test` | adds a skipped, fixme'd, quarantined or xfail test |
| `no-verify` | adds `--no-verify` or `--no-gpg-sign` outside Markdown |

Before turning on auto-merge: `task guard:check -- <pr>`. Anything but exit 0 → no `--auto`, add `needs-human`, say why on the ticket. Never skip the hooks, never disable a test to get green: fix it or ask.

## Two red CI runs in a row

The `streak` job of the same workflow counts the CI verdicts of an agent branch (`cyrus/*`), one per commit, cancelled runs ignored (`scripts/agent-guard/ci-streak.sh`). Two reds: auto-merge off, `needs-human`, a PR comment. The session stops and comments the failure on the ticket.

## Emergency stop

Repo variable `AGENT_ENABLED`. Missing or anything but `false` = on.

- `task guard:stop` sets it to `false` and switches off every armed auto-merge; `task guard:resume` lifts it. Needs the owner's `gh` login: an agent token cannot write variables.
- Every agent session starts with `task guard:enabled` (exit 20 = stopped: comment, `needs-human`, do nothing).
- While `false`, the guard hands every `cyrus/*` PR to a human, whatever it touches.
- The dispatcher that picks tickets lives outside this repository and has to read the same variable.

## Tests

`infra/scripts/tests/agent-guard.test.sh` and `agent-guard-streak.test.sh`, run by the `Infra scripts and workflows` job. A new rule comes with a case that trips it and a case that must not.

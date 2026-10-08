# Guard rails of the autonomous agent (MAG-128)

Agents take tickets unattended and merging deploys to production. These rails decide what an agent may merge on its own. A tripped rail never blocks a merge button: it hands the PR to a human — label `needs-human`, auto-merge off, the reason in a comment — and the owner merges or not.

## What needs a human

`scripts/agent-guard/check.sh` reads a diff and prints one `code: why` per finding (exit 10 = a human merges). It runs as the `Sensitive changes and limits` job of the pull-request pipeline (`ci.yml`, MAG-244: one line per push; never on a draft, it starts at `ready_for_review`, MAG-243) and again from `.github/workflows/agent-guard.yml` when auto-merge is switched on, from the default branch's code (`pull_request_target`, the PR is never checked out: a PR cannot edit that guard — the copy in `ci.yml` runs the PR's own scripts, so this second one is what holds).

| Code | Trips when the diff… |
|---|---|
| `sensitive-path` | touches a secret (`*.pem`, `.env*` but `*.example`, `secrets*`, `*-secret.yaml`, `api/config/jwt/`), any `policy.yaml`, the guard itself (`scripts/agent-guard/`, `agent-guard.yml`) or the freeze (`incident-gate*`, `linear.sh`, the gate job); or a workflow line reaches for `secrets` |
| `infra-path` | touches `infra/` or `.github/` (anything not above) |
| `permissions` | changes a workflow's token rights (`permissions:`, `: write`), or touches the auth files (`security.yaml`, JWT config, Google OAuth controller, MCP access listener, `Voter`s, `admin/src/auth`, `agent/app/auth.py`, mobile `data/auth`), or adds/removes an access rule (`IsGranted`, `access_control`, `ROLE_`, `security:`) outside tests |
| `destructive-migration` | has an `up()` that drops a table or column, truncates, or deletes rows (the `down()` of a new table drops it: ignored) |
| `oversize` | **off by default since 8 Oct. (owner)**: a large PR is reviewed, not split by rule. `AGENT_MAX_DIFF_LINES` > 0 turns it back on (lines outside tests, lockfiles, `api/contract/` and `agent-os/specs/`) |
| `disabled-test` | adds a skipped, fixme'd, quarantined or xfail test |
| `no-verify` | adds `--no-verify` or `--no-gpg-sign` outside Markdown |

**Emergency (MAG-184):** when `infra-path` is the only finding and the branch's ticket (`cyrus/mag-n-…`) is in the Linear state « Emergency », the guard waives it and the fix merges itself (`scripts/agent-guard/emergency.sh`, also run by `task guard:check`; no Linear answer = no waiver).

Before turning on auto-merge: `task guard:check -- <pr>`. Anything but exit 0 → no `--auto`, add `needs-human`, say why on the ticket. Never skip the hooks, never disable a test to get green: fix it or ask.

## Red CI

No job of `ci.yml` gives up on a red PR any more (the « two reds » streak was removed on 7 Oct.): it counted a
flaky test or a run still queued as a failure of the code, and never took its `needs-human` back once the PR was
green. The merge train decides: two real repairs (a new head each, the failure the branch's), one automatic re-run of
a red job per head for flaky tests, then the owner.

## Emergency stop

Repo variable `AGENT_ENABLED`. Missing or anything but `false` = on.

- `task guard:stop` sets it to `false` and switches off every armed auto-merge; `task guard:resume` lifts it. Needs the owner's `gh` login: an agent token cannot write variables.
- Every agent session starts with `task guard:enabled` (exit 20 = stopped: comment, `needs-human`, do nothing).
- While `false`, the guard hands every `cyrus/*` PR to a human, whatever it touches.
- The dispatcher that picks tickets lives outside this repository and has to read the same variable.

## Tests

`infra/scripts/tests/agent-guard.test.sh` and `agent-guard-emergency.test.sh`, run by the `Infra scripts and workflows` job. A new rule comes with a case that trips it and a case that must not.

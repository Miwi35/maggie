# Implement Spec

Execute a shaped spec task by task: tests first, one commit per task, then the
e2e journey, the Linear docs, and the PR.

`/shape-spec` decides **what** to build. This command only builds it. If the
spec does not answer a question, that is a spec bug — stop and ask (Step 8).
Never guess.

## Usage

```
/implement-spec                                      # infer spec from the current branch
/implement-spec agent-os/specs/2026-09-30-1151-…     # explicit spec folder
/implement-spec MAG-124                              # by Linear issue key
```

## Prerequisites

Run this in **normal mode, not plan mode** — this command writes code and
commits. Run `task guard:enabled` first: exit 20 means the emergency stop is
on — comment on the ticket, add `needs-human`, stop.

Resolve `$ARGUMENTS` to a spec folder under `agent-os/specs/`:

- **Spec folder** — use it as given.
- **Linear issue key** — find the folder that mentions it
  (`grep -rl "MAG-124" agent-os/specs/`). If nothing matches, stop: the ticket
  has not been shaped. Tell the user to run `/shape-spec` first.
- **Empty** — derive the key from the branch name
  (`cyrus/mag-124-…` → `MAG-124`), then resolve as above.

## Context Loading

Read exactly these three files up front:

| File | What you take from it |
| --- | --- |
| `plan.md` | The ordered `## Tâche N` list — your work queue |
| `shape.md` | Decisions already made: scope, `Hors périmètre`, `Décisions` |
| `references.md` | The existing code each task should look like |

**Do not read `standards.md`.** It holds the *full copied content* of every
standard the spec touches — often thousands of lines. Reading it floods the
context with rules that have nothing to do with the task in front of you.
Standards arrive per task, in Step 1, via `/inject-standards`.

Skip `## Tâche 1 : Enregistrer la spec` — `/shape-spec` already saved the spec
folder. Start at Task 2.

## Process

### Step 1: Inject Only This Task's Standards

Work out which components the task touches, from its paths and from
`references.md`. Then call `/inject-standards` in **explicit mode** with the
narrowest scope that covers them:

```
/inject-standards api/testing api/entities     # an API task touching entities
/inject-standards admin/react-admin admin/testing
/inject-standards mobile/android-app mobile/testing
/inject-standards agent/architecture agent/testing
```

Map the touched component to its standards folder: `api/`, `admin/`, `agent/`,
`mobile/`, plus `global/` for real-time and cross-cutting testing rules, and
`agenda/`, `docker/`, `deployment/` when the task reaches them. Check
`agent-os/standards/index.yml` for what exists in a folder — read the index,
not the standards themselves.

Pass the scope, not the whole tree. A task editing one React view has no use
for the Android signing rules.

### Step 2: Write the Tests First

Before touching implementation code:

- **Feature task** — write the tests that describe the behaviour the task
  asks for. Run them and confirm they fail *for the right reason*: the missing
  behaviour, not a typo or an unloaded fixture.
- **Bug task** — write the reproduction test first, and make it fail exactly
  the way the report describes. If you cannot reproduce it, you do not yet
  understand the bug: stop (Step 8) rather than fix something adjacent.

Honour the repo's non-negotiable test rules (CLAUDE.md, `api/testing`,
`global/testing`). For every API endpoint or MCP tool that means: 401
unauthenticated, 400 bad input, happy path asserting **DB state**,
`assertMercureUpdatePublished`, `assertElasticsearchIndexDispatched`, with
`resetMercure()` + `resetAsyncTransport()` in `setUp`. Use the traits in
`api/tests/Support/`; never hand-roll that setup.

Follow the module's existing layout: `api/modules/<module>/tests/<Area>/`,
namespace `Maggie\<Module>\Tests\<Area>`.

### Step 3: Implement

Write the smallest change that makes those tests pass, in the style of the code
`references.md` points at. Anything you notice but the spec did not ask for goes
in the PR body as a note — not in the commit, and not in `Hors périmètre`
territory.

### Step 4: Lint and Test the Touched Component

Run only what the task touched. Never the full `task ci` — far too slow for a
per-task loop.

| Component | Lint | Tests |
| --- | --- | --- |
| `api/` | `task api:lint` | `task api:test -- --testsuite <Module>` |
| `agent/` | `task agent:lint` + `task agent:format:check` | `task agent:test` |
| `admin/` | `task admin:lint` + `task admin:typecheck` | `task admin:test` |
| `mobile/` | CI only (local `lintProdRelease` hits a known AGP/K2 bug) | `cd mobile && ./gradlew testProdReleaseUnitTest` |

`<Module>` is the PHPUnit testsuite — the module directory capitalised:
`Calendar`, `Core`, `Cookbook`, `Grocery`, `Notification`, `Finance`.

Everything runs through the Taskfile in Docker. Never call `php`, `composer`,
`bin/console`, `npm`, `pytest` or `uv` on the host.

**If you are in a git worktree** (parallel agent sessions), `task *:test` runs
`docker compose exec` against the dev stack, which mounts the **main checkout,
not your worktree** — it would test the wrong code and report a meaningless
pass. Do not start a second stack either; host ports collide. So:

- Run the **lint** steps — they are static and safe.
- Skip the local test run, say so explicitly, and let CI verify after the push.
- Still write the tests. Skipping the *run* is a worktree limitation; skipping
  the *tests* is not allowed.

Lint of every touched component and the task's targeted tests (never the full suite: CI runs it) must pass before committing. A
failure is yours to fix: do not commit red and do not move on. If the failure
sits in code the task never touched and you cannot explain it, treat it as an
ambiguity (Step 8).

### Step 5: Commit

One commit per task, tests and implementation together so the commit is green
on its own.

Subject in English, stating the intent — what changes for the user or the code
— matching this repo's log ("Derive categorization rules from the statement
itself", "Give a failed bank connection a way out"), not the mechanics ("add
method", "update service"). Body explains why.

**No `Co-Authored-By` trailer.** This repository is public: never commit
secrets, `.env.local`, `*.pem`, keystores or real personal data.

Then loop back to Step 1 for the next `## Tâche`.

### Step 6: Add or Extend the Module's E2E Journey

Once the last task is committed, the feature needs one journey that exercises
it the way a client actually would — the test that catches the wiring every
unit test mocks away.

- **Web** — extend the module's Playwright journey; **mobile** — the Maestro
  flow. E2E use a deterministic fake LLM, never a live model.
- **If no Playwright/Maestro harness exists yet**, do not build one here: that
  is its own ticket. Instead extend the module's HTTP-level journey in
  `api/modules/<module>/tests/Api/`, which is the real end-to-end coverage
  available today — authenticate, walk the full request sequence the feature
  needs, assert the resulting state. Note in the PR body that browser-level
  coverage is still owed.

Run the module suite, then commit.

### Step 7: Update the Linear Documents

Via the Linear MCP tools, bring the written record in line with what shipped:

- **The module's functional spec** — update the sections this feature changes.
  Describe the behaviour that now exists, in the document's own voice and
  language. Do not paste the plan or a changelog into it.
- **The mode d'emploi (user guide)** — update it when the feature changes what
  the user sees or does. If nothing is user-facing, say so in the PR body
  rather than padding the document.

Edit the existing documents. Do not create new ones to dodge an edit.

### Step 8: Open the PR and Hand Over

- Push the branch and open the PR against `main`. The body says what changed
  and why, **links the Linear ticket by URL**, and lists anything you noticed
  but deliberately left alone.
- Move the ticket to **In Review**.

Report back: the PR URL, one line per commit, which checks you ran and their
result (naming anything CI must verify because you were in a worktree), and the
document updates you made.

## Blocked on an Ambiguity

This applies at **every step above**, not only at the start.

If a decision is genuinely missing — the spec supports two readings that lead
to materially different code, a task contradicts `shape.md`, or a test failure
implies the spec itself is wrong — do not pick one and carry on. Guessing here
produces a PR that looks finished and reviews as correct while doing the wrong
thing, which costs far more than stopping.

Stop cleanly:

1. Leave the tree green and committed at the last task that passed, and push
   the branch so no work is lost.
2. Comment on the Linear ticket: the specific question, the readings you see
   and what each would imply, which tasks are done, which task is blocked.
3. Add the `needs-human` label.
4. Stop. **Do not open the PR and do not move the ticket to In Review** — In
   Review claims the work is ready for review; this work is waiting on an
   answer.

This is for missing decisions, not for ordinary difficulty. A hard task you can
still reason through from `shape.md` is not blocked — keep going.

## Tips

- **One task, one commit** — a reviewer should be able to read the branch as a
  sequence of decisions.
- **Never read `standards.md`** — that is the whole point of per-task
  `/inject-standards`.
- **Trust `Hors périmètre`** — if `shape.md` excluded it, leave it out and
  mention it in the PR body.
- **Tests first, always** — for a bug, the reproduction test is the proof you
  understood it.

## Integration

Second half of the agent-os protocol: `/shape-spec` produces the spec folder,
`/implement-spec` executes it. Calls `/inject-standards` once per task in
explicit mode.

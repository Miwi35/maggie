# Maggie

Personal AI agent platform. Tickets live in Linear (workspace Meven, team **Maggie**, key `MAG`).

| Directory | Stack |
|---|---|
| `api/` | Symfony 7, API Platform 4, Doctrine, Mercure, MCP bundle — modular bundles in `api/modules/` (calendar, core, cookbook, grocery, notification, finance) |
| `agent/` | Python 3.12, FastAPI, Anthropic Claude, MCP client |
| `admin/` | React (API Platform Admin), Vite |
| `mobile/` | Kotlin, Jetpack Compose, Ktor, Koin |

Project skills are in `.claude/skills/`, agent-os commands in `.claude/commands/agent-os/`, standards and specs in `agent-os/`.

## Workflow

- **Pick the path from the ticket's type label** — the effort follows the ticket, not one ceremony for all:

  | Type | What it is | Path | E2E | After merge |
  |---|---|---|---|---|
  | `Feature` | something the user sees or does in the app | spec folder: `/shape-spec` then `/implement-spec`; a small one gets a short plan comment instead of a spec folder | journey required | **Recette** |
  | `Bug` | the app does not do what it should | `/fix-bug` — reproduction test red first | extend the journey ticket if user-visible | **Recette** |
  | `Task` | not acceptable through the UI: docs, refactoring, upgrades, CI/CD, tests, tooling | `/run-task` | `N/A — task` | **Done** |

  No type label → decide from the content, set it, and say so in the plan. Found a bug while using the app → `/report-bug`.
- Pull standards on demand with `/inject-standards` (index: `agent-os/standards/index.yml`) — never paste all of them into context.
- One ticket = one branch = one PR that links the ticket.
- Delivering a spec also means updating the module's functional spec and the user guide (Linear Documents, entry point: team doc « Index de la documentation Maggie »).
- Keep specs, prompts and comments short and actionable: acceptance criteria and e2e journeys, not prose.

## Reporting — short, once

Writing costs as much time as coding: say things once, where they belong.
- **Ticket**: one final comment, **10 lines at most** — what was delivered, the PR link, what is left or out of scope. No second summary.
- **Decisions**: 4 lines each (Dilemma · Options · Choice · Why), posted when taken.
- **PR**: the detail — changes, verification, decisions, Review section.
- **Push first, paperwork while CI runs.** As soon as the change is verified and reviewed, commit and push; follow-up tickets, decision comments, the Recette note and Linear docs are written while the checks run, never before the push.
- **A session ends only on a green CI, with the PR set to merge itself.** Once the PR is open, move the ticket to **In Review** yourself (Linear MCP) — Linear does not do it for a PR nobody is asked to review — and turn on auto-merge: `gh pr merge <number> --squash --auto` — GitHub merges it the moment the required checks pass, and **merging deploys to production**. **Nothing reaches the owner's acceptance without a green e2e**: the e2e jobs are required checks, so a red journey blocks the merge — and every `Feature` or `Bug` is `blockedBy` the ticket that made them required (MAG-96). Then wait with **`task ci:watch -- <number>`** — the only way to wait for CI, **never a hand-written `until`/`sleep` loop**: it stops at once on a conflicting PR (GitHub runs no CI then: rebase, resolve, `git push --force-with-lease`, run it again), gives up if no CI run starts within 2 minutes, and only then watches the checks. Red → read the failing job's log (`gh run view --log-failed`), fix, push, watch again. Still red after two fixes → `gh pr merge <number> --disable-auto`, comment the failure, `needs-human`, stop. Never auto-merge a PR the review loop did not accept, or one touching `infra/k8s`, secrets or `.github/workflows` (MAG-128): leave those for the owner to merge.
- **Recette comment** (`Feature` and `Bug`, not `Task`): when the PR is open, post a separate ticket comment titled **Recette** — what the owner does **in production** to accept it: where (web, mobile, voice, asking Maggie), numbered steps from a logged-in user, the expected result of each, and what would mean it failed. Use real screen labels and a test value to type; no code, no dev setup. It is what the owner follows once the ticket reaches Recette.
- **Never end a session with the ticket In Progress.** A PR moves it to In Review by itself. No PR (an analysis, Linear docs only): move it yourself — `Task` → Done, otherwise Recette. Waiting for an answer → `needs-human`.

## Creating Linear tickets

Tickets are created by agents through the Linear MCP and picked up unattended by the dispatcher, so each one must be workable without a conversation. Before creating, search Linear for a duplicate. Write in French.

- **Description**, in this order: **Contexte** (why, with links) · **À faire** · **Critères d'acceptation** (verifiable, one per line) · **Parcours e2e** (Given/When/Then and the journey ticket MAG-99…103 it extends; `N/A — task` for a Task) · **Fichiers probables** · **Hors périmètre**. End with `Définition de « terminé » : agent-os/standards/global/testing.md` — link it, do not copy it.
- **Team** Maggie, **project** of the feature (attached to an initiative), **priority** set (Urgent: fixes and test foundation · High: Maggie works · Medium: rest), **type label** `Feature` (user-visible), `Bug` or `Task` (not acceptable through the UI) — it decides the path and whether the ticket goes to Recette.
- **Dispatcher labels**: one `area:*` per component touched, `lock:migration` if it adds a schema migration, `needs-shaping` if a spec must be shaped first.
- **Dependencies** as `blockedBy`, never only in prose: the dispatcher skips blocked tickets.
- One ticket = one deliverable a single PR can close; split anything larger into a project with one ticket per plan task.

## Autonomy — decide by default, ask only when it matters

Agents take tickets unattended, from shaping to PR. At any step, **decide and move on** when the ticket, the module's functional spec, the standards or the existing code give an answer.

Every decision taken alone leaves a trace: a comment on the Linear ticket, posted when the decision is made, with **Dilemma** (the question, in one line), **Options** (those considered), **Choice** and **Why** (the ticket, spec, standard or code that settles it). Repeat the list in the PR description.

Stop and ask only for:
- a product, UX or data-model decision nothing answers and that is costly to undo;
- conflicting requirements;
- something destructive or irreversible (deleting data, prod, secrets, infra);
- a missing access or credential;
- the same failure twice (CI still red after a fix, a rebase conflict you cannot resolve).

To ask: reply in the ticket's agent thread with short questions, each as options with your recommendation first; add the `needs-human` label; stop cleanly. When the answer arrives in the thread, remove `needs-human` and resume where you stopped.

## Review loop — before opening the PR

Once the ticket is implemented and its tests pass, the session orchestrates a review until the reviewer accepts:

1. Send the branch to a fresh `code-reviewer` subagent with the ticket, the spec folder and the base branch.
2. `CHANGES_REQUIRED` → hand the blocking findings to a `fullstack-developer` subagent to fix (tests first when a test is missing), then re-run lint and the targeted tests of what the fix touched.
3. Resubmit to a new `code-reviewer` with the previous findings and the commit it reviewed: it checks **only the fixes** since then, not the whole branch again. Repeat until `ACCEPT`.
4. Three rounds without `ACCEPT`: stop and ask (`needs-human`), listing what is left.

The PR description ends with a **Review** section: number of rounds, blocking findings fixed, minor findings left as is.

## Commands — Docker only

Never run `php`, `composer`, `bin/console`, `npm`, `pytest` or `uv` on the host: runtimes live in containers. Use the Taskfile.

| Component | Lint | Tests |
|---|---|---|
| API | `task api:lint` (PHP-CS-Fixer check + PHPStan) | `task api:test` (`-- --testsuite <Module>` to narrow) |
| Agent | `task agent:lint` + `task agent:format:check` | `task agent:test` |
| Admin | `task admin:lint` + `task admin:typecheck` | `task admin:test` |
| Mobile | CI (local `lintProdRelease` crashes on a known AGP/K2 bug) | `cd mobile && ./gradlew testProdReleaseUnitTest` (Java 21 via `org.gradle.java.home`) |

Symfony console: `task api:console -- <args>`.

**Before every push: `task fix:all`.** It runs every fixer in write mode (PHP-CS-Fixer, ruff `--fix` + `format` for agent and ciqual, ESLint `--fix`), then every linter, then PHPStan on the PHP files you changed since `origin/main` (committed or not) — none may introduce a violation. It runs on the code of the checkout you launch it from, worktree included, and never touches another checkout. Commit what it rewrote, then push. CI fails on code php-cs-fixer would still change.

**In a git worktree** (parallel agents): every `task api:*`, `task admin:*`, `task agent:*` and `task ciqual:*` command runs `docker compose exec` against the dev stack, which mounts the main checkout, not your worktree — tests and linters would check the wrong code, and **formatters would rewrite the owner's working copy: never run them from a worktree**. Use `task fix:all` and `task wt:*` instead: minimal one-shot containers, nothing published, `--rm` / `down -v` even on failure, and a load guard that waits then sends you to CI (exit 75 = the machine is busy, not your change). Details: `agent-os/standards/global/worktree-checks.md`.

| Worktree checks | |
|---|---|
| `task fix:all` | every fixer, every linter, PHPStan on changed files |
| `task wt:phpstan -- <files>` | PHPStan, one container, ~2 s warm |
| `task wt:phpstan:changed` | PHPStan on the PHP files changed since `origin/main` (also `task api:phpstan:changed`) |
| `task wt:cs:fix`, `task wt:cs:check` | PHP-CS-Fixer, write / dry-run |
| `task wt:test:api` | PHPUnit on a throwaway Postgres |
| `task wt:lint:admin`, `task wt:test:admin` | ESLint + tsc, Vitest |
| `task wt:lint:agent`, `task wt:test:agent` | Ruff, pytest |
| `task wt:lint:ciqual`, `task wt:test:ciqual` | Ruff, pytest |

**Contract files** (`api/contract/`, MAG-104): the OpenAPI document, the MCP tool list, the Mercure topics, the client query parameters and recorded API responses — generated by the `Contract` PHPUnit suite, read by the mobile and admin suites. Changing the API's shape makes them fail; that is the point. Read the failure, then `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` and commit the diff so a reviewer sees it. Details: `api/contract/README.md`.

For a journey, or anything needing the whole system, bring up the e2e stack — one per worktree, one ephemeral loopback port, isolated from dev:

| E2E stack | |
|---|---|
| `task e2e:up` | start (build, install, migrate), prints the URL |
| `task e2e:seed` | deterministic fixtures + Elasticsearch rebuild |
| `task e2e:smoke` | the smoke journey (HTTP) |
| `task e2e:web` | the Playwright journeys for the admin (MAG-97) |
| `task e2e:web:lint`, `task e2e:web:typecheck` | check them without a stack — in `task lint:all` |
| `task e2e:admin:build` | rebuild the admin bundle after editing `admin/src` |
| `task e2e:test:api` | PHPUnit inside the stack |
| `task e2e:eval` | the prompt-lab scenarios on the **real** model — nightly, outside CI |
| `task e2e:down` | remove everything |

Test login: `POST /api/auth/e2e/login` with `X-E2E-Token`, mounted only in `APP_ENV=e2e`. External services (Enable Banking, Google, Whisper, TTS) are simulated, and so is the model: `LLM_PROVIDER=fake` makes Maggie answer from the scenario files in `agent/fixtures/fake-llm/`, through the real tool loop, the real AG-UI streaming and the real MCP (MAG-95). Nothing matches → she answers `[fake-llm] aucun scénario…`, so add the scenario before the step. Judgement — tone, right tool, keeping the thread — is checked apart by `task e2e:eval` on the real model; an assertion that would hold with any plausible wording belongs in a journey with the fake instead. Details: `agent-os/standards/global/e2e-environment.md`.

## Tests — every change

A ticket is **done** only when all five hold. Full rules, including what each kind of unit owes: `agent-os/standards/global/testing.md`.

1. **Every unit touched has a test.** API endpoint or MCP tool: 401 unauthenticated, 400 bad input, happy path asserting **DB state**, `assertMercureUpdatePublished`, `assertElasticsearchIndexDispatched`. Use `MercureAssertionTrait` + `ElasticsearchAssertionTrait`; call `resetMercure()` + `resetAsyncTransport()` in `setUp`. Reference: `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`. Also: entity → Mercure Create/Update/Delete, admin component → render + interaction + error, mobile ViewModel → one test per state transition.
2. **At least one e2e journey covers the feature**, new or extended (Playwright web, Maestro mobile, deterministic fake LLM). The harness isn't built yet: until then, write the journey as Given/When/Then in the spec's `plan.md` and name the journey ticket that will carry it.
3. **Every bug fix ships its reproduction test**, written red before the fix.
4. **CI green on a PR** that links the ticket.
5. **Module functional spec and user guide updated in Linear** (ADR-006).

No user-facing behavior (standards, docs, infra, pure refactor)? Write `E2E: N/A — <reason>` in `plan.md`. An exemption that isn't written down is a missing test. Before committing: lint of every touched component, and the **targeted tests** — those of the change and of the code it touches (a `--filter` or `--testsuite`, one spec file, one pytest path). **Never the full suite locally: CI runs it on every PR.**

## Commits

- English, imperative subject that says the intent (what changes for the user or the code), body explains why.
- **No `Co-Authored-By` trailer.**
- This repository is **public**: never commit secrets, `.env.local`, `*.pem`, keystores or real personal data.

## Gotchas

- **MCP auth**: `/_mcp` requires a bearer (user JWT, or `SERVICE_TOKEN` + `X-Maggie-User-Id`). Tools read the user through `McpUserContext` (`requireUser()`), filter reads by user, and catch `MissingMcpUserException`. Never `findAll()[0]`.
- **MCP discovery**: a module missing from `discovery.scan_dirs` in `api/config/packages/mcp.yaml` has its tools silently hidden. New bundle → add `modules/<name>/src` and check with a real `tools/list`.
- **API Platform**: never declare a `Patch` operation on an `uriTemplate` without an identifier (`/…/me`): it instantiates a new entity → 500. Use a dedicated REST controller that only applies the fields present. Enforced by `MeOperationContractTest`.
- **Serialized names**: Symfony serialises `isCushion()` as `cushion`, so a client DTO declaring `isCushion` silently reads its own default. The Mercure payloads spell the same flags `isCushion` — the two channels disagree. `DtoContractTest` on the mobile side checks every declared field against a recorded response.
- **Elasticsearch**: indexable collections are served from ES and fall back to Doctrine only on exceptions — a stale index returns an empty list silently. Check with `task api:console -- app:elasticsearch:status --check`.
- Entities use ULID; events follow the Google Calendar model (RRULE, reminders, exception instances). API Platform 4 collections use the `member` key.
- Mobile autocomplete opens a dedicated full-screen search, never an inline dropdown.

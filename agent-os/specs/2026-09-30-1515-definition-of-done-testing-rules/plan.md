# Definition of Done and Systematic Testing Rules — Plan

Ticket: [MAG-92](https://linear.app/meven/issue/MAG-92/definition-de-termine-et-regles-de-test-systematiques)
Project: Suite e2e et tests systématiques

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`, `standards.md`. No visuals, no code references beyond
the files edited.

## Task 2: Write the Definition of Done in `agent-os/standards/global/testing.md`

Single canonical source: the five conditions, the per-unit coverage table, the e2e rule
with its transition and its N/A escape hatch, red-first bug fixes, CI green, ADR-006 docs.
Update the pyramid (E2E is no longer "deferred to Phase 5"; add the nightly eval row per
ADR-005) and the mobile run command. Update the `global/testing` description in
`index.yml` so `/inject-standards` surfaces it for what it now holds.

## Task 3: Make the rules reachable from where agents actually work

- `.claude/skills/pre-commit/SKILL.md` — the Definition of Done as a checkbox list, since
  this is the skill loaded right before a commit.
- `.claude/skills/{api,admin,agent,mobile}-testing/SKILL.md` — a "Required Coverage"
  block per component, plus red-first and the e2e pointer.
- `agent-os/standards/{api,admin,agent,mobile}/testing.md` — a pointer to the Definition
  of Done; `api/testing.md` also gains the endpoint enforcement list (401/400/DB/Mercure/ES),
  which only existed for MCP tools.
- `.claude/agents/fullstack-developer.md` — "write the tests, then run them".
- `CLAUDE.md` — the five conditions, since it is always in context.

## Task 4: Put the e2e section in every plan

`.claude/commands/agent-os/shape-spec.md`: a mandatory "Shape the E2E Journey" step
(extend MAG-99→103 before creating), `global/testing` always injected, and a documented
`plan.md` structure with `## Tests`, `## E2E journey` and `## Definition of Done`.

## Task 5: Tests checklist in Linear

Linear document « Définition de « terminé » », paste-ready for a team issue template.

## Tests

| Unit touched | Tests |
|---|---|
| — | No production code changes: standards, skills, commands, subagent prompt, CLAUDE.md |

No lint or test suite covers prose, so verification for this ticket is different in kind.
What was actually run:

- `index.yml` and every skill frontmatter parsed as YAML, `name` and `description` present
- every relative link in the standards and in this spec resolved from its own file
  (`agent-os/standards/global/testing.md` → `../../../.claude/skills/pre-commit/SKILL.md`
  included)
- every referenced symbol checked to exist: `MercureAssertionTrait`,
  `ElasticsearchAssertionTrait` (`resetAsyncTransport`, `assertElasticsearchIndexDispatched`,
  `assertElasticsearchDeleteDispatched`), `SecurityTokenTrait::loginFixtureUser`,
  `AuthenticatedTestTrait::authHeaders`, `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`,
  `api/modules/grocery/tests/Controller/CheckGroceryItemControllerTest.php`
- every ticket reference checked against Linear (MAG-94→MAG-103, ADR-005, ADR-006)

No bug fix in scope, so no reproduction test.

## E2E journey

`N/A — rules and agent instructions only, no user-facing behavior.`

This is the first exercise of the escape hatch the ticket introduces: without it, the very
ticket that makes e2e mandatory could not be closed.

## Definition of Done

- [x] Unit/integration tests — N/A, no production code (justified above)
- [x] E2E journey — N/A, no user-facing behavior (justified above)
- [x] Bug fix reproduction test — N/A, no bug fixed
- [ ] CI green on a PR linking MAG-92
- [ ] Linear updated: « Définition de « terminé » » document; the team issue template
      itself needs the Linear UI (no MCP write API for templates)

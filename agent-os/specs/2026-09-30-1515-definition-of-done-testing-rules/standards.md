# Standards for Definition of Done and Systematic Testing Rules

This spec **rewrites** the standards it applies, so quoting them here would freeze a copy
that is wrong the moment the ticket lands. Read the files instead:

| Standard | Role in this ticket |
|---|---|
| [global/testing](../../standards/global/testing.md) | Canonical Definition of Done — written by this spec |
| [api/testing](../../standards/api/testing.md) | MCP tool and endpoint minimums had to match it |
| [admin/testing](../../standards/admin/testing.md) | Component minimums (render, interaction, error) |
| [agent/testing](../../standards/agent/testing.md) | Route and gateway minimums, `respx` mocking |
| [mobile/testing](../../standards/mobile/testing.md) | ViewModel state-transition minimums |
| [global/taskfile](../../standards/global/taskfile.md) | Docker-only commands, quoted in the run table |

## Decisions that bind this spec, from Linear

- **ADR-005** — e2e on an isolated stack with a deterministic fake LLM (Playwright web,
  Maestro mobile), blocking for PRs; the real model is checked by a separate nightly,
  non-blocking eval suite.
- **ADR-006** — Linear holds the human documentation: the user guide and one living
  functional spec per module. Delivering an implementation spec means updating them. That
  is a condition of "done".
- **ADR-008 / ADR-009** — tickets are executed by agents with auto-merge; quality rests on
  the test suite and the user's acceptance, not on code review. Hence the strictness.

## Prerequisite tickets referenced by the rule

MAG-94 (e2e environment), MAG-95 (fake LLM), MAG-96 (blocking CI), MAG-97 (Playwright
harness), MAG-98 (Maestro harness), MAG-99→MAG-103 (the module journeys a feature extends).

# Definition of Done and Systematic Testing Rules — Shaping Notes

## Scope

Write down what makes a ticket "done", and make the rule reachable from every place an
agent works: the standard, the skills loaded during implementation, the command that
generates specs, and `CLAUDE.md`.

Cause (ADR-005): 81 of 271 commits were fixes or regressions, with no real e2e coverage.
The rule exists to stop that, ahead of the autonomous executor (ADR-008/009) which relies
on the test suite rather than on code review.

## Decisions

Taken alone, per the autonomy rule. Each is a decision nothing in the repo settled.

### One canonical source, pointers everywhere else

**Options:** repeat the Definition of Done in each skill / keep it only in the standard /
one source plus short pointers.
**Choice:** `agent-os/standards/global/testing.md` is canonical; skills carry only their
component's minimum list and a pointer.
**Why:** CLAUDE.md forbids pasting all standards into context, and duplicated rules drift.
`pre-commit` is the exception and carries the full checklist — it is the skill loaded at
the exact moment the question "is this done?" gets asked.

### E2E mandatory, but the harness does not exist yet

**Options:** wait for MAG-97/98 before making the rule binding / require the executable
flow now / require the journey to be *written* now, executable once the harness lands.
**Choice:** the third.
**Why:** ADR-005 makes e2e blocking for PRs, but MAG-94/95/97/98 are all in Backlog.
Requiring an executable flow today would block every ticket; waiting would ship a dozen
features with no journey recorded, and MAG-99→103 would have to reverse-engineer them.
Writing the Given/When/Then keeps the intent at the moment it is known, and the journey
tickets become transcription work.

### Tickets with no user-facing behavior

**Options:** silence (agents decide case by case) / an explicit `E2E: N/A — <reason>` in
`plan.md`.
**Choice:** the explicit escape hatch, plus the rule "an exemption that isn't written down
is a missing test".
**Why:** standards, docs, prompt and infra tickets have no journey — this ticket included.
Without a written exemption the rule is unfollowable, and an unfollowable rule gets ignored
wholesale. Written down, it stays auditable.

### Extend a journey before creating one

**Options:** one journey per feature / extend the five existing module journeys.
**Choice:** extend MAG-99 (chat), MAG-100 (agenda), MAG-101 (recipes/meals/groceries),
MAG-102 (finance), MAG-103 (settings/search/notifications); a new journey only when none
fits.
**Why:** the project already splits journeys by module. Per-feature journeys would multiply
the fixtures and the CI time for the same coverage.

### The Linear issue template

**Options:** create the template via MCP / a Linear document ready to paste, with the
manual step flagged.
**Choice:** the document « Définition de « terminé » », plus an explicit note that the
template itself must be created in Team settings → Templates.
**Why:** the Linear MCP exposes `list_templates` and `get_template` but no write tool, and
no team template exists yet. Not a product decision — a missing capability. Everything
else in the ticket is delivered; this one step needs the UI.

## Context

- **Visuals:** none
- **References:** the files edited; `api/modules/grocery/tests/` as the reference test suite
- **Product alignment:** project « Suite e2e et tests systématiques »; prerequisite of
  ADR-008 (autonomous ticket execution with auto-merge)

## Standards Applied

- `global/testing` — the subject of the ticket
- `api/testing`, `admin/testing`, `agent/testing`, `mobile/testing` — the per-component
  minimums had to match what each already prescribes

## Observation, out of scope

`CLAUDE.md` points to `/implement-spec`, which does not exist in `.claude/commands/`.
Worth a ticket.

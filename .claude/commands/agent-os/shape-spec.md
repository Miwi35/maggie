# Shape Spec

Gather context and structure planning for significant work. **Run this command while in plan mode.**

## Important Guidelines

- **Interactive mode: always use AskUserQuestion** when asking the user anything
- **Autonomous mode: never use AskUserQuestion** — source answers from the ticket, or escalate (see [Autonomous Mode](#autonomous-mode))
- **Offer suggestions** — Present options the user can confirm, adjust, or correct
- **Keep it lightweight** — This is shaping, not exhaustive documentation

## Modes

This command runs in one of two modes. **Detect which one before Step 1.**

You are in **autonomous mode** if any of these hold:

- There is no interactive user to answer — the session was started by an agent
  runner (Cyrus) from a Linear ticket, rather than by someone typing
- The session is running on a `cyrus/*` branch or inside a git worktree
- The invocation passed a Linear issue key rather than a prose description

Otherwise you are in **interactive mode**: follow the steps exactly as written,
asking through AskUserQuestion.

## Prerequisites

**Interactive mode** must be run in plan mode. Before proceeding, check if you
are currently in plan mode. If NOT in plan mode, **stop immediately** and tell
the user:

```
Shape-spec must be run in plan mode. Please enter plan mode first, then run /shape-spec again.
```

Do not proceed with any steps below until confirmed to be in plan mode.

**Autonomous mode** has no plan-mode requirement — there is no plan to approve
interactively. Skip this check entirely.

## Autonomous Mode

Same nine steps, same output folder. The only difference is where answers come
from: **the written record instead of the user.**

### Where each answer comes from

Read these before Step 1, in this order of authority:

| Source | Use it for |
| --- | --- |
| The Linear ticket — title, description, comments (Linear MCP) | Scope, expected outcome, constraints, acceptance criteria |
| The module's functional spec in `agent-os/product/` (e.g. `finance-functional-spec.md`) | Existing behaviour the feature extends, domain vocabulary |
| `agent-os/product/mission.md`, `roadmap.md`, `tech-stack.md` | Product alignment (Step 4) |
| Linked tickets, parent issue, attached images | Visuals (Step 2), related decisions |
| The codebase itself | Reference implementations (Step 3) |
| `agent-os/standards/index.yml` | Which standards apply (Step 5) |

A decision already written in the ticket or the functional spec is **made** —
do not re-litigate it and do not ask about it.

Answer from these sources whenever a defensible answer exists. Steps 2, 3, 4
and 5 are almost always answerable without the user: a ticket with no mockup
simply means "no visuals", and standards selection follows from the paths the
feature touches.

### When a decision is genuinely missing

Only when a **product or behaviour decision** is absent and the readings lead
to materially different specs — not when a detail is merely unstated and you
can pick a sane default, and not for Steps 2–5, which have defaults.

Do not guess, and do not fall back to AskUserQuestion — there is nobody to
answer it.

1. Save what you already have. Write the spec folder with the sections you
   could resolve, and mark each open decision inline as
   `**OPEN DECISION:** …` in `shape.md` so the work is not lost.
2. Post **one** comment on the Linear ticket containing:
   - the questions, numbered, each with the options you see and what each would
     imply for the build
   - your recommendation per question, where you have one
   - what you already shaped, and which tasks are blocked on which question
3. Add the `needs-human` label to the ticket.
4. Stop cleanly. Do not write the implementation tasks around a guess, and do
   not hand off to `/implement-spec`.

Ask everything you need in that single comment — a round trip costs the user a
context switch, so do not drip-feed one question at a time.

### Reporting

Finishing autonomously means: state the spec folder path, the decisions you
took and the source each came from, and anything you defaulted rather than
resolved.

## Process

### Step 1: Clarify What We're Building

Use AskUserQuestion to understand the scope:

```
What are we building? Please describe the feature or change.

(Be as specific as you like — I'll ask follow-up questions if needed)
```

Based on their response, ask 1-2 clarifying questions if the scope is unclear. Examples:
- "Is this a new feature or a change to existing functionality?"
- "What's the expected outcome when this is done?"
- "Are there any constraints or requirements I should know about?"

**Autonomous:** the ticket description is the answer. Read the title,
description and every comment, plus the module's functional spec for the
behaviour being extended. Treat acceptance criteria in the ticket as the
expected outcome. If the ticket does not say what to build at all — as opposed
to leaving a detail open — that is a missing decision: escalate.

### Step 2: Gather Visuals

Use AskUserQuestion:

```
Do you have any visuals to reference?

- Mockups or wireframes
- Screenshots of similar features
- Examples from other apps

(Paste images, share file paths, or say "none")
```

If visuals are provided, note them for inclusion in the spec folder.

**Autonomous:** use the images attached to the ticket, if any. No attachment
means "None" — record that and move on. Never escalate for missing visuals.

### Step 3: Identify Reference Implementations

Use AskUserQuestion:

```
Is there similar code in this codebase I should reference?

Examples:
- "The comments feature is similar to what we're building"
- "Look at how src/features/notifications/ handles real-time updates"
- "No existing references"

(Point me to files, folders, or features to study)
```

If references are provided, read and analyze them to inform the plan.

**Autonomous:** take the references the ticket names, then find the rest
yourself — the sibling module that already solves the same shape of problem,
the nearest existing entity, controller, MCP tool set, view or screen. Searching
the codebase is your job here, not the user's.

### Step 4: Check Product Context

Check if `agent-os/product/` exists and contains files.

If it exists, read key files (like `mission.md`, `roadmap.md`, `tech-stack.md`) and use AskUserQuestion:

```
I found product context in agent-os/product/. Should this feature align with any specific product goals or constraints?

Key points from your product docs:
- [summarize relevant points]

(Confirm alignment or note any adjustments)
```

If no product folder exists, skip this step.

**Autonomous:** read `agent-os/product/` and check the feature against it
yourself — `mission.md`, the relevant `*-roadmap.md` and
`*-functional-spec.md`. Record the alignment in `shape.md`. Escalate only if
the ticket directly contradicts the product docs, which is a real decision the
user must make.

### Step 5: Surface Relevant Standards

Read `agent-os/standards/index.yml` to identify relevant standards based on the feature being built.

`global/testing` is **always** relevant — it holds the Definition of Done that every
ticket must satisfy. Read it now; do not put it up for confirmation.

Use AskUserQuestion to confirm the others:

```
Based on what we're building, these standards may apply:

1. **api/response-format** — API response envelope structure
2. **api/error-handling** — Error codes and exception handling
3. **database/migrations** — Migration patterns

Should I include these in the spec? (yes / adjust: remove 3, add frontend/forms)
```

Read the confirmed standards files to include their content in the plan context.

**Autonomous:** select the standards yourself from `index.yml`, driven by the
components the feature touches (`api/`, `admin/`, `agent/`, `mobile/`, plus
`global/` for real-time and testing rules). Include your selection in
`shape.md` under `Standards appliqués` with one line on why each applies. Never
escalate for standards selection.

When `/inject-standards` is called from here in autonomous mode, it must not
ask how to include them either: use **References** (`@` file paths), which keeps
the plan light and the standards in sync.

### Step 6: Shape the E2E Journey

**Every spec has this section. No spec is shaped without it.** Per the Definition of Done
(`agent-os/standards/global/testing.md`), at least one e2e journey covers the feature.

Derive the journey yourself from the scope, then confirm it with AskUserQuestion:

```
Here's the e2e journey I'll write for this feature:

**Extends:** MAG-101 — Parcours e2e : recettes, menus et courses

- **Given** a logged-in user with the "Dahl de lentilles" recipe
- **When** they plan it for Tuesday dinner
- **Then** its ingredients appear in the grocery list, quantities merged
- **And** the other open tab receives the update via Mercure

(confirm / adjust / this feature has no user-facing behavior)
```

Rules:

- **Extend before creating.** Name the existing journey ticket this belongs to — MAG-99
  chat, MAG-100 agenda, MAG-101 recipes/meals/groceries, MAG-102 finance, MAG-103
  settings/search/notifications. A new journey only when none of them fits.
- Write it as **Given / When / Then**, precise enough to be transcribed into a Playwright
  spec or a Maestro flow without deciding anything again: selectors by role and text, the
  real-time updates to wait for, the DB state to check.
- The harness is not built yet (MAG-94, MAG-95, MAG-97, MAG-98). Until it lands, the
  written journey *is* the deliverable; once it lands, the executable flow is part of the
  ticket.
- **No user-facing behavior** — standards, docs, prompts, infra, refactor with no behavior
  change: record `E2E: N/A — <reason>` instead. One line, in the plan.

**Autonomous:** do not confirm the journey. Derive it from the ticket, the module's
functional spec and its journey ticket, write it into the plan, and record the choice
of journey ticket as a decision comment. `N/A` still needs its one-line reason.

### Step 7: Generate Spec Folder Name

Create a folder name using this format:
```
YYYY-MM-DD-HHMM-{feature-slug}/
```

Where:
- Date/time is current timestamp
- Feature slug is derived from the feature description (lowercase, hyphens, max 40 chars)

Example: `2026-01-15-1430-user-comment-system/`

**Note:** If `agent-os/specs/` doesn't exist, create it when saving the spec folder.

### Step 8: Structure the Plan

Now build the plan with **Task 1 always being "Save spec documentation"**.

Present this structure to the user:

```
Here's the plan structure. Task 1 saves all our shaping work before implementation begins.

---

## Task 1: Save Spec Documentation

Create `agent-os/specs/{folder-name}/` with:

- **plan.md** — This full plan
- **shape.md** — Shaping notes (scope, decisions, context from our conversation)
- **standards.md** — Relevant standards that apply to this work
- **references.md** — Pointers to reference implementations studied
- **visuals/** — Any mockups or screenshots provided

## Task 2: [First implementation task]

[Description based on the feature]

## Task 3: [Next task]

...

## Tests

[The tests each implementation task owes — see the Definition of Done]

## E2E journey

[The Given/When/Then from Step 6, or `N/A — reason`]

---

Does this plan structure look right? I'll fill in the implementation tasks next.
```

**Autonomous:** do not pause for confirmation. Derive the tasks from the ticket
and write them straight out, then continue to Step 9. Order them so each task
is independently testable and committable, since `/implement-spec` commits one
per task.

### Step 9: Complete the Plan

After Task 1 is confirmed, continue building out the remaining implementation tasks based on:
- The feature scope from Step 1
- Patterns from reference implementations (Step 3)
- Constraints from standards (Step 5)

Each task should be specific and actionable, and each task that touches code **names the
tests it owes** — no separate "write the tests" task at the end, tests ship with the code.

### Step 10: Ready for Execution

When the full plan is ready:

```
Plan complete. When you approve and execute:

1. Task 1 will save all spec documentation first
2. Then implementation tasks will proceed

Ready to start? (approve / adjust)
```

**Autonomous:** there is no approval gate. Write the spec folder yourself —
that is Task 1 — then report the folder path, the decisions you took with their
sources, and anything you defaulted. Hand off to `/implement-spec` only if the
ticket asked for implementation too; shaping alone is a complete result.

## Output Structure

The spec folder will contain:

```
agent-os/specs/{YYYY-MM-DD-HHMM-feature-slug}/
├── plan.md           # The full plan, incl. Tests and E2E journey sections
├── shape.md          # Shaping decisions and context
├── standards.md      # Which standards apply and key points
├── references.md     # Pointers to similar code
└── visuals/          # Mockups, screenshots (if any)
```

## plan.md Content

The plan holds the implementation tasks, then **two mandatory sections**. A plan missing
either is not a plan — `/implement-spec` has nothing to verify against.

```markdown
# {Feature Name} — Plan

## Task 1: Save Spec Documentation
...
## Task N: {Implementation task}

{What changes} — tests it owes: {e.g. 401, 400, happy path + DB state, Mercure, ES}

## Tests

Per unit touched, from `agent-os/standards/global/testing.md`:

| Unit | Tests |
|---|---|
| `CreateMealTool` | no user bound, missing `recipeId`, unknown action, happy path asserting DB, Mercure, ES |
| `MealPlanner.tsx` | render, plan a meal, API error |

Bug fix in scope? Name the reproduction test, written red before the fix.

## E2E journey

**Extends:** MAG-101 — Parcours e2e : recettes, menus et courses

- **Given** …
- **When** …
- **Then** …

(or: `N/A — standards only, no user-facing behavior`)

## Definition of Done

- [ ] Unit/integration tests above, green
- [ ] E2E journey written (executable once MAG-97/98 land)
- [ ] Bug fix has its red-first reproduction test
- [ ] CI green on a PR linking the ticket
- [ ] Module functional spec + user guide updated in Linear (ADR-006)
```

## shape.md Content

The shape.md file should capture:

```markdown
# {Feature Name} — Shaping Notes

## Scope

[What we're building, from Step 1]

## Decisions

- [Key decisions made during shaping]
- [Constraints or requirements noted]

## Context

- **Visuals:** [List of visuals provided, or "None"]
- **References:** [Code references studied]
- **Product alignment:** [Notes from product context, or "N/A"]

## Standards Applied

- api/response-format — [why it applies]
- api/error-handling — [why it applies]
```

## standards.md Content

Include the full content of each relevant standard:

```markdown
# Standards for {Feature Name}

The following standards apply to this work.

---

## api/response-format

[Full content of the standard file]

---

## api/error-handling

[Full content of the standard file]
```

## references.md Content

```markdown
# References for {Feature Name}

## Similar Implementations

### {Reference 1 name}

- **Location:** `src/features/comments/`
- **Relevance:** [Why this is relevant]
- **Key patterns:** [What to borrow from this]

### {Reference 2 name}

...
```

## Tips

- **Keep shaping fast** — Don't over-document. Capture enough to start, refine as you build.
- **Visuals are optional** — Not every feature needs mockups.
- **Standards guide, not dictate** — They inform the plan but aren't always mandatory. The
  one exception is the Definition of Done in `global/testing`: it applies to every ticket,
  and every exemption is written in the plan with its reason.
- **Specs are discoverable** — Months later, someone can find this spec and understand what was built and why.
- **Autonomously, escalate late but decisively** — Default the details, shape everything you can, and spend the one Linear comment on the decisions that actually change the build.

## Integration

First half of the agent-os protocol: `/shape-spec` produces the spec folder,
`/implement-spec` executes it task by task. Calls `/inject-standards` during
Step 5.

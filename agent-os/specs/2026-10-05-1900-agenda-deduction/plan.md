# Maggie deduces an event's agenda — Plan

Ticket: [MAG-150](https://linear.app/meven/issue/MAG-150/maggie-deduit-lagenda-dun-evenement-et-demande-en-cas-de-doute)
Shaping notes and decisions: [shape.md](shape.md) · Standards: [standards.md](standards.md) · References: [references.md](references.md)

## Acceptance criteria

1. « Ajoute le concert de Stromae le 12 » lands in « Concerts », and the tool answers with
   the agenda it chose so Maggie can say it.
2. « Ajoute un rendez-vous avec Paul demain 10 h », when the past appointments with Paul
   are in « Boulot », lands in « Boulot » — deduced from the user's own history, with no
   agenda named in the request.
3. A request two agendas fit equally **creates nothing** and comes back asking which one,
   naming the 2 or 3 plausible agendas with the reason each is plausible.
4. Nothing points anywhere → the default agenda. No default agenda → the tool asks, as it
   already did before this ticket (MAG-149).
5. An agenda the user named explicitly still wins over every deduced signal, and still
   resolves in **one** `create_event` call (MAG-230 keeps holding).
6. The deduction only ever reads the caller's own agendas and events; neither a choice nor
   an error message can name another user's agenda.

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`, `standards.md`, `references.md`. No `visuals/` — the
ticket carries no attachment and nothing changes on screen; the whole feature is behind
`create_event`.

## Task 2: `AgendaResolver` — separate "no exact match" from "several exact matches"

`api/modules/calendar/src/Service/AgendaResolver.php`

- `findExact(User, string): ?Agenda` — the id or the folded name, `null` when nothing
  matches, still `\DomainException` when **several** agendas share the name (that is a real
  ambiguity the user has to settle, not something to deduce around).
- `resolve()` keeps its signature and message, implemented on top of `findExact()`, so
  `update_event` is untouched.
- `describe(array $agendas): string` becomes public: the suggester's own messages list the
  agendas the same way, and there must be one sentence for that, not two.

Tests it owes: `AgendaResolverTest` — exact id, folded name, unknown reference returns
`null`, twin names throw, another user's agenda is never returned. The existing
`AgendaByNameToolsTest` stays green unchanged and is the regression net on the messages.

## Task 3: `AgendaSuggester` — the signals, the score, the decision

`api/modules/calendar/src/Service/AgendaSuggester.php` plus the value objects
`AgendaChoice` (the decision) and `AgendaCandidate` (an agenda, its score, its reasons).

Input: the caller, and what `create_event` knows — `summary`, `description`, `location`,
and `spoken` (whatever the model passed as `agenda_id` when it resolved to nothing).

Words are folded (ASCII, lower case), kept from 3 letters up, stripped of French function
words and of the words that name the *kind* of entry rather than its subject (`rendez`,
`rdv`, `reunion`, `point`, `appel`, …), then singularised from 6 letters up so « Concerts »
and « concert » are one word.

**`spoken` does not join the other signals, it replaces them.** Someone saying « dans mon
agenda Théâtre » has told us where the event goes; deducing « Concerts » from the title
instead would contradict them. So when the user named an agenda, only that is scored, and
reaching none of theirs is a question — never the default agenda.

| Signal | Weight |
|---|---|
| `spoken` names the agenda (shared word, or one written inside the other) | 200 |
| `spoken` appears in the agenda's description — only if **no** agenda's name answered | 120 |
| *(when nothing was spoken)* a word of the agenda's **name** is in the request | 100 (+40 if the whole name is) |
| a word of the agenda's **description** is in the request | 30 |
| a past event here has the same summary | 60 × share |
| a past event here shares a word with the request | 40 × share |
| a past event here has the same location | 25 × share |

A name answering settles it, so the descriptions are not scored beside it: 200 against 120
sits inside the dominance band, and « dans mon agenda boulot » with a « Perso » described
as « tout ce qui n'est pas le boulot » would come back as a question. For the same reason,
a `spoken` reference is only ever matched *inside* a name, never inside a description —
« sport » is written in « Déplacements et transports ».

`share` is this agenda's count of matching events over the count across **all** the
agendas, so a habit kept in one agenda scores full marks; and a signal that reaches *every*
one of the user's agendas **and leans on none of them** is dropped outright, because the
share alone still clears the floor when split three ways and the answer would be a
three-way question. The lean matters: with two agendas, « reaches both » is also what « three
appointments at work against one at home » looks like.

History is the caller's events starting before `now + 30 days`, newest first, 500 at most,
cancelled ones excluded — and **plain events only**: `Meal extends Event`, and a meal lives
in the « Repas » agenda the meal planner creates for itself, so one planned lunch would
otherwise make the meal planner a candidate for an appointment.

Decision, on the candidates scoring 10 or more:

- none → the default agenda (`Fallback`); no default → `Ask`;
- exactly one → `Confident`;
- several, the best at least twice the runner-up → `Confident`;
- several otherwise → `Ambiguous`, with the candidates at or above half the best score,
  3 at most.

Tests it owes (`AgendaSuggesterTest`, happy path **and** every branch): name in the
request, whole name bonus, description in the request, spoken reference shortened onto a
name, spoken reference in a description, a name beating a description that merely mentions
the word, a fragment never reaching inside a description, two agendas a spoken name fits, a
spoken reference never outvoted by the event's own words, history by summary, history by
word, history by location, a word spread over several agendas losing to the habit one
holds, a word every agenda carries evenly deciding nothing, a habit that leans still read
when it reaches every agenda, the reasons ordered by what they scored, ambiguity capped at
3, no signal with a default, no signal without one,
unresolvable `spoken`, the plural of a generic word still dropped (long and short),
cancelled events ignored, far-future events ignored, another user's agendas and events
never scored.

Plus `MealIsNotAnAgendaHabitTest` in **cookbook** — the dependency only points that way —
for the planned meal that must not make « Repas » a candidate.

## Task 4: `create_event` chooses, says which, or asks

`api/modules/calendar/src/Mcp/Tool/CreateEventTool.php`

- A user bound and `agenda_id` given: `findExact()` first — one call, unchanged (MAG-230).
  No exact match → the reference becomes the `spoken` signal.
- A user bound and no `agenda_id`: the suggester decides.
- `Confident` / `Fallback` → create, and return `agendaChoice` (`named`, `deduced`,
  `default`) and `agendaReason` next to `event.agenda`, so the sentence Maggie says is
  grounded in what actually happened.
- `Ambiguous` → **create nothing**, return an error naming the candidates, their ids and
  their reason, and asking the user to pick.
- `Ask` → the error already worded for the no-default case (MAG-149), plus the agenda list.
- No user bound: no deduction at all — nothing to deduce from, and the handler's own guard
  answers, exactly as today.
- The tool description tells the model to pass the agenda as the user said it, that the
  agenda is deduced when it does not, and that its answer has to name `event.agenda`.

Tests it owes: `AgendaDeductionToolTest` — the three acceptance criteria, the default
fallback, no user bound, the explicit name still winning, nothing created on `Ambiguous`,
`assertMercureUpdatePublished()` and `assertElasticsearchIndexDispatched()` on the created
paths. `CreateEventToolTest`, `AgendaByNameToolsTest` and `UserIsolationToolsTest` stay
green unchanged.

`api/contract/mcp-tools.json` records the tool descriptions: regenerate it with
`UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` and commit the diff.

## Task 5: the fake-LLM scenarios and the fixtures the journey needs

- `api/fixtures/e2e/20-calendar.yaml` — two agendas and the history that makes the
  deduction have something to read: « Boulot » with two past appointments with Paul, and
  « Concerts »; a « Déjeuner avec Camille » in both « Famille » and « Boulot », which is
  the genuine ambiguity.
- `agent/fixtures/fake-llm/38-create-event-deduced-agenda.yaml` — the concert, no
  `agenda_id`.
- `agent/fixtures/fake-llm/39-create-event-deduced-from-history.yaml` — Paul, no
  `agenda_id`.
- `agent/fixtures/fake-llm/3a-create-event-ambiguous-agenda.yaml` — the lunch with
  Camille: a first turn calling `create_event` with no agenda, then a turn that asks, and
  the follow-up message naming « Boulot » creating it.

## Task 6: the journey

`e2e/web/tests/chat.spec.ts`, extending MAG-99 — three tests, written out in the
[E2E journey](#e2e-journey) section below, at the **end** of the file: it is serial, and
the tests above it count contexts, bubbles and the twenty messages the chat panel reloads.

`E2eSeedCommandTest` gets the same three expectations asserted against the seed directly.
The agenda an event lands in depends on the whole seeded world — every agenda, and every
event inside the thirty-day window, meals included — so any fixture can move it, and the
browser suite is the slowest and least legible place to discover that.

## Task 7: the judgement half

`scripts/prompt-lab/scenarios/16-agenda-deduit.yaml` — on the real model, through
`task e2e:eval`: she files the concert without asking and says where, and on the ambiguous
lunch she asks instead of creating (`forbidden_tools: [create_event]` on that step).

## Tests

| Unit | Tests |
|---|---|
| `AgendaResolver` | exact id, folded name, unknown returns `null`, twin names throw, another user's agenda refused |
| `AgendaSuggester` | every signal, the share rule, the four decisions, the caps, user isolation (see Task 3) |
| `CreateEventTool` | no user bound, happy path asserting DB state + Mercure + Elasticsearch, the three acceptance criteria, nothing created on `Ambiguous`, explicit name still wins |
| `api/contract/mcp-tools.json` | regenerated, the `create_event` description diff visible in the PR |
| `E2eSeedCommandTest` | the deduction against the **real seed**, asserting what the three journeys expect — plus that the two older chat journeys' agenda-less writes still go through. The counts of agendas and events it pins move with the new fixtures |

No bug fix in scope, so no red-first reproduction test.

Admin, mobile and agent code: untouched. The agent gets no new prompt — the instruction
lives in the tool description, which the model already reads (see [shape.md](shape.md),
decision 1) — so there is no agent unit test to add.

## E2E journey

**Extends:** MAG-99 — Parcours e2e : chat et Maggie (streaming, contextes, outils, voix)

Journey A — deduced from the agenda's name:

- **Given** a logged-in user whose agendas are « Perso » (default), « Famille »,
  « Boulot » and « Concerts », and no event named « Concert de Stromae »
- **When** they tell Maggie « Ajoute le concert de Stromae le 12 novembre 2099 à 20 h »
- **Then** `create_event` is called once, with no `manage_agendas` round trip, and comes
  back `success`
- **And** the event is in « Concerts » — `agenda` is `/api/agendas/<e2e_agenda_concerts>`
  once indexed — not in « Perso »

Journey B — deduced from the history:

- **Given** the same user, whose two past appointments with Paul are in « Boulot »
- **When** they tell Maggie « Ajoute un rendez-vous avec Paul le 13 novembre 2099 à 10 h »
- **Then** `create_event` is called once and comes back `success`
- **And** the event is in « Boulot », not in the default « Perso »

Journey C — ambiguous, so she asks:

- **Given** the same user, who lunches with Camille in « Famille » *and* in « Boulot »
- **When** they tell Maggie « Ajoute un déjeuner avec Camille le 14 novembre 2099 à 12 h 30 »
- **Then** `create_event` is called and comes back **`error`**, and no event named
  « Déjeuner avec Camille » exists on `/api/events`
- **When** they answer « dans Boulot »
- **Then** `create_event` is called again, comes back `success`, and the event is in
  « Boulot »

## Definition of Done

- [ ] Unit/integration tests above, green
- [ ] E2E journey written as Playwright specs in `e2e/web/tests/chat.spec.ts`
- [ ] No bug fix in scope — no reproduction test owed
- [ ] CI green on a PR linking MAG-150
- [ ] Calendar functional spec + user guide updated in Linear (ADR-006)

# Shaping notes — Maggie deduces an event's agenda (MAG-150)

Shaped autonomously: the session was started by Cyrus from the Linear ticket, on a
`cyrus/*` branch in a worktree. Every answer below comes from the ticket, the code or a
standard; the one source that does not exist is a calendar functional spec in
`agent-os/product/` — the calendar's spec lives in Linear (ADR-006), and the ticket itself
states the behaviour precisely enough that nothing had to be guessed about *what* to build.

## Scope

When Maggie creates an event she does not choose the agenda: it goes to the default one.
MAG-149 landed the default agenda and MAG-230 let `create_event` take an agenda by the
name the user spoke. What is missing is the step before: working out **which** agenda an
event belongs to when nobody said, and asking rather than guessing when two fit.

In scope: the deduction, the decision, and `create_event` acting on it.

Out of scope, from the ticket: setting the default agenda (MAG-149, landed);
`update_event`, which keeps exact resolution — moving an event is an explicit act, and
deducing a destination for it is not what the ticket asks for. The proactions that create
events keep their dedicated agenda **with nothing to change**: no proaction dispatches
`CreateEventCommand` itself (the only two callers are `CreateEventProcessor` and
`CreateEventTool`), and a proaction that goes through `create_event` names its agenda, so
exact resolution still wins before any deduction runs.

## Decisions

### 1. Where the logic goes — in `create_event`, not in a new tool and not in a prompt

- **Dilemma:** the ticket leaves it open: an MCP tool returning ranked agendas with their
  score, or the instruction in the agent's agenda skill.
- **Options:** (a) a new `suggest_agenda` MCP tool the model calls before `create_event`;
  (b) the rule written into a skill or the personality prompt; (c) the deduction inside
  `create_event`, in a service the API owns.
- **Choice:** (c) — `AgendaSuggester`, called by `create_event`. No new tool, no new
  prompt.
- **Why:** the ticket asks to prefer what is testable deterministically. (c) is covered by
  PHPUnit alone and, more to the point, **happens whether or not the model cooperates**;
  (a) depends on the model calling a second tool and puts back the round trip MAG-230 just
  removed; (b) depends on the model's judgement, which `LLM_PROVIDER=fake` cannot assert at
  all — a journey would be asserting its own fixture (`agent/fixtures/fake-llm/README.md`).

The ticket's e2e sketch says « l'outil de suggestion est appelé, puis l'événement est créé
dans l'agenda attendu ». With (c) there is one call instead of two, and the journey asserts
the thing that actually matters — the event landed in the expected agenda. Recorded as a
deliberate departure from the sketch, not an omission.

### 2. How Maggie asks — the tool's error, the channel MAG-149 already chose

- **Dilemma:** an ambiguous case has to reach the user as a question, before anything is
  created.
- **Options:** a success payload carrying `needsConfirmation` and candidates; a
  `\DomainException` whose message names the candidates and says to ask.
- **Choice:** the error, listing the 2–3 plausible agendas with their id and the reason
  each is plausible.
- **Why:** `create_event` already answers exactly that way when there is no default agenda
  — « ask which agenda to use rather than picking one » (MAG-149) — and `AgendaResolver`
  does the same for an unknown name (MAG-230). A second, softer channel for the same
  situation would be a second thing to keep right. An error also has the property the
  ticket insists on: nothing is created.

### 3. The hint, on `agenda_id` rather than a new argument

- **Dilemma:** « au boulot » is a signal the model holds and the tool does not — the
  request's words never reach `create_event`, only the title, description and location do.
- **Options:** a new `agenda_hint` argument; reuse `agenda_id` as a fuzzy reference.
- **Choice:** reuse `agenda_id`. It resolves exactly when it can (MAG-230, one call), and
  becomes the deduction's only signal when it cannot.
- **Why:** two arguments both meaning "which agenda" is one more thing for a model to get
  wrong, and an unresolvable name is already information — the user *did* say something
  about the agenda.

**Its only signal, not its strongest.** This started as the top row of the weight table
and the first tool test proved it wrong: « Ajoute le concert de Stromae dans mon agenda
Théâtre » scored « Concerts » 100 on the title, « Théâtre » reached nothing, and the event
was filed in « Concerts » — contradicting the one thing the user had actually said about
the agenda. So a spoken reference now replaces the other signals rather than competing with
them: it decides, or it asks. Two deliberate consequences, both tested: a reference that
reaches nothing never falls back to the default, and a reference that fits two agendas
raises the question instead of picking the better-scoring one.

### 4. Generic scheduling words are dropped, by a list and not by frequency

- **Dilemma:** « Rendez-vous avec Paul » shares « rendez » with « Rendez-vous dentiste »
  in another agenda as surely as it shares « Paul » with the appointments in « Boulot » —
  and on raw token overlap the two signals tie, so Maggie would ask where she should know.
- **Options:** weight each token by how rare it is in the user's events (IDF); drop a short
  list of words that name the *kind* of entry rather than its subject.
- **Choice:** the list — `rendez`, `rdv`, `reunion`, `point`, `appel`, `visio`, `truc`,
  `chose`, `agenda`, `calendrier`, `evenement`, plus French function words of three letters
  or more. Read against the word *and* against it without a trailing « s », because the
  list is written in the singular and short words are not de-pluralised for comparison:
  « rdvs » and « trucs » would otherwise slip through while « appels » is caught.
- **Why:** on a corpus of a few dozen events IDF separates nothing (« rendez » in 1 event
  of 20 and « Paul » in 2 of 20 score alike), and it makes the outcome depend on how full
  the agenda happens to be. A short list is legible, testable, and wrong in a way a reader
  can see and fix. Words that are common *within the user's own agendas* are still handled,
  and by a rule rather than a list — that is what `share` is for (decision 5).

### 5. `share` instead of a cap — a word in every agenda decides nothing

- **Dilemma:** the history signal has to tell a habit from sheer volume. A busy agenda
  wins on raw counts; a flat per-agenda cap loses the habit.
- **Options:** cap the history contribution per agenda; divide by the number of agendas the
  token appears in; weight by this agenda's share of the matching events.
- **Choice:** `share` — the matching events in this agenda over the matching events
  everywhere — **and** a signal is dropped when it reaches every one of the user's agendas
  *and leans on none of them*.
- **Why:** `share` tells habit from volume: five lunches with Paul in « Boulot » against
  one in « Perso » gives 33 against 7, which clears the dominance test, where a cap would
  have made the two indistinguishable. But it does not, on its own, make a word that lives
  everywhere decide nothing — the first review round caught that: 40 split three ways is
  13.3 each, over the floor, so « Ajoute un déjeuner mardi » for someone who lunches in all
  three of their agendas would have asked a three-way question where the ticket asks for
  the default agenda.

**Two rounds to get the condition right, and the second one is the lesson.** « Reaches
every agenda » alone was wrong, and the second review round proved it: with exactly two
agendas — the common shape of an account — that is the *same condition* as « three past
appointments with Paul at work against one at home », so the ticket's second acceptance
criterion silently went back to the default agenda. The rule has to say *discriminates
nothing*, not *touches everything*: dropped only when the spread also fails the dominance
test, the same factor confidence is read with everywhere else. Wider spreads need no rule —
nine agendas out of ten gives 4.4 each, under the floor. Both shapes are pinned:
`testAWordCarriedEvenlyByEveryAgendaDecidesNothing` and
`testAHabitThatLeansIsReadEvenWhenItReachesEveryAgenda`.

### 5b. A planned meal is not a filing habit

- **Dilemma:** `Meal extends Event`, so the deduction's query returns the meal plan too.
- **Options:** leave it (a meal is an entry in an agenda like any other); exclude meals.
- **Choice:** exclude them — only plain `Event` rows are read.
- **Why:** `CreateMealHandler` files meals in a « Repas » agenda it creates for itself, and
  a single planned lunch is then enough to score « Repas » exactly what « Paul » scores for
  « Boulot » — so Maggie would ask whether an appointment belongs in the meal planner.
  Those dedicated agendas are outside this ticket by its own wording, and nobody picks them
  one event at a time. Pinned by `MealIsNotAnAgendaHabitTest`, which lives in the cookbook
  module because that is the direction the dependency runs in; it was watched failing with
  exactly that two-way question before the exclusion went in.

### 6. Confidence is relative, not a threshold on the score

- **Dilemma:** when is a deduction good enough to act on?
- **Options:** an absolute score threshold; dominance over the runner-up.
- **Choice:** a candidate floor of 10 to count at all, then: alone → confident; at least
  twice the runner-up → confident; otherwise ambiguous.
- **Why:** an absolute threshold has to be re-tuned every time a weight moves, and it reads
  the wrong quantity — ambiguity is *competition between agendas*, not weakness of a
  signal. One agenda weakly pointed at and no other candidate is not a doubt; two agendas
  strongly pointed at is. The two knobs left (the floor, the factor 2) are both about the
  comparison, not about the scale.

### 7. The history window — before `now + 30 days`, 500 events, cancelled excluded

- **Dilemma:** how much history to read, with no bound on how many events a user has.
- **Choice:** the caller's events starting before `now + 30 days`, newest first, 500 at
  most, `status != cancelled`.
- **Why:** a habit shows in the recent past and the near future; a one-off booked in 2099
  is not a habit and would otherwise be the first row an unbounded `startAt DESC` returns.
  A cancelled occurrence is the opposite of a habit. 500 keeps one query bounded without
  any user of this app reaching it.

### 8. The journey is MAG-99, not MAG-100

- MAG-100 carries the agenda screens. What this ticket changes is only reachable by talking
  to Maggie, and `e2e/web/tests/chat.spec.ts` already holds the two `create_event`
  journeys this extends (MAG-230's named-agenda test is the sibling case). Recorded as a
  decision because the ticket names MAG-99 and the feature is nominally calendar work.

## Visuals

None. The ticket has no attachment, and nothing changes on screen: the whole feature sits
behind `create_event`, and what the user sees is the sentence Maggie says and the agenda
the event shows up in.

## Product alignment

`agent-os/product/mission.md` asks for an agent that is **proactive & adaptive** — it
« learns preferences » — and at the same time « propose, never impose »: the user decides.
This ticket is both halves at once: deduce from the user's own history when the history
answers, ask when it does not. The default agenda silently absorbing everything was the
one behaviour neither half allows.

Nothing in the roadmap or the tech stack is contradicted: no new dependency, no new
surface, one service and one tool description in an existing module.

## Open decisions

None. Every choice above is settled by the ticket, by MAG-149/MAG-230's existing
behaviour, or by the testing standard.

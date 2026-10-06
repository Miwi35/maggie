# Design system commun web et mobile — Shaping Notes

Ticket: [MAG-39](https://linear.app/meven/issue/MAG-39/design-system-commun-web-et-mobile)
Project: Web et tablette · Related: [MAG-90](https://linear.app/meven/issue/MAG-90/regles-ux-transverses-appliquees-a-tous-les-modules)

## Where the app stands

The ticket says the web uses react-admin's `radiant` and the mobile « un Material 3
par défaut avec un seul violet `#9055FD` », with no shared tokens. Reading
`ra-ui-materialui@5.14.0/dist/theme/radiantTheme.js` corrects one half of that:

```js
var lightPalette = { primary: { main: '#9055fd' }, secondary: { main: '#A270FF' },
                     background: { default: '#f0f1f6' },
                     text: { primary: '#544f5a', secondary: '#89868D' }, mode: 'light' }
var darkPalette  = { primary: { main: '#9055fd' }, secondary: { main: '#FF83F6' },
                     background: { default: '#110e1c', paper: '#151221' }, mode: 'dark' }
typography: { fontFamily: 'Gabarito, tahoma, sans-serif', h2: 600, h3: 700, h4: 800, h5: 900, … }
shape: { borderRadius: 6 }, spacing: 10
```

So the violet `#9055FD` **is** radiant's primary, and Gabarito is already the
admin's font — `admin/index.html` loads it for that reason. The identity exists;
what does not exist is a place where it is written down, which is why the same
literals are typed out again and again by hand:

| Written by hand, identically, in | Values |
|---|---|
| `admin/.../dashboard/TaskListWidget.tsx`, `admin/.../calendar/CalendarView.tsx`, `mobile/.../screens/shared/TaskConstants.kt` | task criticality: `#4CAF50` `#FF9800` `#F44336` `#9C27B0` — **three copies, two languages** |
| `admin/.../mind/ContextList.tsx`, `mobile/.../components/ContextListSheet.kt` | context state: green / orange / grey |
| `admin/src/auth/LoginPage.tsx`, `admin/src/auth/LoadingPage.tsx`, `mobile/.../login/LoginScreen.kt`, `.../loading/LoadingScreen.kt`, `.../lock/LockScreen.kt`, `mobile/.../res/values/themes.xml` | the night surface `#1A1A2E` / `#16213E`, and white at 60 % (`rgba(255,255,255,0.6)` = `0x99FFFFFF`) |
| `admin/.../finance/MonthlyFlowsChart.tsx` | the validated chart palette, slots 1 and 2 |
| `mobile/.../grocery/GroceryListsScreen.kt`, `.../proactions/ProactionScreen.kt`, `admin/.../layout/AppBar.tsx`, `admin/.../chat/ChatWidget.tsx` | the same Material hues again, one shade off here and there (`#FFA000` against `#FF9800`, `#FF5252` against `#F44336`) |

And one provable defect: radiant asks Gabarito for **weight 800 (h4) and 900
(h5)**, `index.html` loads `wght@400;500;600;700`. The browser synthesises the
two it does not have, so the nine `variant="h5"` headings in the admin are drawn
in faux-bold.

The mobile side has no typography at all (`MaterialTheme` default, Roboto), no
shapes, and a colour scheme that sets six roles out of M3's twenty-nine.

## Scope

The **tokens** and the **two themes derived from them**, plus the literals above
replaced by the tokens on both platforms. Nothing is redrawn: the tokens are the
values already on screen, named.

What it does **not** carry, and why:

| Out of scope | Why, and what carries it |
|---|---|
| Module-by-module screen conformance | MAG-90 plans exactly that (« Audit module par module (admin et mobile) … puis un ticket de mise en conformité par module ») and needs this ticket's tokens to do it. Reviewing nine modules here would also be far past the guard's 800 lines |
| FullCalendar's own chrome — `#1A73E8` today, `#EA4335` now-indicator (`calendarTheme.ts`) | they are Google Calendar's markers, recognisable *because* they are Google's; not an identity choice |
| The Google sign-in button's white / `#F5F5F5` / black-54 % (`LoginPage.tsx`) | Google's brand guidelines fix them |
| The Ciqual auto-fill link's `#1976D2` (`CiqualFoodAutocomplete.tsx`) | a link colour no token covers, and the only one of its kind: it belongs to MAG-90's « liens et actions secondaires » rule, not here |
| **Gabarito on Android** — `res/font/gabarito.ttf`, its OFL licence, `Typography.kt`, `MaterialTheme(typography = …)` | a follow-up `Feature`, `blockedBy` this one. It is what pushed the branch over the guard's 800 lines, the licence text alone counting 93 of them, and it is the one half of the work with a binary asset and a licence to review. The Compose theme here is the colours and the shapes; the phone keeps Roboto at Material's sizes — which *are* the token sizes, so the typeface lands without re-flowing a screen. See the decision below |
| `admin/src/auth/LoginPage.tsx` and `LoadingPage.tsx`, which still write `#1a1a2e` by hand | the guard hands **every** change under `admin/src/auth/` to a human (`permissions`), and a background colour is not worth taking the PR off the merge train. Written down in the standard; it travels with the next ticket that has business being in that directory |
| Self-hosting Gabarito | the admin pulls it from Google Fonts. Worth fixing (the e2e stack aborts every off-origin request, so journeys run in Tahoma) but it is a loading concern, not a token |
| Changing MUI's `spacing` unit from radiant's 10 px | see the decision below |

## Decisions

Taken alone, per the autonomy rule in `CLAUDE.md`.

### The tokens are radiant's values, named — not a new identity

**Dilemma:** define a visual identity, or declare the one already on screen?
**Options:** design a fresh palette and typography / take radiant's values as the
tokens and bring the mobile to them / keep both and converge later.
**Choice:** radiant's values become the tokens.
**Why:** the ticket's « un seul violet `#9055FD` » on mobile *is* radiant's
`primary.main`, and Gabarito is already the admin's font — the two platforms
agree by accident on the two things that matter most, and on nothing else. The
web identity is what the owner has been using every day and what twenty-five
Playwright journeys browse; redrawing it is a change the ticket never asks for,
and one no test could say was an improvement. Declaring it costs nothing on the
web and gives the mobile an identity in one commit.

### One JSON source, two typed mirrors, two contract tests

**Dilemma:** how do the web and the mobile read the same tokens?
**Options:** a Gradle task and a Vite plugin generating code from
`design/tokens.json` at build time / `design/tokens.json` as the source with a
hand-written mirror per platform and a contract test on each side / write them
twice and trust a reviewer.
**Choice:** the mirrors and the contract tests.
**Why:** it is the pattern this repository already runs on. `api/contract/` holds
generated files that `admin/src/hooks/useMercure.contract.test.ts` and
`mobile/.../DtoContractTest.kt` *walk up the tree to find*, so a server-side
rename fails in the same pull request. The tokens are the same problem — two
clients, one shape — and the same shape of answer works: a drift is a red CI, not
a screenshot nobody compares. Codegen would make both builds depend on a file
outside their own directory (Vite's `server.fs.allow`, a Gradle source-generating
task in the Compose preview path) for no gain the test does not already give.

### Spacing: a named scale beside `theme.spacing`, not a change of the MUI unit

**Dilemma:** radiant sets `spacing: 10`, the mobile is on Material's 8 dp grid.
**Options:** set the admin's unit to 8 so `spacing(1)` means the same on both /
leave the unit alone and share an absolute named scale.
**Choice:** leave it; `space.xs…xxl` = 4 / 8 / 12 / 16 / 24 / 32, absolute px and
dp, read by new code on both sides.
**Why:** `theme.spacing(n)` is called hundreds of times across the admin. Moving
the unit from 10 to 8 re-spaces every screen at once — a 20 % change to every
margin, padding and gap in the app — and no test in the repository could say
whether the result is better. The ticket asks for the spacings to be *defined*,
and an absolute scale both platforms read defines them without touching a single
existing layout.

### Two colour families: MUI's feedback roles, and the data signals

**Dilemma:** radiant's alert palette is acid — error `#DB488B`, warning
`#F2E963`, info `#3ED0EB`, success `#0FBF9F` — while the data the app draws uses
the Material hues (`#4CAF50`, `#FF9800`, `#F44336`), written three times over.
**Options:** one scale, the acid one, everywhere / one scale, the Material one,
everywhere / two families with distinct jobs.
**Choice:** two, both declared. `feedback` holds radiant's acid palette, which
MUI's `palette.error/warning/info/success` and Compose's `error` role read;
`signal` holds the Material hues, which the data reads.
**Why:** they do different work. The acid roles are wired into MUI's components
(`Alert`, `Badge`, a field in error) and are feedback about *this interaction*;
the signal hues label *a value in the data* — a task's criticality, a thread's
state, an item just ticked off — and have to read as a chip and as a 10 px dot in
both modes. And the acid warning `#F2E963` is a surface colour: a yellow chip
with white text on it is unreadable, which is why nothing in the app uses it that
way. Criticality and context state are then *mappings* onto the signal family,
declared once in the token file rather than four times in two languages.

Declaring `feedback` rather than leaving it to MUI costs nothing on the web — it
is what radiant already sets — and buys two things: the mobile's `error` stops
being Material's baseline red, and a react-admin upgrade that moves the palette
fails a test instead of a screen.

### Gabarito everywhere, capped at weight 700

**Dilemma:** the ticket asks « typographie (Gabarito ?) », and Gabarito is a
Google Fonts *display* family whose weight axis starts at 400.
**Options:** Gabarito for headings and the system stack for body / Gabarito
everywhere / something else entirely.
**Choice:** Gabarito everywhere, weights 400 / 500 / 600 / 700.
**Why:** the question is already answered by what is on screen — radiant draws
every `body2` in the admin in Gabarito and has done since the admin existed, so
pairing it with a second family now would change the body text of every web
screen to answer a question the web already settled. The cap is the defect found
above: `index.html` loads `wght@400;500;600;700`, radiant asks h4 for 800 and h5
for 900, and the browser fakes both. Both become 700 — radiant's scale gets
*bolder* as a heading gets smaller (h2 600, h3 700, h4 800, h5 900), so 700, the
loaded maximum, is the closest thing to what it asked for.

### The split: the colours and the shapes here, the typeface next

**Dilemma:** the branch counted 910 lines outside tests against the guard's 800,
and the guard's own message is « split the ticket ».
**Options:** ship it and let the owner merge 910 lines by hand (`needs-human`) /
cut the comments until it fits / split along the two platforms — the admin now,
the phone later / split along the two *media* — the colours and the shapes now,
the typeface later.
**Choice:** the last.
**Why:** the platform split delivers nothing to the phone, which is half the
ticket's title; trimming comments lands at 805 with worse code. The typeface is
the only part with a binary asset (158 KB) and a licence (93 of the 910 lines are
the OFL text), it is the part a reviewer reads differently, and it is the only
part that can be taken out without leaving either platform half-themed: the token
sizes *are* Material 3's defaults for the nine roles the phone draws, so Gabarito
lands later without re-flowing a screen. Everything else — one source, two
mirrors, two contract tests, both colour families, both themes — ships here,
which is what makes the follow-up a hundred lines rather than a second project.

The follow-up also settles how the font gets there: bundle the variable
`Gabarito[wght].ttf` from `google/fonts` in `res/font/` rather than
`androidx.compose.ui.text.google-fonts`, whose provider needs Play services, a
certificate resource and a first-launch fallback, and fails on a phone with no
network — for a font. `Font(resId, weight)` derives the `wght` axis itself and is
stable, where the explicit `FontVariation.Settings` overload is
`@ExperimentalTextApi`.

### The chart palette keeps its two validated slots, and gains none

**Dilemma:** « palette de graphiques (déjà validée pour la finance) » — how much
of a palette?
**Options:** extend to a full six-slot CVD-safe scale (Okabe-Ito) / carry over
exactly what was validated.
**Choice:** the two slots, light and dark steps, as `MonthlyFlowsChart` has them.
**Why:** slots 1 and 2 are the ones the owner validated on the finance dashboard
(`finance-roadmap.md`, « palette catégorielle validée CVD »). Slots 3 to 6 would
be colours nobody has looked at, used by nothing — dead tokens that the next
chart would have to re-validate anyway. The token file says where they go when a
chart needs them.

### The visual changes taken deliberately

Everything else is the same pixels under a name. These are not:

- **h4/h5 weights** (above) — 800 and 900 become 700, and faux-bold becomes real.
- **The phone's surfaces and its dark accent.** Its scheme set six roles out of
  Material's twenty-nine, so the rest was Material's baseline: a near-white and a
  near-black no admin screen has ever used, and a dark `primary` of pale lavender
  `#E8DEFF` with dark text on it. The surfaces are radiant's now and the accent is
  the brand violet in both modes, as on the web. `error` is the admin's pink
  rather than Material's red.
- **Maggie's activity dot while she thinks**, `#9C27B0` → the brand violet
  (`ActivityPulse.tsx`). The purple was the criticality-critical hue standing in
  for « Maggie is busy »; now that the two are named, the dot that says *Maggie*
  takes Maggie's colour. A 10 px dot in the Mind panel.
- One shade is unified where the same intent was written twice: mobile's
  `#FFA000` (grocery « annuler ») → `signal.warning` `#FF9800`, and the admin's
  `#FF5252` (recording) → `signal.danger` `#F44336`.

## Context

- **Visuals:** none attached. The identity is read out of `radiantTheme.js`, the
  existing screens and `admin/public/logo.svg` (a white glyph, no colour of its own).
- **References:** `api/contract/README.md` and the two contract tests named above
  (the source-and-mirror pattern); `agent-os/specs/2026-10-06-adaptive-mobile-layout`
  (the last ticket to touch both themes); `admin/src/theme.ts` and its
  `theme.test.ts` (what a merge onto radiant may and may not do).
- **Product alignment:** `mission.md` asks for « a single personal AI companion »
  across meals, money, agenda and fitness — one assistant, not four apps, which is
  the whole argument for one set of tokens. MAG-90 depends on this ticket by name.

## Standards Applied

- `global/testing` — the Definition of Done: every unit touched owes its tests, and
  a `Feature` owes an e2e journey.
- `admin/testing` — Vitest, co-located `*.test.ts`, and the contract-test shape of
  `useMercure.contract.test.ts`.
- `mobile/testing` and `mobile/screen-tests` — JUnit on the JVM for a decision, a
  Robolectric screen test only for what needs the framework. A theme is a value:
  plain JUnit.
- `global/worktree-checks` — `task fix:all`, `task wt:test:admin`; the mobile suite
  through Gradle with Java 21.
- `global/e2e-environment` — the journey runs inside the e2e stack, where every
  off-origin request is aborted (so the *declared* font stack is what a journey can
  read, never the rendered glyphs).

## Risks

- **`createTheme(radiant…, options)` deep-merges, and MUI's deepmerge replaces a
  key whenever the incoming value is not a plain object** — `theme.ts`'s own
  header says so, and `theme.test.ts` holds the one slot it bites on
  (`RaLayout.styleOverrides.root`). The palette and typography overrides here are
  plain objects on keys radiant sets as plain objects, so they merge; the two
  existing tests stay as the net.
- **`task wt:test:admin` mounts `admin/` as `/app`**, so the contract test only
  finds `design/tokens.json` if the task mounts it too — the same line
  `api/contract` already has. Without it the test does not skip, it fails: that is
  deliberate.
- **The phone's look changes more than the web's**, because it had less to start
  with. `ThemeTest` asserts every role against its token and the Robolectric
  screen tests render the real theme, but what a surface *looks* like is the
  recette's job.

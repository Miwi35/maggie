# The design system

One identity, two platforms (MAG-39). The values live in **`design/tokens.json`**
and nowhere else; each platform carries a typed mirror, and a contract test fails
when a mirror drifts.

| | |
|---|---|
| Source | `design/tokens.json` |
| Web mirror | `admin/src/design/tokens.ts` → `admin/src/theme.ts` (MUI, merged onto radiant) |
| Mobile mirror | `mobile/app/src/main/java/com/maggie/app/ui/theme/Tokens.kt` → `Theme.kt` (Material 3) |
| Journey | `e2e/web/tests/design-system.spec.ts` — reads the source, through the `design/` mount |
| Nets | `admin/src/design/tokens.contract.test.ts`, `mobile/.../ui/theme/TokensContractTest.kt` |

**Adding or changing a token** means editing those three files in the same
commit. The contract tests walk up the tree to find `design/tokens.json`, as the
`api/contract` tests do, and **fail** rather than skip when they cannot — a
missing mount is a broken test, not a silent pass. `task wt:test:admin`,
`docker-compose.e2e.yml` and the Gradle unit tests all see `design/`.

> ⚠ **Until `design/**` is in `ci.yml`'s path filters, nothing enforces that.** A
> commit touching `design/tokens.json` and one mirror runs only that mirror's
> job; the other platform's contract test is *skipped*, and the drift merges
> green. Edit the three files together, by hand. **MAG-286** closes it; until
> it merges, this note is the only thing standing between you and a silent
> drift. (The mechanism in full:
> `agent-os/specs/2026-10-06-2240-design-system-tokens/shape.md`, Risks.)

## Two colour families, two jobs

- **`feedback`** — react-admin radiant's alert palette, wired into MUI's
  `palette.error/warning/info/success` and into Compose's `error` role. It is
  about *this interaction*: a field in error, an `Alert`, a snackbar. The web
  already draws it; the token file pins it so a react-admin upgrade that changes
  it fails a test instead of a screen — `admin/src/theme.test.ts` compares the
  tokens against `radiantLightTheme` / `radiantDarkTheme` themselves, not just
  against the theme they were merged onto.
- **`signal`** — the Material hues the app labels *data* with: a task's
  criticality, a thread's state, an item just ticked off. They have to read as a
  chip, as a swipe background and as a 10 px dot, in both modes.

`criticality`, `contextState` and `source.done` hold the **name** of a signal
entry, not a hex: the mapping is the design intent, and each mirror resolves it
(`criticalityColor()`, `contextStateColor()`).

## What is deliberately not a token

- **Line heights.** MUI and Material 3 each ship their own per-role values,
  within a few percent of one another. Overriding them would re-flow every
  screen on both platforms for nothing anyone would notice.
- **The MUI spacing unit.** Radiant's is 10 px and `theme.spacing(n)` is called
  all over the admin. New code uses `space.*` (absolute px and dp, the 4-px grid
  the mobile is already on); existing layouts keep their unit.
- **Google's own colours**: the sign-in button (`LoginPage.tsx`) and
  FullCalendar's today / now markers (`calendarTheme.ts`, `#1A73E8`, `#EA4335`)
  are recognisable *because* they are Google's.
- **The Ciqual auto-fill link's `#1976D2`** (`CiqualFoodAutocomplete.tsx`) — a
  link colour, the only one of its kind, out of standard since `ux.md` §4: a
  link is a text button in the theme's primary colour.
- **Plain black, white and greys** (`#fff`, `#888`) — contrast, not identity.
  `readableTextOn` / `getEventTextColor` pick between the first two.
- **`admin/src/auth/`** — the sign-in and loading screens are drawn on the night
  surface and still say so in hex. Every change under that path is handed to a
  human by the guard (`agent-guard-rails.md`, `permissions`), so a background
  colour travels with the next ticket that has a reason to be there.
- **The colour a picker starts on** — a new agenda's `#1976D2`
  (`CalendarView.tsx`), the `#4CAF50` in a field's help text. The owner replaces
  it on the next click.
- **The Material 3 roles the phone's scheme still leaves on Material's
  baseline** — `surfaceContainer`, `surfaceContainerLow/High/Highest`,
  `surfaceBright`, `surfaceDim`, `outlineVariant`, the `tertiary*` family and
  `errorContainer`. `Theme.kt` sets seventeen; live screens read some of the
  rest (`GroceryListsScreen` takes a dragged row from `surfaceContainerHighest`;
  `BudgetScreen`, `FinanceDashboardScreen`, `CushionScreen` and
  `VoiceControlBar` use `tertiary` / `errorContainer` as status colours, which is
  a `signal` wearing a Material role). Naming them means deciding each screen's
  intent, which is MAG-90's module-by-module audit, not a guess.

Anything else written as a hex in `admin/src` or `mobile/app/src/main` is a token
that was not declared yet.

## Typography

Gabarito, 400 / 500 / 600 / 700, loaded from Google Fonts in
`admin/index.html`. **Never ask for a weight that link does not load** — the
browser synthesises it, which is what made the admin's `h5` faux-bold until
MAG-39. `design/tokens.contract.test.ts` reads the link element and checks the
token weights against it.

The sizes map onto the eight MUI variants the admin uses; they are also
Material 3's defaults for the nine roles the phone draws, which is what will
make the Compose typography a no-reflow change. **Line heights are not tokens**
(above), and **the phone still writes in Roboto**: Gabarito has to be bundled in
`res/font/` with its licence, and that is a ticket of its own.

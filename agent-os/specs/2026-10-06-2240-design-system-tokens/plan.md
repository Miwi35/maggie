# Design system commun web et mobile — Plan

Ticket: [MAG-39](https://linear.app/meven/issue/MAG-39/design-system-commun-web-et-mobile)
Project: Web et tablette
Shaping notes, decisions and what is out of scope: [shape.md](shape.md)

## The tokens, and where each value comes from

Nothing here is invented. Every value is either radiant's (and so already on
every web screen), or a literal the two platforms already write by hand.

| Group | Values | Taken from |
|---|---|---|
| `brand` | primary `#9055FD`, onPrimary `#FFFFFF`, secondary light `#A270FF` / dark `#FF83F6`, container light `#E8DEFF` / dark `#6200EE` and their `on` pair | `radiantTheme.js`, and the mobile's `MaggiePurple` / `MaggiePurpleLight` / `MaggiePurpleDark` — the containers are Material 3 roles MUI has none of, so the admin declares them and does not read them |
| `surface.light` | background `#F0F1F6`, paper `#FFFFFF`, text `#544F5A`, textMuted `#89868D` | radiant light |
| `surface.dark` | background `#110E1C`, paper `#151221`, text `#FFFFFF`, textMuted `#B8B7BB` | radiant dark; the muted text is MUI's own dark `text.secondary` — 70 % white — flattened to an opaque hex, because Compose takes a colour and not a CSS alpha |
| `night` | background `#1A1A2E`, raised `#16213E`, text `#FFFFFF`, textMutedAlpha `0.6` | login / loading / lock on both platforms, and the Android splash |
| `feedback` | error `#DB488B`, warning `#F2E963`, info `#3ED0EB`, success `#0FBF9F` | radiant's alert palette — MUI's `palette.error/warning/info/success` today |
| `signal` | success `#4CAF50`, warning `#FF9800`, danger `#F44336`, info `#2196F3`, critical `#9C27B0`, neutral `#9E9E9E` | the Material hues the two platforms write out three times over |
| `criticality` | low→success, medium→warning, high→danger, critical→critical | `TaskListWidget.tsx`, `CalendarView.tsx`, `TaskConstants.kt` |
| `contextState` | active→success, dormant→warning, closed→neutral | `ContextList.tsx`, `ContextListSheet.kt` |
| `source` | meals `#FF6B35`, tasks `#1976D2`, done→neutral | `CalendarView.tsx` |
| `chart.categorical` | slot 1 `#2A78D6`/`#3987E5`, slot 2 `#EB6834`/`#D95926` (light/dark step) | `MonthlyFlowsChart.tsx`, « palette catégorielle validée CVD » |
| `typography` | family `Gabarito, tahoma, sans-serif`; weights 400/500/600/700; sizes 11/12/14/16/20/24/28 | radiant's family; the sizes are MUI's defaults for the eight variants the admin uses, which are also Material 3's for the nine roles the mobile uses — which is why nothing re-flows. **Line heights are not tokens**: each framework ships its own, within a few percent, and overriding them would re-flow every screen for nothing |
| `radius` | 4 / 6 / 12 / 16 / 28 | radiant's `shape.borderRadius: 6` as `sm`, M3's shape scale around it |
| `space` | 4 / 8 / 12 / 16 / 24 / 32 | the 4-px grid both platforms already round to |

`feedback` and `signal` are two families with two jobs — see the decision in
`shape.md`. `criticality`, `contextState` and `source.done` are **names of
signal entries**, not hexes: the mapping is the design intent, and each platform
resolves it through the signal family.

## Acceptance criteria

1. `design/tokens.json` is the only place a token value is written. Both
   platforms carry a typed mirror, and a contract test on each side fails if a
   mirror drifts from the file — the test does not skip when it cannot find the
   file, it fails.
2. The admin's light and dark themes read their palette, typography, radii and
   spacing scale from the mirror. The admin looks the same as before, with two
   deliberate exceptions: `h4` and `h5` are weight 700 instead of 800 and 900,
   which `index.html` never loaded, and Maggie's « thinking » dot is the brand
   violet.
3. The Compose theme reads the same tokens: seventeen roles of the M3 colour
   scheme instead of six, and `Shapes` over the shared radii. The typeface is the
   follow-up ticket's (see `shape.md`); the phone keeps Roboto at Material's
   sizes, which are the token sizes.
4. No criticality, context-state, night-surface, chart or signal hex is left
   written by hand in `admin/src` or `mobile/app/src/main` — the exceptions of
   `shape.md` excepted, and every one of them named in the standard.
5. The Android splash reads its background from the same token as the screens
   that follow it.
6. `agent-os/standards/global/design-system.md` says where the tokens live, how
   to add one, and what is deliberately not a token. It is in `index.yml`.

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`.

## Task 2: The token file and the standard

- `design/tokens.json` — the table above, one JSON object.
- `agent-os/standards/global/design-system.md` — short: the three files to edit
  when adding a token, the two colour families and what each is for, the
  literals that stay literals and why.
- `agent-os/standards/index.yml` — one line under `global`.

Tests it owes: none of its own; the two contract tests below are its net.

## Task 3: The admin — the mirror, the theme, the screens

- `admin/src/design/tokens.ts` — the mirror: `TOKENS`, typed, plus
  `criticalityColor()`, `contextStateColor()`, `SOURCE_COLORS`, `chartColor()`
  and `NIGHT_TEXT_MUTED`, resolving the mappings through `TOKENS.signal`.
- `admin/src/theme.ts` — `paletteFor(mode)`, `TYPOGRAPHY` and `shape` added to
  the options merged onto radiant. The `components` block of MAG-38 is untouched.
- The screens, losing their literals: `auth/LoginPage.tsx`, `auth/LoadingPage.tsx`
  (night), `modules/dashboard/TaskListWidget.tsx` and
  `modules/calendar/CalendarView.tsx` (criticality, source),
  `components/mind/ContextList.tsx` and `ActivityPulse.tsx` (context state, brand),
  `components/layout/AppBar.tsx` (danger, brand),
  `components/chat/ChatWidget.tsx` (the search-hit ring → signal warning),
  `modules/finance/MonthlyFlowsChart.tsx` (chart slots).
- `wt/Taskfile.yml` and `docker-compose.e2e.yml` — mount `design/` read-only
  where `api/contract` already is, so the contract test and the journey find it.

Tests it owes:

| Unit | Tests |
|---|---|
| `admin/src/design/tokens.contract.test.ts` | every token in the mirror equals `design/tokens.json`; the file is found or the test fails; the three mappings resolve to the signal entry they name |
| `admin/src/theme.test.ts` (extended) | both modes: primary is the brand token, the surfaces and text are the mode's tokens, MUI's alert roles are the feedback tokens, `shape.borderRadius` is `radius.sm`, the font family is the token's, `h4`/`h5` weights are 700 and **no variant asks for a weight `index.html` does not load**; the two MAG-38 assertions stay |
| `TaskListWidget` | its existing tests stay green; one asserts a chip carries the criticality token rather than a literal |

## Task 4: The mobile — the mirror, the theme, the screens

- `ui/theme/Tokens.kt` — the mirror: `MaggieTokens` with `Space` and `Radius`,
  plus `criticalityColor()`, `contextStateColor()` and `chartColor()`.
- `ui/theme/Theme.kt` — light and dark `ColorScheme` built from the tokens,
  `MaggieShapes`, `MaterialTheme(colorScheme, shapes)`.
- `res/values/colors.xml` (new) + `res/values/themes.xml` — the splash reads
  `@color/maggie_night`.
- The screens, losing their literals: `login/LoginScreen.kt`,
  `loading/LoadingScreen.kt`, `lock/LockScreen.kt` (night, feedback error),
  `screens/shared/TaskConstants.kt` (criticality),
  `components/ContextListSheet.kt` (context state),
  `screens/cookbook/grocery/GroceryListsScreen.kt` and
  `screens/proactions/ProactionScreen.kt` (signal).

Tests it owes:

| Unit | Tests |
|---|---|
| `ui/theme/TokensContractTest.kt` | the mirror and `design/tokens.json` compared **as whole maps**, which is what catches a token added to the source and forgotten here (walking up as `DtoContractTest` does, failing if it cannot find it); `colors.xml`'s `maggie_night` equals `night.background`; an unknown criticality or context state still draws a colour; the shared stack names Gabarito |
| `ui/theme/ThemeTest.kt` | the light and dark schemes take primary, surfaces, onSurface, outline and error from the tokens; no role draws its content in its own colour; the shapes have the token radii; the night surface is neither mode's background |
| `ui/theme/ContrastTextTest.kt` | stays green (`readableTextOn` is untouched) |

## Tests

Everything above, plus: no component test is rewritten. The admin suite and the
mobile suite both already render these screens; a literal replaced by the same
value changes nothing they assert, which is the point of taking radiant's values
as the tokens.

Commands, from this worktree:

```sh
task fix:all
task wt:test:admin -- src/design src/theme.test.ts src/modules/dashboard
cd mobile && ANDROID_HOME=~/Android/Sdk ./gradlew testProdReleaseUnitTest --tests 'com.maggie.app.ui.theme.*'
task e2e:web:lint && task e2e:web:typecheck
```

## E2E journey

**A new journey**, `e2e/web/tests/design-system.spec.ts`, carried by MAG-39
itself — as `responsive.spec.ts` is carried by MAG-38. None of MAG-99…103 fits:
the identity is not a module, it is every screen at once, and the two surfaces
worth asserting (the sign-in screen and the shell's own typography) belong to no
feature.

The journey reads `design/tokens.json` itself, mounted read-only into the
Playwright container, so the values are never written a fourth time.

> **Given** the owner is signed in
> **When** he opens the dashboard
> **Then** the page is drawn on `surface.light.background` in
> `surface.light.text`, the « Tableau de bord » heading is in Gabarito at weight
> **700** — not the 900 the browser used to synthesise — and every font weight
> the page asks for is one `index.html` loads.

The sign-in screen's night surface is **not** asserted here: its two hexes stay
written by hand (`shape.md`, out of scope), so there would be nothing shared to
read. It joins the journey with the ticket that touches `admin/src/auth/`.

What the journey cannot assert, and why it is not a gap: **that the glyphs are
Gabarito**. The e2e stack aborts every off-origin request, Google Fonts included
(`e2e/web/README.md`), so a journey reads the *declared* stack and the renderer
falls back to Tahoma. The owner sees the real font in the recette, in production.

Dark mode is asserted in `theme.test.ts` and `ThemeTest.kt`, both modes side by
side, rather than in the journey: the admin follows the OS preference, and a
journey that flips `prefers-color-scheme` would be testing react-admin's theme
switch, not the tokens.

Mobile: no Maestro flow. A flow can tap and read text; it cannot read a colour
or a font, and a screenshot comparison of a Compose screen is a test of the
emulator's font rendering. `ThemeTest.kt` covers the values on the JVM.

## Definition of Done

- [ ] The contract tests, the theme tests and the extended admin tests, green
- [ ] `design-system.spec.ts` written (it runs in CI with the rest of `task e2e:web`)
- [ ] Not a bug fix — but the faux-bold h4/h5 is fixed and asserted
- [ ] CI green on a PR linking MAG-39
- [ ] The follow-up `Feature` for Gabarito on Android created, `blockedBy` MAG-39
- [ ] Linear: the design system added to the documentation index (the standard is
      the engineering half; the user guide gains nothing — no behaviour changes)

See `agent-os/standards/global/testing.md`.

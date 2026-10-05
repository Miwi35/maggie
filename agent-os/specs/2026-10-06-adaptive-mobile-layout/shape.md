# Mise en page adaptative (tablette, pliable) — Shaping Notes

Ticket: [MAG-35](https://linear.app/meven/issue/MAG-35/mise-en-page-adaptative-tablette-pliable)
Project: Android natif

## Where the app stands

`grep -r WindowSizeClass mobile/app/src` returns nothing. The app has exactly one
layout: a `ModalNavigationDrawer` behind a burger, a `TopAppBar`, a collapsed
« Demander à Maggie… » bar at the bottom and a chat that only ever exists as a
`ModalBottomSheet` or a full-screen `Dialog`. It is drawn the same way on the
280 dp cover screen of a Flip and on a 1280 dp tablet in landscape — on the
tablet the left half is a burger nobody needs, and the conversation covers the
screen it was asked about.

There is also no `@Preview` anywhere in `mobile/app/src/main` (`grep Preview`
finds only `kotlinx.coroutines.FlowPreview`), so « vérification par les previews
Compose multi-tailles » starts from zero too.

## Scope

What lands here is the **shell**: one window model, a `NavigationRail` from the
medium width up, the chat as a permanent side panel when the window is wide
enough for it, and the six formats of the ticket verified on the JVM and visible
in Android Studio.

What it does **not** carry, and why:

| Out of scope | Why, and what carries it |
|---|---|
| « vue liste et détail (agenda, recettes, courses, finance) » | a follow-up `Feature`, `blockedBy` this one. Each of the four modules needs its own detail turned into pane content — `EventDetailSheet`, `TaskDetailSheet` and `ItemDetailSheet` are `ModalBottomSheet`s, and the recipe detail is a route with its own back arrow — plus the « unfold while a detail route fills the screen » correction, its screen tests and its journey. All four in this branch put the guard at 1 731 counted lines against a limit of 800 (`task guard:check`), which is the guard saying *split the ticket*, and it would have been four detail-screen refactors in one review |
| A tablet emulator profile in the Maestro job | it is a change to `.github/workflows/ci.yml`, which the guard hands to a human (`agent-guard-rails.md`, `infra-path`). Follow-up `Task` ticket |

The split is along the ticket's own seam: the first bullet's « NavigationRail »
and the second bullet (« chat en panneau latéral permanent ») are the frame, and
« vue liste et détail » is what goes inside it. The frame is what the other three
need, and it is acceptable on its own — the owner opens the app on a tablet and
sees a rail and a conversation beside his screen.

## Decisions

Taken alone, per the autonomy rule in `CLAUDE.md`.

### Our own window model, not `material3-window-size-class`

**Dilemma:** how does the app learn how wide its window is?
**Options:** add `androidx.compose.material3:material3-window-size-class` and call
`calculateWindowSizeClass(activity)` / add `material3-adaptive` and its
`NavigableListDetailPaneScaffold` / a pure function over `LocalConfiguration`.
**Choice:** a pure function, `appLayoutFor(widthDp, heightDp)`, in
`ui/layout/WindowLayout.kt`.
**Why:** the ticket asks for the six formats to be *verified*, and the whole
decision has to be readable by a test that names a format and asserts what the
app does with it. `calculateWindowSizeClass` needs an `Activity` and is
experimental; `material3-adaptive` brings a scaffold with its own navigation back
stack next to the app's `NavHost`. A function of two integers is tested in plain
JUnit — no Robolectric, no emulator — which is exactly the verification the ticket
asks for, and `LocalConfiguration` is what Robolectric's `qualifiers` and
`@Preview`'s `widthDp` both write, so the same breakpoints are exercised three
times through one rule.

### The rail replaces the drawer; there is no permanent drawer

**Dilemma:** Material 3 says medium → rail, expanded → permanent drawer.
**Options:** follow Material to the letter / rail at both medium and expanded.
**Choice:** rail at both.
**Why:** the ticket says « NavigationRail en largeur moyenne ou grande ». A
permanent drawer is 360 dp of labels, and on a 1280 dp tablet those 360 dp are
what the chat panel and the content are competing for. Six destinations with
icons read fine in 80 dp.

### The chat panel needs vertical room as well as width

**Dilemma:** a phone in landscape is 891 dp wide — « expanded » like a tablet.
**Options:** panel on every expanded window / require a non-compact height too.
**Choice:** require height ≥ 480 dp.
**Why:** a conversation in a 360 × 411 dp column is a header, two bubbles and an
input. The collapsed bar it would replace costs nothing and opens a sheet that
takes 85 % of the window, which on a short screen is the better reading surface.
This is also why `appLayoutFor` takes the height at all.

### Voice still opens the sheet, even when the panel is on screen

**Dilemma:** the panel replaces the collapsed bar, and the mic was on that bar.
**Options:** no voice on a tablet / a mic in the panel's input row that opens the
modal sheet in voice mode.
**Choice:** the mic — and the brain, for the contexts — move into the panel's
input row with the same tags; voice mode still opens the sheet.
**Why:** dropping voice on a tablet would be a regression the ticket never asked
for. The voice sheet is a reading surface of its own — a big state line and a
push-to-talk mic — and it is what `VoiceControlBar` is drawn for.

### The `Scaffold` stays in `NavGraph`; `AppShell` is only the frame

**Dilemma:** `AppShell` replaces `ModalNavigationDrawer` *and* `Scaffold`, or only
the outer one?
**Options:** `AppShell(topBar, bottomBar, content)` owning the `Scaffold` / an
`AppShell` whose `content` is a `RowScope` and carries the caller's `Scaffold`.
**Choice:** the `RowScope`.
**Why:** the top bar and the collapsed bar are the *content's* chrome — their
labels, their badges and their callbacks are all `NavGraph`'s — while the rail and
the panel are the frame's. Keeping the `Scaffold` where it was also leaves the
400-line `NavHost` block at the nesting depth it already had: the other shape
reindents all of it, which is 800 counted lines of whitespace in a diff the guard
measures, and nothing for a reviewer to read.

## Risks

- **The Maestro journeys drive the burger.** `e2e/mobile/` uses `nav_menu` and
  `drawer_*`, and the CI emulator is a phone (`api-level: 34`). Compact keeps the
  burger and the modal drawer untouched, so the existing flows are the regression
  net for the phone half — and `task e2e:mobile:lint` still checks every id they
  use is declared.
- **A rail with seven entries does not fit a phone in landscape** (411 dp tall,
  7 × ~72 dp). The rail scrolls, and the settings entry is the last item rather
  than a footer pinned to the bottom: a footer with `weight(1f)` inside a
  scrollable column crashes on infinite constraints.
- **`ChatSheet` is refactored, not only extended.** Its two branches each carried
  their own copy of the list state and its two `LaunchedEffect`s; the panel would
  have been a third. They are one `rememberChatListState` now, which is why the
  diff on that file is larger than « add a panel » suggests. `ChatViewModelTest`
  (32 tests) and the chat journeys are what hold it.

# Mise en page adaptative (tablette, pliable) — Plan

Ticket: [MAG-35](https://linear.app/meven/issue/MAG-35/mise-en-page-adaptative-tablette-pliable)
Project: Android natif
Shaping notes, decisions and what is out of scope: [shape.md](shape.md)

## The six formats, in dp

The ticket's list, turned into the numbers every test and every preview names. A
`widthDp`/`heightDp` pair, not a device: that is the whole point of the model.

| Format | dp | Width class | Height class | Navigation | Chat |
|---|---|---|---|---|---|
| Téléphone 21:9 | 412 × 1000 | compact | expanded | burger + modal drawer | collapsed bar + sheet |
| Pliable fermé | 374 × 840 | compact | medium | burger + modal drawer | collapsed bar + sheet |
| Écran externe (Flip) | 280 × 290 | compact | compact | burger + modal drawer | collapsed bar + sheet |
| Pliable ouvert | 674 × 841 | medium | medium | **rail** | collapsed bar + sheet |
| Tablette portrait | 800 × 1280 | medium | expanded | **rail** | collapsed bar + sheet |
| Tablette paysage | 1280 × 800 | expanded | medium | **rail** | **permanent panel** |
| (Téléphone paysage) | 891 × 411 | expanded | compact | **rail** | collapsed bar + sheet |

The last row is not in the ticket; it falls out of the same arithmetic and is
asserted so that « expanded » never silently means « tablet ».

## Acceptance criteria

1. A window under 600 dp wide is drawn exactly as today: `TopAppBar` with the
   burger, `ModalNavigationDrawer`, collapsed chat bar, chat as a sheet. The
   Maestro journeys keep passing unchanged.
2. From 600 dp wide, the main screens show a `NavigationRail` carrying the same
   six destinations plus Paramètres, and the top bar has no burger. A detail
   route (Paramètres, Recherche, une fiche) still takes the whole width.
3. From 840 dp wide **and** 480 dp tall, the conversation is a permanent panel on
   the right of every main screen that had the collapsed bar, and the bar is
   gone. The panel carries the contexts button, the mic and the input.
4. The mic still opens the voice sheet, panel or no panel; a text chat request
   behind the panel opens nothing, because the conversation is already on screen.
5. The six formats of the table above are asserted on the JVM, by name, and are
   each a `@Preview` the owner can open in Android Studio.
6. Voice, contexts, search, notifications and every existing route stay reachable
   in all six formats.

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`.

## Task 2: The window model

`mobile/app/src/main/java/com/maggie/app/ui/layout/WindowLayout.kt` — new.

- `WindowWidth` / `WindowHeight` enums with the Material 3 breakpoints
  (600/840 and 480/900) and an `of(dp)` factory.
- `NavigationKind` — `MODAL_DRAWER`, `RAIL`.
- `AppLayout(width, height, navigation, chatPanelFits)`.
- `appLayoutFor(widthDp, heightDp)` — the pure decision.
- `rememberAppLayout()` — the same over `LocalConfiguration`.
- `RAIL_WIDTH`, `CHAT_PANEL_WIDTH`.

## Task 3: The shell

- `ui/layout/AppShell.kt` — new. Pure arrangement, no decision: a `Row` of
  (rail | content | chat panel), wrapped in `ModalNavigationDrawer` only when
  there is no rail. `content` is a `RowScope` and carries `NavGraph`'s `Scaffold`.
- `ui/components/AppDrawer.kt` — `MaggieNavigationRail(currentRoute, onNavigate)`
  beside the existing `AppDrawerContent`, over the same `DRAWER_DESTINATIONS` and
  `SETTINGS_DESTINATION` (together `RAIL_DESTINATIONS`), scrollable, tagged
  `UiTags.railItem(route)`.
- `ui/components/MaggieTopBar.kt` — `onMenuClick: (() -> Unit)?`; `null` draws no
  navigation icon, which is what a railed window needs.
- `ui/UiTags.kt` — `NAV_RAIL`, `RAIL_ITEM_PREFIX`, `railItem(route)`, `CHAT_PANEL`.

## Task 4: The chat panel

`ui/components/ChatSheet.kt` — the body the sheet and the panel share is factored
out, which also removes the duplication the two branches of `ChatSheet` carry
today (the list state and its two `LaunchedEffect`s, written twice).

- `rememberChatListState(viewModel)` — the list state, the scroll commands and the
  scrolled-to-bottom detection, once for all three surfaces.
- `ChatHistory(viewModel, listState, modifier)`.
- `ChatHeader(onClose, onSearch)` — `onClose` nullable: the panel has nothing to
  dismiss, so it has no close button.
- `ChatInputRow(viewModel, isSending, focusRequester, leading)` — the field and
  the send button, `CHAT_INPUT` / `CHAT_SEND`; `leading` is where the panel puts
  the contexts and mic buttons the collapsed bar carried.
- `ChatPanel(viewModel, onMicClick, onBrainClick, activeContextCount, modifier)` —
  new, `CHAT_PANEL_WIDTH` wide, full height, tagged `CHAT_PANEL`.

## Task 5: Wiring in `NavGraph.kt`

- `chromeFor(layout, route)` — internal pure function returning
  `Chrome(showsRail, showsChatPanel, showsChatBar)` from the window and the
  current route. This is where « the rail is for main screens » and « the panel
  replaces the bar » live, so both are unit tests.
- `showsChatSheet(requested, voiceMode, hasChatPanel)` — internal pure function:
  with a panel on screen only voice mode still opens the sheet.
- `ModalNavigationDrawer` replaced by `AppShell`; the `Scaffold` stays, with
  `Modifier.weight(1f)`.
- `navigateTo(route)` and `startVoiceMode()` extracted — the mic's permission
  dance is now called from two places (the bar and the panel).

## Task 6: The previews

`ui/layout/WindowPreviews.kt` — new.

- `@MaggieWindowPreviews`, a multipreview annotation with the six `@Preview`s of
  the table above, named in French as the owner reads them.
- `AppShellPreview` — the real `AppShell`, the real rail, the real top bar, the
  real collapsed bar, over a stand-in list and a stand-in panel (a `ViewModel`
  cannot be built in a preview). Six renders, one per format.

## Tests

Everything here is `mobile/app/src/test` — the JVM. Per
`agent-os/standards/mobile/screen-tests.md`, a layout *decision* is a function and
a layout *drawing* is a Robolectric screen test with `@Config(qualifiers=…)`;
neither needs a device.

| Unit touched | Tests |
|---|---|
| `appLayoutFor` | `WindowLayoutTest` — one test per row of the table above, named by the format, asserting the classes, the navigation and the panel; plus the breakpoints themselves (599/600, 839/840, 479/480, 899/900) |
| `chromeFor` | `AdaptiveNavigationTest` — compact → burger + bar, no rail; medium → rail + bar; expanded → rail + panel, no bar; the chat route gets no panel (it *is* the chat); a detail route gets no rail and no bar; no route yet gets nothing |
| `showsChatSheet` | `AdaptiveNavigationTest` — not requested → no; requested on a phone → yes; requested with a panel → no; voice mode with a panel → yes |
| `AppShell` + `MaggieNavigationRail` + `MaggieTopBar` | `AppShellScreenTest`, one `@Config` per format: 412×1000, 374×840 and 280×290 → `nav_menu` shown, `nav_rail` absent, chat bar shown. 674×841 and 800×1280 → rail shown, no burger, chat bar shown. 1280×800 → rail and `chat_panel` shown, chat bar absent. The content sits between the rail and the panel (`positionInRoot.x`). The rail carries `RAIL_DESTINATIONS` and a tap reports its route |
| `UiTags` | `railItem("grocery") == "rail_grocery"` |

Run from this worktree:
`ANDROID_HOME=~/Android/Sdk ./gradlew testProdReleaseUnitTest --tests 'com.maggie.app.ui.*'`
(Java 21 via `org.gradle.java.home`), then `task fix:all` and
`task e2e:mobile:lint`.

## E2E journey

Extends **MAG-98** (the Maestro journeys) — *for the compact half, which is what
the CI emulator is*: the existing flows drive `nav_menu` and `drawer_*` on a phone
AVD, and criterion 1 is exactly « they keep passing ». No flow is added or
changed.

> **Given** the owner opens Maggie on his phone (412 dp wide)
> **When** he taps the burger and picks Courses
> **Then** the drawer closes on the grocery screen, and the collapsed
> « Demander à Maggie… » bar is still at the bottom.

The wide half has no journey: a tablet AVD is a change to
`.github/workflows/ci.yml`, which the guard hands to a human (`infra-path`), so it
is a follow-up `Task` ticket carrying this one:

> **Given** a tablet AVD in landscape (1280 × 800 dp)
> **When** the owner opens Cuisine
> **Then** the navigation rail is on the left, there is no burger, and the
> conversation is in the panel on the right with no collapsed bar at the bottom.

Until it lands, the six formats are covered by the JVM screen tests above — which
is what the ticket asks for (« vérification par les previews Compose
multi-tailles et des profils d'émulateur, pas par un achat de téléphone ») and is
stricter than one AVD would be: six formats, every run, in seconds.

## Definition of done

1. Tests — the table above.
2. E2E — the compact half extends MAG-98 (no flow changed); the wide half is a
   follow-up `Task` for the tablet AVD, with the journey written above.
3. Not a bug fix.
4. CI green on the PR linking MAG-35.
5. Linear: the Android module's functional spec and the user guide gain the
   tablet and foldable layout, under the documentation index.

See `agent-os/standards/global/testing.md`.

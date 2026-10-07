# Mise en page adaptative (tablette, pliable) — Plan

Ticket: [MAG-35](https://linear.app/meven/issue/MAG-35/mise-en-page-adaptative-tablette-pliable)
Project: Android natif
Shaping notes, decisions and what is out of scope: [shape.md](shape.md)

## The six formats, in dp

The ticket's list, turned into the numbers every test and every preview names. A
`widthDp`/`heightDp` pair, not a device: that is the whole point of the model.

| Format | dp | Width class | Height class | Navigation | Chat | Top bar |
|---|---|---|---|---|---|---|
| Téléphone 21:9 | 412 × 1000 | compact | expanded | burger + modal drawer | collapsed bar + sheet | 64 dp |
| Pliable fermé | 374 × 840 | compact | medium | burger + modal drawer | collapsed bar + sheet | 64 dp |
| Écran externe (Flip) | 280 × 290 | compact | compact | burger + modal drawer | collapsed bar + sheet | **48 dp** |
| Pliable ouvert | 674 × 841 | medium | medium | **rail** | collapsed bar + sheet | 64 dp |
| Tablette portrait | 800 × 1280 | medium | expanded | **rail** | collapsed bar + sheet | 64 dp |
| Tablette paysage | 1280 × 800 | expanded | medium | **rail** | **permanent panel** | 64 dp |
| (Téléphone paysage) | 891 × 411 | expanded | compact | **rail** | **rail header** + sheet | **48 dp** |

The last row is not in the ticket; it falls out of the same arithmetic and is
asserted so that « expanded » never silently means « tablet ».

## Retour de recette — la hauteur du contenu en paysage (2026-10-07)

The owner refused the recette on that last row: « en mode paysage sur mobile, entre
le header et le chat de maggie, on n'a que très peu d'espace pour le contenu ». He is
right, and the number says so: 411 dp of window, minus a 64 dp top bar, minus the
72 dp of the collapsed bar, left **275 dp** of content — two list rows between two
bands of chrome.

A short window (height class compact, < 480 dp) now spends nothing it does not have:

- the three buttons of the collapsed bar — contexts, mic, « Demander à Maggie… » —
  move to the **rail's header**, which is the one piece of chrome that costs no height
  at all; the band is not drawn. `ChatEntry` is the new third answer of the model:
  `BOTTOM_BAR`, `RAIL`, `PANEL`, exactly one per window;
- the top bar is drawn at **48 dp** instead of 64 (`MaggieTopBar(dense = …)`): the
  icons keep their 48 dp touch targets, only the empty band around the title goes.

Content in landscape: **363 dp** instead of 275, asserted by measuring the node in
`AppShellScreenTest` rather than by naming the components it no longer contains.

A window that is short but too narrow for a rail — the cover screen of a Flip — keeps
the collapsed bar: there is nowhere else to put it. It gains the dense top bar.

**Known limits.** `dense` only reaches the shell's own top bar: a detail screen —
recipe, account transactions… — builds its own `TopAppBar` and still spends 64 dp in
landscape. And the shell still hands **no window inset** to the content, band or no
band: each piece pays its own (`ChatBottomBar`, `ChatPanel`, `ChatScreen`), and the
main screens that nest a `Scaffold` — Cuisine, Calendrier — get `safeDrawing` from it.
Material 3 1.3 does not consume `contentWindowInsets` for the body, so handing one down
from the shell would pay the gesture bar twice on exactly the screens this gives height
back to. What is left: on a screen with no `Scaffold` of its own (Tableau de bord), a
list scrolls under the gesture bar in landscape — the standard edge-to-edge behaviour,
and the last row is reachable by scrolling. Both out of scope for this recette return.

**E2E: N/A — no landscape journey can run.** The Maestro flows drive a phone AVD in
**portrait** and no flow rotates the device, so the format the owner refused is covered
by the JVM screen tests (891 × 411 in `AppShellScreenTest`) and by the « Téléphone
paysage » preview. The day an emulator profile lands (MAG-264), the journey to write is:
*Given* the phone in landscape on the Courses screen *When* the owner taps « Demander à
Maggie » in the rail *Then* the conversation opens, the grocery list keeps its full
height under the top bar, and its last row stays above the gesture bar.

## Acceptance criteria

1. A window under 600 dp wide is drawn exactly as today: `TopAppBar` with the
   burger, `ModalNavigationDrawer`, collapsed chat bar, chat as a sheet — with a
   48 dp top bar if it is also under 480 dp tall (the Flip's cover screen). The
   Maestro journeys keep passing unchanged.
2. From 600 dp wide, the main screens show a `NavigationRail` carrying the same
   six destinations plus Paramètres, and the top bar has no burger. A route that
   is not a main screen still takes the whole width and has no rail — including
   **Paramètres and Finance, which are rail entries but not main screens**: each
   brings its own back arrow, as it does on a phone today. Deliberate, and the
   reason `MAIN_SCREENS` and `RAIL_DESTINATIONS` are not the same list.
3. From 840 dp wide **and** 480 dp tall, the conversation is a permanent panel on
   the right of every main screen that had the collapsed bar, and the bar is
   gone. The panel carries the contexts button, the mic and the input.
4. The mic still opens the voice sheet, panel or no panel; a text chat request
   behind the panel opens nothing, because the conversation is already on screen.
5. The six formats of the table above are asserted on the JVM, by name, and are
   each a `@Preview` the owner can open in Android Studio.
6. Voice, contexts, search, notifications and every existing route stay reachable
   in all six formats.
7. On a window under 480 dp tall that has a rail — the phone in landscape — the
   collapsed bar is not drawn and its three buttons are at the top of the rail,
   under the same tags; the top bar is 48 dp; the content keeps at least 355 dp of
   the 411 the window has — measured on the JVM, where the system insets are zero; on
   the device the status bar takes ~24 of them (*retour de recette*, below).

## Task 1: Save spec documentation

This folder: `plan.md`, `shape.md`.

## Task 2: The window model

`mobile/app/src/main/java/com/maggie/app/ui/layout/WindowLayout.kt` — new.

- `WindowWidth` / `WindowHeight` enums with the Material 3 breakpoints
  (600/840 and 480/900) and an `of(dp)` factory.
- `NavigationKind` — `MODAL_DRAWER`, `RAIL`.
- `AppLayout(width, height, navigation, chatEntry, denseTopBar)` — the last two are
  the *retour de recette* below; the first delivery had a single `chatPanelFits`.
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
| `appLayoutFor` | `WindowLayoutTest` — one test per row of the table above, named by the format, asserting the classes, the navigation, the `ChatEntry` and the dense top bar; plus the breakpoints themselves (599/600, 839/840, 479/480, 899/900) |
| `chromeFor` | `AdaptiveNavigationTest` — compact → burger + bar, no rail; medium → rail + bar; expanded → rail + panel, no bar; **short + rail → rail header, no bar**; the chat route gets no entry point (it *is* the chat); a detail route gets no rail and no bar; no route yet gets nothing |
| `showsChatSheet` | `AdaptiveNavigationTest` — not requested → no; requested on a phone → yes; requested with a panel → no; voice mode with a panel → yes |
| `AppShell` + `MaggieNavigationRail` + `MaggieTopBar` | `AppShellScreenTest`, one `@Config` per format: 412×1000, 374×840 and 280×290 → `nav_menu` shown, `nav_rail` absent, chat bar shown. 674×841 and 800×1280 → rail shown, no burger, chat bar shown. 1280×800 → rail and `chat_panel` shown, chat bar absent. 891×411 → **the content node measures ≥ 355 dp** (275 before the fix) and starts at 48 dp (the dense bar; a 412×1000 window starts at 64), `chat_open` / `chat_mic` / `chat_contexts` are inside the rail (`positionInRoot.x` under the rail's right edge), and the last rail destination is still tappable after a scroll. The content sits between the rail and the panel. The rail carries `RAIL_DESTINATIONS` and a tap reports its route |
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

# UX rules, every module, both platforms

The screens were built module by module (MAG-90). These rules make them one app.
They sit on the design system (`design-system.md`: tokens, colours, type) and,
for the admin's identity, on Veilleuse (MAG-311). A rule that contradicts a
screen means the screen is out of standard: fix it, or write the exception here.

Every rule below names the piece that already does it. **Reuse it; don't draw a
second one.**

## 1. Navigation — one entry per module, entered by its dashboard

- **The main menu has one entry per module**: Accueil, Agenda, Cuisine, Courses,
  Finance… (owner's rule of 1 Oct., in MAG-90 and MAG-196). Not one per resource.
  The module names are these, on both platforms (« Cuisine », not « Nutrition »). Settings, the contexts and the
  account are not modules: they sit at the bottom of the menu or behind the
  avatar.
- **A module is entered by its dashboard**: what needs attention today first,
  then one tile or row per part of the module. First applied: Finance on mobile
  (MAG-196, `FinanceDashboardScreen`).
- **Ordered by frequency of use**, everywhere — menu, dashboard, tabs, a
  screen's actions: daily first and within thumb reach, monthly next, rare last,
  grouped (« Réglages <module> »). Write the order chosen in the module's spec.
- **Menu → dashboard → list → detail.** Tabs only for sibling views of the
  *same* data (week / month, à faire / fait), never to reach another part of
  the module. A part reached from the dashboard comes back to it with the back
  arrow, not through the menu.
- Deep links (notifications, Maggie, search results) open the item itself, with
  the module's dashboard as its back destination.
- **The admin keeps its desk density** (MAG-311): a module is one collapsible
  group whose header opens the module's dashboard and whose items are its parts,
  by frequency. No « Données brutes » group: a rare part sits at the end of its
  own module. A folded sidebar shows one icon per module, never fewer. Today's
  `components/layout/Menu.tsx` is out of standard on all three counts — don't
  copy it.

## 2. Lists, details, forms

- **List**: the line says what the item is and its one number (amount, date,
  quantity) at the right, in tabular figures. Sorted by what the user acts on
  (next date, most recent), the sort said in the header when it isn't obvious.
- **Detail**: the answer first (amount, date, state), the editable fields next,
  the history and technical fields (ids, sources, timestamps) last and folded.
  Destructive actions at the bottom, apart, with a confirmation that says what
  goes with it (« 3 repas planifiés seront supprimés »).
- **Form** — the admin's finance forms are the reference
  (`modules/finance/EnvelopeForm.tsx`, `CategorizationRuleForm.tsx`, built on
  `components/form/FormSection.tsx`):
  - fields in the order the user thinks: the *kind* first when it changes the
    rest (Dépense / Recette, MAG-301), then what, how much, when, then options;
  - titled sections that read as steps; two related fields per row on wide
    screens, one per row on a phone; inputs capped at a readable width;
  - **a helper line under every field whose effect can't be guessed from its
    label**, saying the effect, not repeating the label (« Une catégorie posée à
    la main ne sera jamais écrasée par cette règle. »);
  - **validation inline, on blur**, under the field, in French, saying how to
    fix it (« Le montant doit être positif », not « Invalid value »); the submit
    button stays enabled and focuses the first field in error;
  - sensible defaults (today, the current account, the last category used);
  - **dates and times through a picker** (react-admin `DateInput` /
    `DateTimeInput` on the admin, Material 3 `DatePickerDialog` /
    `TimePicker` on mobile — none is used there yet), never a free-text
    « AAAA-MM-JJ » field; a value that can't be parsed is an inline error,
    never a crash.
- **Choosing among a short fixed set** (a recurrence, a criticality, a unit) is
  a segmented button or radio list when there are up to five options, a menu
  beyond. Choosing among the user's data that grows (a product, an
  ingredient, a store) is the full-screen search below; a handful that doesn't
  (agendas, accounts) stays a menu.
- **Autocomplete opens a dedicated full-screen search on mobile**, never an
  inline dropdown (rule in place). On the admin, an `AutocompleteInput` is fine.

## 3. Empty, loading, error — human, in French

| State | Rule | Admin | Mobile |
|---|---|---|---|
| Empty | says what the screen is for and offers the next action; a filter that hides everything says so | `components/list/ListEmpty.tsx` (`empty=` on every `List`) | `ui/components/EmptyState.kt` |
| Loading | a skeleton or a spinner in place of the content, never a blank screen; nothing for under 300 ms | react-admin's `Loading` / `LinearProgress` | `CircularProgress` in place, or `PullToRefreshBox` |
| Error | what happened and what to do, with « Réessayer »; the technical message goes to the log, **never `e.message` on screen** | `useNotify` with a French message | `ui/components/ErrorSnackbar.kt` |
| Success | a short confirmation of what changed, with « Annuler » when it can be undone | react-admin's default `mutationMode: 'undoable'` | snackbar with action |

- **A failure is never shown as empty**, nor swallowed in the console: a
  search that failed doesn't say « Aucun résultat », a dashboard that didn't
  load isn't blank.
- Every text the user reads is French — react-admin's English defaults, field
  names of generated screens and tab labels included (`i18n/messages.ts`).
  « Aucun résultat », not « No results found »; « Événements », not `Events`.
- Short, direct, no jargon (`ULID`, `null`, `HTTP 500`), no exclamation marks.
- **The interface says *vous*** — labels, helpers, buttons, cards, snackbars
  (« Vérifiez votre connexion », « Choisissez un compte »), as most screens
  already do. Only Maggie's own words in the conversation follow her register,
  which the owner sets (« tutoie-moi », MAG-22). A *tu* in the chrome is out
  of standard (mobile `ApprovalCard`: « Maggie demande ton accord »,
  « réessaie » ; admin `UserPreferenceSettings`: « quand tu ne précises pas »).

## 4. Mobile first

- **Touch targets ≥ 48 dp** (48 px on the admin under `md`): `IconButton`, not a
  bare `Icon` with `clickable`; a small visual inside a 48 dp hit area is fine
  (`minimumInteractiveComponentSize()`).
- **Primary action within thumb reach**: a `FloatingActionButton` or a bottom
  button on a phone; the top bar holds navigation, search and rare actions
  (overflow menu). One primary action per screen.
- **A secondary action or a link** is a text button in the theme's primary
  colour — never a hand-picked hex (`design-system.md`, the Ciqual link) and
  never plain clickable text (a `Typography` with `onClick` is unreachable by
  keyboard).
- Swipe is a shortcut, never the only way: a swiped action exists in the item's
  menu or detail too.
- The Maggie bar at the bottom (`ChatBottomBar`) is chrome of its own: a FAB sits
  above it, never under it.

## 5. Screen formats

The window model decides, not the device: mobile `ui/layout/WindowLayout.kt`
(`appLayoutFor`, Material 3 breakpoints 600 / 840 dp wide, 480 dp high), admin
`src/breakpoints.ts` (one breakpoint, `md` = 900 px). The table is the rule;
*(target)* marks what no screen does yet.

| Format | Window | Rule |
|---|---|---|
| Phone portrait | compact | drawer behind the burger, Maggie bar at the bottom, one column |
| Phone landscape | short | rail, chat from the rail, dense top bar (48 dp) |
| **Z Flip** cover screen (« Flex Window ») | compact, tiny (~ 280 × 290 dp, `WindowPreviews.kt`) | nothing cut: one column, no fixed height, text that wraps, no list cell below 48 dp; a glance (next event, list in progress) beats a full screen |
| **Z Flip** inner screen (22:9) | compact, very tall | phone portrait; the extra height goes to the list, never to stretched cards |
| Tablet / unfolded | medium–expanded | `NavigationRail`, **list and detail side by side** *(target, MAG-263)*, chat as a side panel when expanded and tall enough |
| Admin < 900 px | narrow | menu and chat as drawers (MAG-38); a list reads without horizontal scroll — `SimpleList` instead of `Datagrid` *(target)* |
| Admin ≥ 900 px | desk | menu, page, chat side by side; desk density (MAG-311) |

A screen test names the format it checks (`appLayoutFor(800, 1280)`, the
Playwright projects 1440 / 834 / 393). No fixed width in dp or px for content.

## 6. Real time

- **What the user can see on screen updates by itself** through Mercure
  (`real-time.md`): a list, a detail, a counter, a badge — never a « tirer pour
  rafraîchir » as the only way. Pull to refresh stays as a fallback.
- **Someone else's change** (Maggie, a sync, another device) is signalled, a
  change of one's own is not: the new or changed row is briefly highlighted
  (admin `useItemTransitions`, as the dashboard widgets do),
  and a change to what the user is editing never overwrites the field — a
  banner « Modifié par Maggie · Recharger » instead.
- A lost connection is shown once, discreetly (« Hors ligne — les changements
  arrivent à la reconnexion »), not as an error per request.

## 7. Maggie in the interface

- **The chat is one gesture away from every screen**: the bottom bar on a
  phone, the rail on a short window, the side panel on a wide one (mobile
  `ChatEntry`); the right panel or its drawer on the admin (`ChatWidget`).
  It knows the screen it was opened from (`screenContext`).
- **Validation cards** (`ApprovalCard`): what Maggie wants to do, in one
  sentence with the figures (« Ajouter 3 articles à la liste Carrefour »), and
  two buttons, « Autoriser » first and « Refuser » second, as the mobile card
  already does. Never a bare « OK / Annuler », never the tool's name.
- **Suggestions** sit where the decision is taken (a rule proposed under the
  transaction, a recipe under the meal plan), dismissible, and never block the
  screen.
- **When Maggie speaks on her own**, one component per platform (the
  interruption, MAG-311 on the admin, MAG-314 on the phone): her avatar, one
  message, the proposed action and « Plus tard ».
- What Maggie did shows up as an action chip under her reply
  (« Courses · parmesan ajouté »), which opens the thing changed.

## 8. Accessibility

- **Contrast 4.5:1** for text, 3:1 for icons and borders, **in light and in
  dark** — checked by the theme tests, not by eye. Colour never carries the
  meaning alone: a state has a word or an icon too.
- **Text sizes**: `sp` on mobile, `rem` / theme variants on the admin, never a
  fixed px or dp height around text; the screen still works at 200 % font scale
  (nothing truncated that matters, lists wrap).
- **Screen readers**: every actionable icon has a label (`contentDescription`,
  `aria-label`) saying the action (« Supprimer l'article »); decorative icons
  have `null` / `aria-hidden`. Amounts read with their currency.
- **Motion** respects `prefers-reduced-motion` / the system's « Supprimer les
  animations »: a fade instead.

## Checking a screen against this standard

A ticket that creates or reworks a screen states, in its plan, the module's
entry point (§1), the format table it was checked on (§5), and its three states
(§3). The e2e journey goes through the dashboard, not a deep URL.

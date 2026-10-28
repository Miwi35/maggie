# Add or rework an admin screen

**When**: a `Feature` that adds or redraws a page of `admin/src/modules/<module>/` from a mockup, followed « à la lettre » (pills, labels, sizes). Not for a field added to an existing screen (→ `admin-api-field.md`), not for an Android screen (→ `mobile-screen.md`).
**Reference**: `admin/src/modules/grocery/StoreList.tsx` (master-detail on `LiveList`, one shared stream) with `StoreList.test.tsx` and `StoreList.live.test.tsx`; `admin/src/modules/grocery/resources.tsx` (registration); `admin/src/modules/finance/useMonthBalance.ts` (data hook for a custom endpoint).

## Steps
1. Open the mockup the ticket's `## Cadrage` links. List every visible label, pill, count and state; that list is the test plan. A missing mockup is a stop: ask, do not draw one.
2. `admin/src/modules/<module>/<thing>Api.ts` (or `<module>Topics` constant in it): the topics the page listens to, as one exported array (`STORES_PAGE_TOPICS`), and the raw `fetch` calls with `Authorization: Bearer` from `localStorage.getItem('token')`, `Accept: application/ld+json`, `application/merge-patch+json` for a PATCH. Skip it when react-admin's data provider covers the reads and writes.
3. Data:
   - an API Platform collection → wrap the page in `LiveList` (`components/list/LiveList.tsx`) with a `LiveConfig` (`hooks/liveList.ts`: `topics`, `required`, `references`, `accept`); read rows with `useListContext()`, write with `useCreate` / `useUpdate` / `useDelete`;
   - a custom endpoint → `admin/src/modules/<module>/use<Thing>.ts`: typed interface of the answer, `{ data, loading, error, refresh }`, a `latest` ref so only the last read writes (as `useMonthBalance`), and `useMercure(TOPICS, () => { refresh() })`.
4. Components: `admin/src/modules/<module>/<Thing>Page.tsx` (or `<Resource>List.tsx`) plus one file per block. MUI components, colours from the theme only (`theme.palette…`, `'primary.main'`, `theme.palette.module.<hue>`), pills for buttons/chips/tabs, cards by surface colour (Veilleuse).
5. States, in French and *vous*: empty → `components/list/ListEmpty.tsx` (`empty=` on the list) or a short `Typography`; loading → `Loading` / `LinearProgress`; error → `useNotify("…", { type: 'error' })` with a French sentence, never `e.message`; success of a deletion → react-admin's undoable mutation.
6. Route: in `admin/src/modules/<module>/resources.tsx`, load the page with `lazyPage(() => import('./<Thing>Page'), '<Thing>Page')`, then either `<ResourceGuesser name="<resource>" list={…} />` or `<CustomRoutes><Route path="/<path>" element={…} /></CustomRoutes>` (`<CustomRoutes noLayout>` for a full-screen activity). A new module also exports `<module>Resources` from its `index.ts` and is added in `admin/src/App.tsx`.
7. Menu and breadcrumb: add the part to `NAVIGATION` (or the module's `NavMenu` aspects/screens) in `admin/src/components/layout/navigation.ts` — the sidebar and the trail are built from it, no page writes its own.
8. Generated field names and labels in French: `admin/src/i18n/messages.ts`.
9. Journey: `e2e/web/tests/<module>-<feature>.spec.ts`, listed in `e2e/impact-map.yml` — follow `e2e-web-journey.md`.

## Tests owed
- `admin/src/modules/<module>/<Thing>Page.test.tsx`: render inside `MemoryRouter` + `AdminContext dataProvider={testDataProvider(…)}` (+ `ResourceContextProvider`), assert every mockup label and the order; the interaction the page exists for with `userEvent`; the empty state; the error (`getList` rejects, or `fetch` answers 500 → the French message).
- Mercure: `<Thing>.live.test.tsx` with `liveListSuite` from `admin/src/test/mercure.tsx` for a `LiveList`; otherwise `vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)` and `emitMercure(topic, payload)` → the UI changes.
- An identity component (new colour, pill, card): one render per mode with `renderWithTheme(ui, veilleuseLightTheme|veilleuseDarkTheme)` (`admin/src/test/renderWithTheme.tsx`); a new text colour goes through `admin/src/theme.test.ts`.
- New route: `admin/src/components/layout/Breadcrumbs.test.tsx` gets its trail case.
- Run: `task wt:test:admin -- src/modules/<module>/<Thing>Page src/components/layout/Breadcrumbs`, `task wt:lint:admin`, `task e2e:web:lint`; `task fix:all` before push.

## Standards
[global/ux](../global/ux.md) · [frontend/veilleuse](../frontend/veilleuse.md) · [admin/react-admin](../admin/react-admin.md) · [admin/testing](../admin/testing.md) · [global/real-time](../global/real-time.md) · [global/design-system](../global/design-system.md) · [global/testing](../global/testing.md) · [global/e2e-environment](../global/e2e-environment.md)

## Pitfalls
- **One Mercure stream per page.** Every `useMercure` with a different topic list opens an `EventSource`; over HTTP/1.1 the browser keeps six per origin and the shell already holds four — the page's own requests then queue forever (MAG-473, MAG-623). Share one topic array across the page's hooks.
- **Never refetch an Elasticsearch-served collection on an event**: the event outruns the index, the read misses the change. Use `LiveList`, which applies the payload (MAG-347); a custom hook that must read again waits (`useStoreCounts`: `INDEX_DELAY_MS`) and coalesces bursts (MAG-676).
- Write with the ULID, not react-admin's IRI: `bareId` / `idOf` of the module (`grocery/recurring.ts`, `cookbook/recipeSheet.ts`) — MAG-171, MAG-190.
- A `<Navigate>` inside a `:id/*` route must be absolute (`useCreatePath`): a relative one stacks on the splat and loops (React error #185, `cookbook/resources.tsx`).
- No hexadecimal colour, no explanatory text on screen (the why lives in the spec), no `tu` in the chrome.
- Copy the reference's layout, not its delete dialog: the standard is « do it, then Annuler »; a confirmation only when something else goes with it, and it says what.

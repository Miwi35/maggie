# Add or extend a Playwright journey (admin)

**When**: a `Feature` or user-visible `Bug` that the admin shows — the journey the ticket's « Parcours e2e » names. Not for screen logic a Vitest test can hold (`admin-testing` skill), and not for the Android app (`e2e-mobile-journey.md`). A step that talks to Maggie also needs `agent-fake-llm-scenario.md`.
**Reference**: `e2e/web/tests/meals-page.spec.ts` (MAG-628: own account, data created over the API, page object) with `e2e/web/pages/MealsPage.ts`; for a chat step `e2e/web/tests/meals-chat-move.spec.ts` (MAG-639). Guide: `e2e/web/README.md`.

## Steps
1. Extend the existing `e2e/web/tests/<module>-<feature>.spec.ts` when the journey is the same; otherwise a new file. Import `test`, `expect` (and `seedId`, `seedDate`, `parisDay`, `e2eNow`, `parisTime`) from `../fixtures/index.js`, never from `@playwright/test`.
2. A journey that writes: `test.use({ journeyAccount: 'empty' | 'standard' | 'stock' })` — `page`, `api`, `session` and `journeyUser` are then that user's (MAG-577). Read-only on the seed: the default signed-in user.
3. Create the data the test owns through `api` (`api.post('/api/…', { headers: jsonLd, data })`), keep the ids the responses return, and suffix labels with `test.info().retry` (CI retries once without reseeding).
4. Seeded data instead: `api/fixtures/e2e/<NN>-<module>.yaml`, Alice reference `e2e_…`, dates `<e2eDate("+2 days 09:00")>`; read it with `seedId('e2e_…')` / `seedAnchorDate()`; bump the counts in `api/modules/core/tests/E2e/E2eSeedCommandTest.php`.
5. Page object `e2e/web/pages/<Screen>Page.ts` extending `AdminShell`: locators scoped to `this.content`, by role or `getByTestId` (the `data-testid` of the admin component, added in `admin/src/modules/<module>/…` if missing); route in `e2e/web/pages/routes.ts` (`ROUTES`).
6. Wait, never read once: `waitForIndexed(api, '/api/…', (row) => row.id === id)` (`helpers/api.ts`) or `expect.poll`; real time: `openSubscribed` (`helpers/mercure.ts`) before acting; chat: `new ChatPanel(page).send(…)` then `calledTools` / `toolResults` (`helpers/agui.ts`).
7. Assert the effect on the API (DB state), not only the screen; assert Maggie's wording only against her scenario.
8. `e2e/impact-map.yml`, under `web:`: `e2e/web/tests/<file>.spec.ts: { areas: [<module>…], paths: [<scenario>, <wiremock mapping>…] }`.
9. Header comment of the spec: the Given/When/Then of the ticket and the journey ticket it extends.

## Tests owed
- The journey itself, green in CI on the PR (`E2E` jobs are required checks).
- Lint and types, no stack needed: `task e2e:web:lint`, `task e2e:web:typecheck` (both in `task lint:all`).
- Locally, only to write or debug it (CI runs it anyway), in the worktree:
  `task e2e:up` → `task e2e:web -- tests/<file>.spec.ts --retries=0` (reseeds itself; `task e2e:seed` alone to reset between manual tries) → `task e2e:down` before the PR.
  After editing `admin/src`: `task e2e:admin:build`; after PHP code or config: `task e2e:cache:clear`.
- `--project=phone` / `tablet` only when the test is tagged `@responsive`.

## Standards
- [global/e2e-environment](../global/e2e-environment.md) — *Seeded data*, *The clock*, *Sélecteurs*, *What a new journey owes*
- [global/testing](../global/testing.md)
- [global/worktree-checks](../global/worktree-checks.md)

## Pitfalls
- A row found by part of its name also matches the seed's and the last attempt's: keep the id the POST returned (`6dffe1ce6`, refused by `task e2e:web:lint`, rule 1).
- `getByLabel('Nom')` matches by substring (a sort header too): use `getByRole('textbox', { name: 'Nom' })`.
- `AdminShell.goto` reloads on the current route; a plain `page.goto` to the same hash URL is a no-op and the view never refetches.
- Never `Date.now()` or a literal date: `e2eNow()`, `parisTime(day, '09:00:00')`, `seedDate(n)`.
- A journey chaining agent runs can pass 90 s: set its own `test.setTimeout` rather than quarantine (`9debfe4f1`, MAG-677). A flake is fixed at its cause; `quarantine: {since, reason}` in the map only warns and is lifted after a green week.
- `meals-chat-move.spec.ts` still writes as the shared `otherUser`; new journeys use `journeyAccount` (standard rule 6).

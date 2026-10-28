# Consume a new or changed API field in the admin

**When**: the API added, renamed or changed a field (or a filter / sort parameter) and an admin screen must read, show or send it. The API side is its own ticket (`api-entity-field.md`) and lands first (`blockedBy`). Not for a new page (→ `admin-screen.md`), not for Android (→ `mobile-api-field.md`).
**Reference**: MAG-648 (`b29f8bb25`): `admin/src/modules/cookbook/recipeSheet.ts` (type + pure formatters), `admin/src/modules/cookbook/RecipeBlocks.tsx` and `RecipeBlocks.test.tsx`; `admin/src/modules/cookbook/cooking/cookingStatus.test.ts` (a test that reads `api/contract/responses/`).

## Steps
1. Read the field as the API really sends it in `api/contract/responses/<resource>.<collection|read>.json` (REST) and, if the screen is live, in the entity's `toMercurePayload()` (Mercure). Note both spellings: they can differ.
2. Type: add the field to the interface the module already declares for that answer (`RecipeRecord` in `recipeSheet.ts`, `MonthBalance` in `useMonthBalance.ts`, `FinanceDashboard` in `useFinanceDashboard.ts`), optional (`field?:`) when the API may omit it, with a one-line JSDoc saying what null/absent means. One interface per answer: never redeclare it in a component.
3. Formatting: a pure, exported function in the same module `.ts` (`micronutrientAmount`, `durationLabel` in `finance/nextProject.ts`), French output, tabular figures left to the theme.
4. Display: the component reads the typed field; absent/null renders the documented fallback, never `undefined`, `null` or `NaN` on screen.
5. Live: a `LiveList` whose payload names another resource by ULID gets it in `LiveConfig.references` (`accountId` → `account`); a field a new row must carry goes in `required`. A Mercure key spelled differently from REST is read under both names or mapped once.
6. Sending it: through `useUpdate` / `useCreate` or the module's `*Api.ts`; an identifier as the bare ULID (`bareId` / `idOf` of the module), never the IRI react-admin holds; PATCH as `application/merge-patch+json`.
7. A new filter or sort: use it as `filter={{ key: … }}` / `sort: { field: '…' }` literals so `admin/src/queryParameters.contract.test.ts` sees it; it must be declared in `api/contract/openapi.json` and `api/contract/query-parameters.json` (the API ticket).
8. A new topic: the literal `'/api/<resource>/{id}'` (spelled once in the module's `*Api.ts`) must be in `api/contract/mercure-topics.json`; agent topics go through `agentTopic()` (`admin/src/hooks/agentTopics.ts`).

## Tests owed
- `admin/src/modules/<module>/<module-file>.test.ts`: the formatter, every branch (absent, 0, singular/plural, large value).
- `admin/src/modules/<module>/<Component>.test.tsx`: the field displayed from a fixture copied from the recorded response (same key names), the fallback when absent, and the write's body when the screen sends it (`fetch` / data-provider spy asserting the bare ULID).
- A contract test when the screen interprets the shape (enum, nested object): read the recording as `cookingStatus.test.ts` does (walk up to `api/contract/`, parse `responses/<name>.json`, run the admin's reader on it).
- Mercure-fed field: `emitMercure(topic, payload)` (`admin/src/test/mercure.tsx`) with the payload's real key → the screen shows the new value.
- Run: `task wt:test:admin -- src/modules/<module> src/queryParameters.contract src/hooks/useMercure.contract`, `task wt:lint:admin`; `task fix:all` before push.

## Standards
[admin/testing](../admin/testing.md) · [admin/react-admin](../admin/react-admin.md) · [global/real-time](../global/real-time.md) · [api/resources-only](../api/resources-only.md) · [global/testing](../global/testing.md) · `api/contract/README.md`

## Pitfalls
- **Serialized names**: Symfony serialises `isCushion()` as `cushion` in REST while the Mercure payload says `isCushion`. A fixture written by hand with the client's own name passes and the screen is wrong in production — copy fixture keys from `api/contract/responses/`. The admin has no `DtoContractTest`: nothing else catches it.
- A query parameter API Platform does not declare is dropped silently and the list comes back unfiltered; a sort or filter built dynamically escapes `queryParameters.contract.test.ts`.
- Collections use `member`, not `hydra:member`; records carry their IRI as `id`, the ULID is the last segment.
- A recording without the field (empty list, field omitted when empty) proves nothing: ask the API ticket to fill `api/tests/Contract/fixtures/RecordedResponses.yaml` and rerun `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`.
- Never compute business values in the admin from raw fields: the API serves the result (`api/resources-only`); the front only formats and filters.

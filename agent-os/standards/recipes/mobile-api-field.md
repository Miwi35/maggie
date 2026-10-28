# Consume a new or changed API field on Android

**When**: the API added, renamed or changed a field and the Android app must read or send it. The API side is its own ticket (`api-entity-field.md`) and lands first (`blockedBy`), with the field in the recorded response. Not for a new screen (→ `mobile-screen.md`), not for the admin (→ `admin-api-field.md`).
**Reference**: MAG-648 (`b29f8bb25`): `mobile/app/src/main/java/com/maggie/app/data/model/Recipe.kt` (`micronutrientsPerServing`, `Micronutrient`), its case in `mobile/app/src/test/java/com/maggie/app/data/model/DtoContractTest.kt`, and `ui/screens/cookbook/recipes/RecipeLiveUpdate.kt` (the same field read from Mercure).

## Steps
1. Read the field as the API sends it: REST in `api/contract/responses/<resource>.<collection|read>.json`, Mercure in the entity's `toMercurePayload()`. Write both spellings down.
2. DTO, `mobile/app/src/main/java/com/maggie/app/data/model/<Thing>.kt`: the property on the `@Serializable data class`, **with a default** (`= null`, `= emptyList()`, `= 0`) unless the API always sends it; a nested object is its own `@Serializable data class` in the same file with a KDoc saying what null means. A name that differs from the Kotlin one takes `@SerialName("…")` (`Account.kt`: `@SerialName("cushion") val isCushion`).
3. Enum-like strings stay `String` in the DTO; the French label is a function next to it or in the screen (MAG-636: the criticality chip says its French word, not « low »).
4. Sending it: the request class in `data/api/MaggieApiService.kt` (`<Thing>WriteRequest`), identifiers as bare ULIDs; the repository method passes it through, `Result<T>` via `runCatching`.
5. Live update: the screen's Mercure reader applies the new key (`RecipePatch.applyTo` in `RecipeLiveUpdate.kt`: `fields["<key>"]?.let { json.decodeFromJsonElement(…) } ?: recipe.<key>`), else the value shown goes stale until a reload.
6. Mapping and display: formatting functions (amounts, durations, labels) are pure Kotlin next to the DTO (`FinanceHome.kt`: `durationLabel`) or the screen, then shown with a `UiTags` tag if a test addresses it.
7. A field the API legitimately never sends in the recording (client-side only, or omitted when empty and the recorded world has none): add it to `fieldsTheApiDoesNotEmit` in `DtoContractTest` **with the reason** — a decision, not an escape.

## Tests owed
- `DtoContractTest`: for a new top-level field, the existing `assertCollectionContract` / `assertItemContract` case covers it; for a nested object, a new test that reads the recording (`readRecording("<name>")`), asserts it is not empty (« it proves nothing »), calls `assertDeclaredFieldsExist("<Dto>", serializer<Dto>(), keys, "<name> (<part>)")` and asserts the decoded values.
- `mobile/app/src/test/java/com/maggie/app/data/model/<Thing>Test.kt`: every formatter branch.
- The Mercure reader's test (`…/<Screen>LiveUpdateTest.kt` or the ViewModel test): a payload carrying the key → the state holds the new value.
- The screen test of the screen that shows it (`…ScreenTest.kt`, `ScreenRule`): the label drawn, the fallback when absent.
- A new query parameter: `data/api/QueryParameterContractTest.kt` checks it against `api/contract/openapi.json`.
- Run: `task wt:test:mobile -- --tests 'com.maggie.app.data.model.DtoContractTest' --tests 'com.maggie.app.data.model.<Thing>Test' --tests 'com.maggie.app.ui.screens.<module>.*'`.

## Standards
[mobile/testing](../mobile/testing.md) · [mobile/android-app](../mobile/android-app.md) · [mobile/screen-tests](../mobile/screen-tests.md) · [global/real-time](../global/real-time.md) · [global/testing](../global/testing.md) · `api/contract/README.md`

## Pitfalls
- **`isCushion` vs `cushion`**: Symfony drops `is` in REST, the Mercure payload keeps `isCushion`. A mismatched name deserialises to the default without error — the badge reads `false` forever. `assertDeclaredFieldsExist` is the only thing that catches it; never silence it by a default alone.
- A non-null property without default crashes the whole list when the API omits the field (it omits an empty note, `Transaction`): give it a default.
- A field whose shape varies (string or list, as Elasticsearch highlights) crashes the screen on first use (MAG-337): read it as `JsonElement` and accept both.
- The recording must carry a non-empty value: if `api/contract/responses/` lacks it, the API ticket fills `api/tests/Contract/fixtures/RecordedResponses.yaml` and reruns `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract` — never edit the recording by hand.
- Amounts are cents (`Int`/`Long`), formatted by the shared formatters (`data/model/Money.kt`), never `Float` euros.

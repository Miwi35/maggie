# Add or extend a Maestro journey (Android)

**When**: a `Feature` or user-visible `Bug` on Android whose check needs a real device — a sheet under the keyboard, rotation, a window, permissions, deep links, notifications, real time between two clients. What a screen draws, its order, empty and error states are a Compose test on the JVM (`mobile/screen-tests.md`), not a flow. Admin: `e2e-web-journey.md`.
**Reference**: `e2e/mobile/flows/27-cooking-launch.yaml` (MAG-580: own user, data by script, deep link, ids) with `e2e/mobile/scripts/recipe-api.js`; for a list on every format, `e2e/mobile/flows/29-agenda-trips.yaml` (MAG-658). Guide: `e2e/mobile/README.md`.

## Steps
1. Extend the flow of the same journey, or add `e2e/mobile/flows/<NN>-<module>-<feature>.yaml`: `appId: com.maggie.app.e2e`, `name`, `tags`, `---`, then a header comment with the Given/When/Then, the ticket, the journey it extends, and which JVM tests hold the rest.
2. A flow that writes: `- runFlow: ../subflows/new-user.yaml` first, then `launchApp` with `clearState: true`, `permissions: { all: allow }`, `arguments: { e2e_journey: e2e/mobile/flows/<file>.yaml, e2e_email: ${output.journeyEmail} }`, then `- runFlow: ../subflows/sign-in.yaml`. Profile other than `empty`: top-level `env: PROFILE`.
3. Data and the « other device »: `e2e/mobile/scripts/<module>-api.js` (`runScript` with `env: ACTION, EMAIL: ${output.journeyEmail}`), reading `E2E_BASE_URL` / `E2E_LOGIN_TOKEN` from `run.sh`, setting `output.<id>`, polling the API (never one read). Reuse an existing script when one covers the module.
4. Navigate through subflows: `openLink: "maggie-e2e://…"`, `../subflows/open-destination.yaml` (`env: DEST`), `open-calendar.yaml`, `open-chat.yaml` / `close-chat.yaml`, `start-voice.yaml`. Never tap `nav_menu` or `chat_open` directly.
5. Address nodes by `id:`. A missing one: `const val` in `mobile/app/src/main/java/com/maggie/app/ui/UiTags.kt` (a `*_PREFIX` const + function for ids carrying a data id or `${TODAY}`), `Modifier.testTag(UiTags.…)` on the composable, and `Modifier.uiTagRoot()` on the root of any `Dialog` / `ModalBottomSheet` content that carries tags.
6. Wait with `extendedWaitUntil` (explicit `timeout`), reach list items with `scrollUntilVisible` — or `repeat: while: notVisible` + `swipe` from 25 % / 75 % on a tablet layout; dates from `${TODAY}` (and `run.sh` variables), never literals.
7. A chat step: its fake-LLM scenario first (`agent-fake-llm-scenario.md`), then `assertNotVisible: ".*aucun scénario.*"` after the exchange.
8. Register the flow: `e2e/mobile/config.yaml` (`flowsOrder`), one shard line of `e2e/mobile/shards.txt` (the lowest measured sum; update the comment listing unmeasured flows), and `e2e/impact-map.yml` under `mobile:` with `areas` and `paths` (its script, its scenario, the screens it reads).

## Tests owed
- The flow, run by CI on every PR the impact map selects (phone), and nightly on phone, foldable and tablet.
- Locally **only** `task e2e:mobile:lint` (no device, no stack; needs a host JDK; in `task lint:all`, not in `task fix:all`): syntax, every `id:` declared in `UiTags.kt`, `uiTagRoot()` per window, `e2e_journey` on each `launchApp`, unawaited assertions, selector rules, shards.
- New tags or composables: their JVM test, `task wt:test:mobile -- --tests '<ScreenTest class>'`.
- Never `./gradlew`, an emulator or `task e2e:mobile` to check a change — only to write or debug a journey, with one device.

## Standards
- [mobile/screen-tests](../mobile/screen-tests.md) — flow or JVM test
- [global/e2e-environment](../global/e2e-environment.md) — *The emulator harness*, *Sélecteurs*, *What a new journey owes*
- [mobile/testing](../mobile/testing.md), [global/testing](../global/testing.md)

## Pitfalls
- `id:` and `text:` on one selector match nothing unless the tagged node is clickable (merged): otherwise `containsDescendants`. A ticked state is `checked:`, `selected:` only on `subview_` tabs.
- Never a bare `hideKeyboard` (a *back* that closes the sheet): `runFlow: ../subflows/hide-keyboard.yaml`.
- Every `text:` is a regex on the whole node text: wrap rows in `.*`, escape `?`.
- A timeout counts from the last interaction: a `retry` around a wait starts with `- evalScript: ${0}`.
- `scrollUntilVisible` swipes from the screen's centre — the detail pane on a tablet (`a5ee2bef6`, MAG-646); branch on `finance_two_columns` / `detail_pane` rather than assume the phone layout.
- `29-agenda-trips.yaml` and `29-finance-cushion-share.yaml` still write on a seeded account (no `new-user.yaml`); new flows follow rule 7 of the README.

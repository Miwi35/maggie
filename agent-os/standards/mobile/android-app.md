# Android Mobile App

Kotlin + Jetpack Compose + Material 3.

## Stack choices
- **Koin** for DI (lightweight, less boilerplate than Hilt)
- **Ktor** for HTTP client (built-in SSE support for Mercure)
- **Navigation Compose** for routing
- BuildConfig fields for API_BASE_URL and MERCURE_URL

## Pattern
- Repository pattern: `ApiService` → `Repository` → `ViewModel`
- Hydra JSON-LD parsed via `HydraCollection<T>` wrapper

## Build Environment
- **JAVA_HOME:** `/opt/android-studio-for-platform/jbr` (Java 21) — host Java is 11, always use Android Studio JBR
- `gradle.properties` has `org.gradle.java.home` set to Android Studio JBR
- **adb:** `~/Android/Sdk/platform-tools/adb`

## Signing & Google Credentials
- **Release keystore:** `mobile/release.keystore` (alias `maggie`, config in `keystore.properties`)
- **Release SHA1:** `C5:AD:BF:94:9F:08:68:09:44:78:F1:F0:8B:A2:41:CA:B1:78:4C:DA` — registered in Google Cloud Console
- Google OAuth credentials only work with the release signing key
- **Always build `prodRelease`** for device testing: `task mobile:install` (checks a phone is connected, then an incremental `./gradlew installProdRelease` — never `clean`)
- Do NOT use `devDebug` or `prodDebug` — the debug keystore SHA1 is not registered in GCP

## The `e2e` flavor (MAG-98)

A third flavor, for the Maestro journeys only. `e2eDebug` is the one debug
variant that is fine to build: it never talks to Google, so the keystore SHA1
above is irrelevant to it.

- Signs in through `POST /api/auth/e2e/login` instead of Credential Manager,
  which no emulator can drive. The implementation is in `app/src/e2e/` and is
  therefore **not compiled into `dev` or `prod`**; those two link the Google one
  from `app/src/google/`, a source set they share. `src/main/` knows neither —
  it only declares the `SignInStrategy` seam and `AuthManager`, which persists
  whatever payload came back.
- Anything else that must only exist on the emulator goes in `app/src/e2e/`, with
  its unit tests in `app/src/testE2e/`. the `mobile-unit` job of `ci.yml` runs the prod variant; the
  `E2E Mobile unit tests (e2e flavor)` CI job runs `testE2eDebugUnitTest`.
- `API_BASE_URL` is `http://localhost:8099`, bridged onto the stack's ephemeral
  port by `adb reverse` — never a hard-coded host port. Override with
  `-PE2E_API_BASE_URL`.
- UI the flows drive carries a `testTag` from `ui/UiTags.kt`, published to
  UiAutomator by `Modifier.uiTagRoot()` — **once per window, not once per app**.
  A `Dialog` or a `ModalBottomSheet` is a separate semantics owner, so the one on
  `MainActivity` does not reach it and its tags have no resource id at all.
  `task e2e:mobile:lint` fails on both halves: an id a flow uses that is not
  declared, and a tagged window with no `uiTagRoot()`.

Full guide: [e2e/mobile/README.md](../../../e2e/mobile/README.md).

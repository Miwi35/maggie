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
- **Always build `prodRelease`** for device testing: `JAVA_HOME=/opt/android-studio-for-platform/jbr ./gradlew installProdRelease`
- Do NOT use `devDebug` or `prodDebug` — the debug keystore SHA1 is not registered in GCP

---
name: mobile-android
description: "Android app architecture and build conventions. Use when creating or modifying Kotlin code in mobile/ including Jetpack Compose UI, Ktor HTTP, Koin DI, Room database, signing, and product flavors."
user-invocable: false
---

# Android Mobile App

Kotlin + Jetpack Compose + Material 3.

## Stack

- **Koin** for DI (lightweight, less boilerplate than Hilt)
- **Ktor** for HTTP client (built-in SSE support for Mercure)
- **Navigation Compose** for routing
- **Room** for local database (KSP)
- BuildConfig fields for `API_BASE_URL` and `MERCURE_URL`

## Architecture Pattern

Repository pattern: `ApiService` → `Repository` → `ViewModel`

Hydra JSON-LD parsed via `HydraCollection<T>` wrapper.

## Build Environment

- **JAVA_HOME:** `/opt/android-studio-for-platform/jbr` (Java 21) — host Java is 11
- `gradle.properties` has `org.gradle.java.home` set
- **adb:** `~/Android/Sdk/platform-tools/adb`
- compileSdk 35, minSdk 29, targetSdk 35

## Signing & Google Credentials

- Release keystore: `mobile/release.keystore` (alias `maggie`, config in `keystore.properties`)
- Release SHA1: `C5:AD:BF:94:9F:08:68:09:44:78:F1:F0:8B:A2:41:CA:B1:78:4C:DA`
- Google OAuth credentials ONLY work with release signing key
- **Always build `prodRelease`** for device testing
- Do NOT use `devDebug` or `prodDebug`

## Product Flavors

| Flavor | API URL | App ID suffix |
|--------|---------|---------------|
| dev | `http://10.0.2.2` (emulator alias for host) | `.dev` |
| prod | `https://maggieai.fr` | — |

## Reference

For full details, read `agent-os/standards/mobile/android-app.md`

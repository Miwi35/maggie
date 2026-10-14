---
name: mobile-android
description: "Android app architecture and build conventions. Use when creating or modifying Kotlin code in mobile/ including Jetpack Compose UI, Ktor HTTP, Koin DI, Room database, signing, and product flavors."
user-invocable: false
---

# Android Mobile App

**Use when** creating or changing Kotlin code in `mobile/` (Compose, Ktor, Koin, Room, signing, flavors).

**Read** `agent-os/standards/mobile/android-app.md`; screen logic tests: `agent-os/standards/mobile/screen-tests.md`.

## Gotchas

- Pattern: `ApiService` → `Repository` → `ViewModel`; Hydra JSON-LD through `HydraCollection<T>`.
- Google OAuth only works with the release key: device testing is `task mobile:install` (`prodRelease`), never `devDebug` or `prodDebug`. That is the owner's phone.
- Agents build no APK: their one Gradle command is `task wt:test:mobile -- --tests …`; CI does the rest.
- The `e2e` flavor signs in through `/api/auth/e2e/login`; its code lives in `app/src/e2e/`, never `src/main/`.
- Autocomplete opens a dedicated full-screen search, never an inline dropdown.

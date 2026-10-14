---
name: admin-oauth
description: "Google OAuth 2.0 login flow. Use when modifying authentication, login page, auth provider, JWT handling, or Google callback logic in admin/ or api/."
user-invocable: false
---

# Google OAuth Login

**Use when** touching sign-in, the auth provider, JWT or refresh handling, or the Google callback (admin and API).

**Read** `agent-os/standards/admin/google-oauth.md`: flow, key files, endpoints, session renewal.

## Gotchas

- The refresh token is single-use: one refresh at a time (shared promise in a tab, Web Locks across tabs), or the loser of two concurrent calls is signed out.
- `handleAuthCallback()` runs at module level in `App.tsx`, before React renders.
- A refresh that fails for lack of network (fetch error, 5xx) keeps the session; a 4xx clears it.
- `/api/auth/*` is public, everything else needs the JWT. Mobile uses `POST /api/auth/google` and still gets `refresh_token` in the body.
- EventSource does not reconnect after a 401: a tab that slept past expiry reloads after renewing.

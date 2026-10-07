---
name: admin-oauth
description: "Google OAuth 2.0 login flow. Use when modifying authentication, login page, auth provider, JWT handling, or Google callback logic in admin/ or api/."
user-invocable: false
---

# Google OAuth Login

Authorization Code flow — no local credentials. All users sign in via Google.

## Flow

1. Login page links to `GET /api/auth/google/redirect`
2. API redirects to Google consent screen
3. Google calls back `GET /api/auth/google/callback?code=…`
4. API exchanges code for `id_token` via `https://oauth2.googleapis.com/token`
5. API validates token, finds or creates `User` (matched by Google `sub` claim)
6. API generates Lexik JWT and redirects to `ADMIN_URL?token=JWT&user=JSON`, with two cookies: `mercureAuthorization` and the httpOnly `refresh_token`
7. `handleAuthCallback()` stores in `localStorage`, strips URL params

## Key Files

| File | Role |
|------|------|
| `admin/src/auth/LoginPage.tsx` | Login UI — single Google button |
| `admin/src/auth/authProvider.ts` | React-Admin auth provider + `handleAuthCallback()` |
| `admin/src/auth/session.ts` | Refresh, 401 replay (`window.fetch` wrapper), renewal timer |
| `admin/src/App.tsx` | Runs callback before render, injects Bearer token |
| `api/modules/core/src/Controller/GoogleAuthController.php` | redirect, callback, mobile POST |
| `api/modules/core/src/Security/RefreshTokenCookieFactory.php` | The `refresh_token` cookie, same flags as Gesdinet's |
| `api/modules/core/src/EventListener/RenewMercureCookieOnRefreshListener.php` | New Mercure cookie on every refresh |
| `api/modules/core/config/services.yaml` | Binds client ID, secret, redirect URI, admin URL |

## Auth Provider

```ts
login()       → stores token + user in localStorage
logout()      → removes token + user
checkAuth()   → expired token: tries the refresh cookie first, rejects only if refused
checkError()  → clears auth on a 401/403 the replay could not fix (not while offline)
getIdentity() → returns {id, fullName, avatar} from stored user JSON
```

`handleAuthCallback()` runs before React renders (called at module level in `App.tsx`).

## API Endpoints

- `GET /api/auth/google/redirect` — initiates web OAuth flow
- `GET /api/auth/google/callback` — exchanges code for JWT, redirects to admin
- `POST /api/auth/google` — mobile flow, accepts `{"idToken": "…"}`, returns `{token, user}`

## Security

- `/api/auth/*` routes are public (firewall `security: false`)
- All other `/api/*` routes require JWT (`ROLE_USER`)
- JWT TTL: 24 hours (Lexik config)
- Token audience validated against `GOOGLE_CLIENT_ID`

## Session renewal (MAG-37)

- Refresh token: Gesdinet, 30 days, **single-use** (each refresh rotates it). Cookie `refresh_token`, httpOnly, SameSite=Strict, path `/api/token`, Secure (not in the e2e stack). The page never reads it.
- `POST /api/token/refresh` (body `{}`, `credentials: 'include'`) → `{token}` and renewed `refresh_token` + `mercureAuthorization` cookies. `POST /api/token/invalidate` deletes the token and clears the cookie (logout).
- One refresh at a time: in a tab (shared promise) and across tabs (Web Locks). Two concurrent calls would burn the token and sign the loser out.
- `installAuthRefresh()` wraps `fetch`: a 401 on a request carrying a Bearer to our own origin is replayed once with the new token. Covers dataProvider, REST hooks and agent calls.
- `startSessionKeeper()` renews 10 min before expiry and on wake (`visibilitychange`, `online`, `focus`). A tab that slept past expiry reloads after renewing: EventSource does not reconnect after a 401.
- A refresh that fails for want of a network (fetch error, 5xx) keeps the session; a 4xx clears it.
- Mobile keeps the body flow: `refresh_token` is still returned in the JSON body (`remove_token_from_body: false`).

## Reference

For full details, read `agent-os/standards/admin/google-oauth.md`

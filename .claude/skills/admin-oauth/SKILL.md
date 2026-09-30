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
6. API generates Lexik JWT and redirects to `ADMIN_URL?token=JWT&user=JSON`
7. `handleAuthCallback()` stores in `localStorage`, strips URL params

## Key Files

| File | Role |
|------|------|
| `admin/src/auth/LoginPage.tsx` | Login UI — single Google button |
| `admin/src/auth/authProvider.ts` | React-Admin auth provider + `handleAuthCallback()` |
| `admin/src/App.tsx` | Runs callback before render, injects Bearer token |
| `api/modules/core/src/Controller/GoogleAuthController.php` | redirect, callback, mobile POST |
| `api/modules/core/config/services.yaml` | Binds client ID, secret, redirect URI, admin URL |

## Auth Provider

```ts
login()       → stores token + user in localStorage
logout()      → removes token + user
checkAuth()   → rejects if no token
checkError()  → clears auth on 401/403
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

## Reference

For full details, read `agent-os/standards/admin/google-oauth.md`

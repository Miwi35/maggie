# Google OAuth Login

Google OAuth 2.0 (Authorization Code flow) for admin authentication. No local credentials — all users sign in via Google.

## Flow

1. Login page links to `GET /api/auth/google/redirect`
2. API redirects to Google consent screen (`scope=openid email profile`, `prompt=select_account`)
3. Google calls back `GET /api/auth/google/callback?code=…`
4. API exchanges code for `id_token` via `https://oauth2.googleapis.com/token`
5. API validates token via `https://oauth2.googleapis.com/tokeninfo`, checks `aud` matches client ID
6. API finds or creates `User` (matched by Google `sub` claim)
7. API generates Lexik JWT and redirects to `ADMIN_URL?token=JWT&user=JSON`, setting the `mercureAuthorization` and httpOnly `refresh_token` cookies
8. `handleAuthCallback()` stores `token` and `user` in `localStorage`, cleans URL

## Key Files

| File | Role |
|------|------|
| `admin/src/auth/LoginPage.tsx` | Login UI — single Google button |
| `admin/src/auth/authProvider.ts` | React-Admin auth provider + `handleAuthCallback()` |
| `admin/src/auth/session.ts` | Refresh, 401 replay, renewal timer, restore at boot |
| `admin/src/App.tsx` | Runs `handleAuthCallback()` before render, injects Bearer token |
| `api/modules/core/src/Controller/GoogleAuthController.php` | Three endpoints: redirect, callback, mobile POST |
| `api/modules/core/config/services.yaml` | Binds `$googleClientId`, `$googleClientSecret`, `$googleRedirectUri`, `$adminUrl` |

## Auth Provider

```ts
// authProvider.ts — React-Admin AuthProvider
login()      → stores token + user in localStorage, starts the session keeper
logout()     → POST /api/token/invalidate, then removes token + user
checkAuth()  → expired token: tries the refresh cookie, rejects only if refused
checkError() → clears auth on a 401/403 the replay could not fix (not while offline)
getIdentity()→ returns {id, fullName, avatar} from stored user JSON
```

`handleAuthCallback()` runs **before React renders** (called at module level in `App.tsx`). Extracts `token` and `user` query params, stores them, and strips params from URL.

## HTTP Client

```ts
// App.tsx
const httpClient = (url: URL, options: HttpClientOptions = {}) => {
  const token = localStorage.getItem('token')
  if (token) {
    options.user = { authenticated: true, token: `Bearer ${token}` }
  }
  return fetchHydra(url, options)
}
```

Every API request includes `Authorization: Bearer <jwt>`. `installAuthRefresh()` wraps `window.fetch` once, so a 401 on such a request is replayed with a renewed token — dataProvider, REST hooks and agent calls without touching their call sites.

## Session renewal (MAG-37)

The 24 h access token alone logged the wall tablet out daily. The Gesdinet refresh token (30 days, **single-use**) now rides an httpOnly cookie:

| Cookie | Flags | Set by |
|--------|-------|--------|
| `refresh_token` | httpOnly, SameSite=Strict, path `/api/token`, Secure (off in e2e) | Google callback, e2e login, every refresh |
| `mercureAuthorization` | path `/.well-known/mercure` | same, and `RenewMercureCookieOnRefreshListener` on every refresh |

- `POST /api/token/refresh` (`credentials: 'include'`) returns `{token}` and rotates both cookies. `POST /api/token/invalidate` revokes the token (logout).
- **One refresh at a time**, per tab (shared promise) and across tabs (`navigator.locks`): the token is single-use, a second concurrent call would sign the loser out.
- `main.tsx` awaits `restoreSession()` before mounting: an expired token with a signed-in user is renewed at boot.
- `startSessionKeeper()` renews 10 min before expiry and on `visibilitychange`/`online`/`focus`. A tab that slept past expiry reloads after renewing, because EventSource does not reconnect after a 401.
- Network failure (fetch error, 5xx) keeps the session and retries a minute later; a 4xx clears it.
- The refresh token is still in the JSON body (`remove_token_from_body: false`) for the mobile app.

## API Endpoints

### `GET /api/auth/google/redirect`
Initiates web OAuth flow. Redirects browser to Google.

### `GET /api/auth/google/callback`
Exchanges authorization code for JWT. Redirects to admin with credentials.

Error cases redirect to admin with `auth_error` param:
- `missing_code`, `token_exchange_failed`, `no_id_token`, `invalid_token`

### `POST /api/auth/google`
Mobile flow. Accepts `{"idToken": "…"}`, returns `{token, user}`.

## User Creation

On first login, `findOrCreateUser()` creates a `User` entity:

| Field | Source | Notes |
|-------|--------|-------|
| `googleId` | `sub` claim | Unique, used for lookup |
| `email` | `email` claim | Unique |
| `name` | `name` claim (fallback: email) | Updated every login |
| `avatar` | `picture` claim | Updated every login |

## Environment Variables

| Variable | Container | Purpose |
|----------|-----------|---------|
| `GOOGLE_CLIENT_ID` | php, node | OAuth app ID |
| `GOOGLE_CLIENT_SECRET` | php | OAuth app secret |
| `GOOGLE_REDIRECT_URI` | php | Callback URL |
| `ADMIN_URL` | php | Post-auth redirect target |
| `VITE_API_URL` | node | API URL for login redirect |

## Security

- `/api/auth/*` routes are **public** (firewall `security: false`)
- All other `/api/*` routes require JWT (`ROLE_USER`)
- `/api/token/refresh` and `/api/token/invalidate` are public (firewall `token_refresh`)
- JWT TTL: 24 hours (Lexik config); refresh token TTL: 30 days, rotated on use
- Token audience validated against `GOOGLE_CLIENT_ID` to prevent token reuse from other apps

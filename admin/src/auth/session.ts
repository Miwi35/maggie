/**
 * The web session: a 24 h access token in localStorage, renewed from an
 * httpOnly refresh cookie the page cannot read (MAG-37).
 *
 * Three things keep a wall tablet signed in for weeks:
 * - `refreshSession` trades the cookie for a new access token, once at a time
 *   (single-use refresh tokens: two concurrent calls would burn the token and
 *   the loser would be signed out);
 * - `installAuthRefresh` retries a request that came back 401 with a new token;
 * - `startSessionKeeper` renews the token before it expires and when the
 *   tablet wakes up, so nothing has to fail first.
 */

const apiUrl = (import.meta.env.VITE_API_URL || 'http://localhost/api').replace(/\/$/, '')

export const REFRESH_URL = `${apiUrl}/token/refresh`
export const INVALIDATE_URL = `${apiUrl}/token/invalidate`

const REFRESH_MARGIN_MS = 10 * 60 * 1000
const RETRY_WHEN_UNREACHABLE_MS = 60 * 1000
const MAX_TIMER_MS = 2 ** 31 - 1

export type RefreshOutcome = 'refreshed' | 'rejected' | 'unreachable'

export const pageControls = {
  reload: () => window.location.reload(),
}

let unreachableSinceLastRefresh = false

export function getToken(): string | null {
  return localStorage.getItem('token')
}

function payloadOf(token: string): { exp?: number } | null {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(base64))
  } catch {
    return null
  }
}

export function tokenExpiresAt(token: string): number | null {
  const payload = payloadOf(token)
  return payload?.exp ? payload.exp * 1000 : null
}

/** A token that cannot be read counts as expired; one without `exp` never expires. */
export function isTokenExpired(token: string): boolean {
  const payload = payloadOf(token)
  if (!payload) {
    return true
  }
  return payload.exp ? payload.exp * 1000 < Date.now() : false
}

export function clearSession(): void {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
}

/**
 * True when the last renewal failed for want of a network rather than because
 * the server refused: a tablet that boots before its wifi must not forget
 * who is signed in.
 */
export function sessionAwaitsNetwork(): boolean {
  return unreachableSinceLastRefresh && !!getToken() && !!localStorage.getItem('user')
}

async function withCrossTabLock<T>(task: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return navigator.locks.request('maggie-session-refresh', task)
  }
  return task()
}

let inFlight: Promise<RefreshOutcome> | null = null

export function refreshSession(): Promise<RefreshOutcome> {
  if (!inFlight) {
    const tokenBefore = getToken()
    inFlight = withCrossTabLock(() => renew(tokenBefore)).finally(() => {
      inFlight = null
    })
  }
  return inFlight
}

async function renew(tokenBefore: string | null): Promise<RefreshOutcome> {
  // Another tab renewed while this one waited for the lock: its token is
  // already in storage and the refresh cookie it spent is gone.
  const current = getToken()
  if (current && current !== tokenBefore && !isTokenExpired(current)) {
    unreachableSinceLastRefresh = false
    scheduleRenewal()
    return 'refreshed'
  }

  let response: Response
  try {
    response = await fetch(REFRESH_URL, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
  } catch {
    unreachableSinceLastRefresh = true
    return 'unreachable'
  }

  if (response.ok) {
    try {
      const { token } = (await response.json()) as { token?: string }
      if (token) {
        localStorage.setItem('token', token)
        unreachableSinceLastRefresh = false
        scheduleRenewal()
        return 'refreshed'
      }
    } catch {
      // fall through: a 200 without a token is not a session
    }
    return 'rejected'
  }

  if (response.status >= 500) {
    unreachableSinceLastRefresh = true
    return 'unreachable'
  }

  clearSession()
  return 'rejected'
}

/** Leaves the session as is when the refresh cookie is still good but the server cannot be reached. */
export async function restoreSession(): Promise<void> {
  const token = getToken()
  if (!token || !localStorage.getItem('user') || !isTokenExpired(token)) {
    return
  }
  await refreshSession()
}

export async function endSession(): Promise<void> {
  try {
    await fetch(INVALIDATE_URL, { method: 'POST', credentials: 'include' })
  } catch {
    // Offline sign-out still signs out locally; the token expires on its own.
  }
  clearSession()
}

let renewalTimer: ReturnType<typeof setTimeout> | undefined

function scheduleRenewal(): void {
  clearTimeout(renewalTimer)
  const token = getToken()
  const expiresAt = token ? tokenExpiresAt(token) : null
  if (!expiresAt) {
    return
  }
  const delay = Math.min(Math.max(expiresAt - REFRESH_MARGIN_MS - Date.now(), 0), MAX_TIMER_MS)
  renewalTimer = setTimeout(() => void renewIfDue(), delay)
}

async function renewIfDue(): Promise<void> {
  const token = getToken()
  const expiresAt = token ? tokenExpiresAt(token) : null
  if (!token || !expiresAt) {
    return
  }
  if (expiresAt - Date.now() > REFRESH_MARGIN_MS) {
    scheduleRenewal()
    return
  }

  // Slept through the expiry: every Mercure connection died with the old
  // cookie and EventSource does not reconnect after a 401, so start over.
  const sleptThroughExpiry = expiresAt < Date.now()
  const outcome = await refreshSession()

  if (outcome === 'refreshed' && sleptThroughExpiry) {
    pageControls.reload()
  } else if (outcome === 'unreachable') {
    clearTimeout(renewalTimer)
    renewalTimer = setTimeout(() => void renewIfDue(), RETRY_WHEN_UNREACHABLE_MS)
  }
}

let keeperStarted = false

export function startSessionKeeper(): void {
  scheduleRenewal()
  if (keeperStarted) {
    return
  }
  keeperStarted = true

  // Timers are throttled or frozen while a tablet sleeps: waking up is the
  // moment to look at the clock again.
  const wake = () => {
    if (document.visibilityState !== 'hidden') {
      void renewIfDue()
    }
  }
  document.addEventListener('visibilitychange', wake)
  window.addEventListener('online', wake)
  window.addEventListener('focus', wake)
  window.addEventListener('storage', (event) => {
    if (event.key === 'token') {
      scheduleRenewal()
    }
  })
}

function requestUrl(input: RequestInfo | URL): URL | null {
  try {
    return new URL(input instanceof Request ? input.url : String(input), window.location.href)
  } catch {
    return null
  }
}

function isOwnRequest(url: URL | null): boolean {
  if (!url || url.href === REFRESH_URL || url.href === INVALIDATE_URL) {
    return false
  }
  return url.origin === window.location.origin || url.origin === new URL(apiUrl, window.location.href).origin
}

function bearerOf(input: RequestInfo | URL, init?: RequestInit): string | null {
  const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined))
  const authorization = headers.get('Authorization')
  return authorization?.startsWith('Bearer ') ? authorization.slice('Bearer '.length) : null
}

function withBearer(input: RequestInfo | URL, init: RequestInit | undefined, token: string): [RequestInfo | URL, RequestInit | undefined] {
  if (input instanceof Request) {
    const headers = new Headers(init?.headers ?? input.headers)
    headers.set('Authorization', `Bearer ${token}`)
    return [input, { ...init, headers }]
  }
  const headers = new Headers(init?.headers)
  headers.set('Authorization', `Bearer ${token}`)
  return [input, { ...init, headers }]
}

let authRefreshInstalled = false

/**
 * Replays a 401 once with a renewed token — for react-admin's data provider,
 * the REST hooks and the agent calls alike, which all go through `fetch` with
 * a Bearer header read from localStorage. Wrapping `fetch` once is what keeps
 * those thirty call sites unaware of refresh tokens.
 *
 * A request only gets a second chance if it carried a Bearer token to one of
 * our own origins, and the refresh route itself never does.
 */
export function installAuthRefresh(): void {
  if (authRefreshInstalled) {
    return
  }
  authRefreshInstalled = true

  const nativeFetch = window.fetch.bind(window)

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const replay = input instanceof Request ? input.clone() : input
    const response = await nativeFetch(input, init)

    const used = bearerOf(input, init)
    if (response.status !== 401 || !used || !isOwnRequest(requestUrl(input))) {
      return response
    }

    // A request that left with a token another one has since replaced does
    // not need a second renewal, only the new token.
    if (getToken() === used && (await refreshSession()) !== 'refreshed') {
      return response
    }
    const token = getToken()
    if (!token || token === used) {
      return response
    }

    return nativeFetch(...withBearer(replay, init, token))
  }
}

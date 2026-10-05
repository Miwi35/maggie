import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const API = 'http://localhost/api'

function jwt(exp: number): string {
  return `e30.${btoa(JSON.stringify({ exp }))}.sig`
}

const inSeconds = (seconds: number) => Math.floor(Date.now() / 1000) + seconds

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

type Handler = (url: string, init?: RequestInit) => Response | Promise<Response>

/** Stubs fetch with a routing table; returns the calls it saw. */
function stubFetch(handler: Handler) {
  const calls: Array<{ url: string; init?: RequestInit }> = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input instanceof Request ? input.url : input)
      calls.push({ url, init })
      return handler(url, init)
    }),
  )
  return calls
}

async function loadSession() {
  vi.resetModules()
  return import('./session')
}

const bearer = (init?: RequestInit) => new Headers(init?.headers).get('Authorization')

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('refreshSession', () => {
  test('trades the cookie for a new access token and keeps it', async () => {
    const calls = stubFetch(() => json({ token: jwt(inSeconds(86400)) }))
    const { refreshSession, getToken } = await loadSession()
    localStorage.setItem('token', jwt(1))

    const outcome = await refreshSession()

    expect(outcome).toBe('refreshed')
    expect(getToken()).not.toBe(jwt(1))
    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe(`${API}/token/refresh`)
    expect(calls[0].init?.method).toBe('POST')
    expect(calls[0].init?.credentials).toBe('include')
    expect(bearer(calls[0].init)).toBeNull()
  })

  test('renews once however many callers ask at the same time', async () => {
    const calls = stubFetch(() => json({ token: jwt(inSeconds(86400)) }))
    const { refreshSession } = await loadSession()

    const outcomes = await Promise.all([refreshSession(), refreshSession(), refreshSession()])

    expect(outcomes).toEqual(['refreshed', 'refreshed', 'refreshed'])
    expect(calls).toHaveLength(1)
  })

  test('signs the user out when the server refuses the refresh token', async () => {
    stubFetch(() => json({ message: 'Invalid refresh token' }, 401))
    const { refreshSession } = await loadSession()
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')

    expect(await refreshSession()).toBe('rejected')

    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })

  test.each([
    ['the network is down', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['the server fails', () => json({}, 503)],
  ])('keeps the session when %s', async (_label, respond) => {
    stubFetch(respond as Handler)
    const { refreshSession, sessionAwaitsNetwork } = await loadSession()
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')

    expect(await refreshSession()).toBe('unreachable')

    expect(localStorage.getItem('token')).toBe(jwt(1))
    expect(localStorage.getItem('user')).not.toBeNull()
    expect(sessionAwaitsNetwork()).toBe(true)
  })

  test('adopts the token another tab stored while this one waited for the lock', async () => {
    const calls = stubFetch(() => json({ token: jwt(inSeconds(86400)) }))
    const locks = {
      request: async (_name: string, task: () => Promise<unknown>) => {
        localStorage.setItem('token', jwt(inSeconds(86400) + 1))
        return task()
      },
    }
    vi.stubGlobal('navigator', { ...navigator, locks })
    const { refreshSession } = await loadSession()
    localStorage.setItem('token', jwt(1))

    expect(await refreshSession()).toBe('refreshed')

    // The refresh token is single-use: spending it again would sign this tab out.
    expect(calls).toHaveLength(0)
  })
})

describe('restoreSession', () => {
  test('renews an expired token for a user who was signed in', async () => {
    const calls = stubFetch(() => json({ token: jwt(inSeconds(86400)) }))
    const { restoreSession, isTokenExpired, getToken } = await loadSession()
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')

    await restoreSession()

    expect(calls).toHaveLength(1)
    expect(isTokenExpired(getToken()!)).toBe(false)
  })

  test.each([
    ['a valid token', () => localStorage.setItem('token', jwt(inSeconds(3600)))],
    ['no token', () => undefined],
  ])('does not call the API with %s', async (_label, setup) => {
    const calls = stubFetch(() => json({}))
    const { restoreSession } = await loadSession()
    localStorage.setItem('user', '{"id":"1"}')
    setup()

    await restoreSession()

    expect(calls).toHaveLength(0)
  })
})

describe('endSession', () => {
  test('deletes the refresh token server-side and forgets the user', async () => {
    const calls = stubFetch(() => json({ code: 200 }))
    const { endSession } = await loadSession()
    localStorage.setItem('token', jwt(inSeconds(3600)))
    localStorage.setItem('user', '{"id":"1"}')

    await endSession()

    expect(calls[0].url).toBe(`${API}/token/invalidate`)
    expect(calls[0].init?.method).toBe('POST')
    expect(calls[0].init?.credentials).toBe('include')
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })

  test('signs out locally even when the API cannot be reached', async () => {
    stubFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    const { endSession } = await loadSession()
    localStorage.setItem('token', jwt(inSeconds(3600)))
    localStorage.setItem('user', '{"id":"1"}')

    await endSession()

    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })
})

describe('installAuthRefresh', () => {
  const STALE = () => jwt(1)
  const FRESH = () => jwt(inSeconds(86400))

  /** An API that accepts only the fresh token and renews on /token/refresh. */
  function apiAcceptingOnly(fresh: string) {
    return stubFetch((url, init) => {
      if (url.endsWith('/token/refresh')) return json({ token: fresh })
      return bearer(init) === `Bearer ${fresh}` ? json({ ok: true }) : json({ message: 'Expired JWT Token' }, 401)
    })
  }

  test('replays a 401 once with the renewed token', async () => {
    const fresh = FRESH()
    const calls = apiAcceptingOnly(fresh)
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()
    localStorage.setItem('token', STALE())

    const response = await fetch(`${API}/events`, { headers: { Authorization: `Bearer ${STALE()}` } })

    expect(response.status).toBe(200)
    expect(calls.map((c) => c.url)).toEqual([`${API}/events`, `${API}/token/refresh`, `${API}/events`])
    expect(bearer(calls[2].init)).toBe(`Bearer ${fresh}`)
  })

  test('renews the agent calls too, body and method intact', async () => {
    const fresh = FRESH()
    const calls = apiAcceptingOnly(fresh)
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()
    localStorage.setItem('token', STALE())

    const response = await fetch('/agent/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${STALE()}` },
      body: JSON.stringify({ message: 'hello' }),
    })

    expect(response.status).toBe(200)
    const replay = calls[calls.length - 1]
    expect(replay.init?.method).toBe('POST')
    expect(replay.init?.body).toBe('{"message":"hello"}')
    expect(new Headers(replay.init?.headers).get('Content-Type')).toBe('application/json')
  })

  test('shares one renewal between simultaneous 401s', async () => {
    const calls = apiAcceptingOnly(FRESH())
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()
    localStorage.setItem('token', STALE())
    const headers = { Authorization: `Bearer ${STALE()}` }

    const responses = await Promise.all([
      fetch(`${API}/events`, { headers }),
      fetch(`${API}/tasks`, { headers }),
      fetch('/agent/contexts', { headers }),
    ])

    expect(responses.map((r) => r.status)).toEqual([200, 200, 200])
    expect(calls.filter((c) => c.url.endsWith('/token/refresh'))).toHaveLength(1)
  })

  test('replays with the stored token, without renewing again, when another request already did', async () => {
    const fresh = FRESH()
    const calls = apiAcceptingOnly(fresh)
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()
    localStorage.setItem('token', fresh)

    const response = await fetch(`${API}/events`, { headers: { Authorization: `Bearer ${STALE()}` } })

    expect(response.status).toBe(200)
    expect(calls.some((c) => c.url.endsWith('/token/refresh'))).toBe(false)
  })

  test('hands back the original 401 when the session cannot be renewed', async () => {
    const calls = stubFetch((url) =>
      url.endsWith('/token/refresh') ? json({ message: 'Invalid refresh token' }, 401) : json({ message: 'Expired JWT Token' }, 401),
    )
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()
    localStorage.setItem('token', STALE())

    const response = await fetch(`${API}/events`, { headers: { Authorization: `Bearer ${STALE()}` } })

    expect(response.status).toBe(401)
    expect(calls.filter((c) => c.url.endsWith('/events'))).toHaveLength(1)
  })

  test('gives a replayed request only one second chance', async () => {
    const calls = stubFetch((url) => (url.endsWith('/token/refresh') ? json({ token: FRESH() }) : json({}, 401)))
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()
    localStorage.setItem('token', STALE())

    const response = await fetch(`${API}/events`, { headers: { Authorization: `Bearer ${STALE()}` } })

    expect(response.status).toBe(401)
    expect(calls.filter((c) => c.url.endsWith('/events'))).toHaveLength(2)
    expect(calls.filter((c) => c.url.endsWith('/token/refresh'))).toHaveLength(1)
  })

  test.each([
    ['a request without a Bearer token', `${API}/events`, undefined],
    ['a request to another site', 'https://example.com/data', { headers: { Authorization: `Bearer ${jwt(1)}` } }],
    ['the refresh route itself', `${API}/token/refresh`, { headers: { Authorization: `Bearer ${jwt(1)}` } }],
  ])('leaves a 401 on %s alone', async (_label, url, init) => {
    const calls = stubFetch(() => json({}, 401))
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()
    localStorage.setItem('token', STALE())

    const response = await fetch(url, init as RequestInit | undefined)

    expect(response.status).toBe(401)
    expect(calls).toHaveLength(1)
  })

  test('passes successful responses through untouched', async () => {
    const calls = stubFetch(() => json({ ok: true }))
    const { installAuthRefresh } = await loadSession()
    installAuthRefresh()

    const response = await fetch(`${API}/events`, { headers: { Authorization: `Bearer ${FRESH()}` } })

    expect(response.status).toBe(200)
    expect(calls).toHaveLength(1)
  })
})

describe('startSessionKeeper', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
  })

  test('renews the token before it expires, then plans the next renewal', async () => {
    let issued = 0
    const calls = stubFetch(() => json({ token: jwt(Math.floor(Date.now() / 1000) + 86400 + issued++) }))
    const { startSessionKeeper } = await loadSession()
    localStorage.setItem('token', jwt(inSeconds(3600)))

    startSessionKeeper()
    await vi.advanceTimersByTimeAsync(49 * 60 * 1000)
    expect(calls).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(2 * 60 * 1000)
    expect(calls).toHaveLength(1)

    await vi.advanceTimersByTimeAsync(23 * 60 * 60 * 1000 + 51 * 60 * 1000)
    expect(calls).toHaveLength(2)
  })

  test('does nothing for a visitor who is not signed in', async () => {
    const calls = stubFetch(() => json({}))
    const { startSessionKeeper } = await loadSession()

    startSessionKeeper()
    await vi.advanceTimersByTimeAsync(48 * 60 * 60 * 1000)

    expect(calls).toHaveLength(0)
  })

  test('renews and reloads when the tablet wakes up after the token expired', async () => {
    stubFetch(() => json({ token: jwt(inSeconds(86400 * 2)) }))
    const { startSessionKeeper, pageControls } = await loadSession()
    const reload = vi.spyOn(pageControls, 'reload').mockImplementation(() => undefined)
    localStorage.setItem('token', jwt(inSeconds(3600)))
    startSessionKeeper()

    // The tablet sleeps for two days: no timer fires, then it wakes up.
    vi.setSystemTime(Date.now() + 48 * 60 * 60 * 1000)
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)

    expect(reload).toHaveBeenCalledTimes(1)
  })

  test('does not reload for a renewal made while the token was still valid', async () => {
    stubFetch(() => json({ token: jwt(inSeconds(86400)) }))
    const { startSessionKeeper, pageControls } = await loadSession()
    const reload = vi.spyOn(pageControls, 'reload').mockImplementation(() => undefined)
    localStorage.setItem('token', jwt(inSeconds(3600)))

    startSessionKeeper()
    await vi.advanceTimersByTimeAsync(55 * 60 * 1000)

    expect(reload).not.toHaveBeenCalled()
  })

  test('tries again a minute later when the network is down', async () => {
    let online = false
    const calls = stubFetch(() => (online ? json({ token: jwt(inSeconds(86400)) }) : Promise.reject(new TypeError('offline'))))
    const { startSessionKeeper, getToken } = await loadSession()
    const expiring = jwt(inSeconds(300))
    localStorage.setItem('token', expiring)
    localStorage.setItem('user', '{"id":"1"}')

    startSessionKeeper()
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toHaveLength(1)
    expect(getToken()).toBe(expiring)

    online = true
    await vi.advanceTimersByTimeAsync(60 * 1000)
    expect(calls).toHaveLength(2)
    expect(getToken()).not.toBe(expiring)
  })
})

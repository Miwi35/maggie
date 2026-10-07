import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { routeVisitorWithoutSessionToLogin } from './authProvider'

function jwt(exp: number): string {
  return `e30.${btoa(JSON.stringify({ exp }))}.sig`
}

const inOneHour = () => Math.floor(Date.now() / 1000) + 3600

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** A fresh copy of the provider and its session module, so the "offline" flag starts false. */
async function loadProvider() {
  vi.resetModules()
  return import('./authProvider')
}

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

describe('authProvider.checkAuth', () => {
  beforeEach(() => localStorage.clear())

  test('accepts a valid token without calling the API', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem('token', jwt(inOneHour()))
    const { authProvider } = await loadProvider()

    await expect(authProvider.checkAuth({})).resolves.toBeUndefined()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('renews an expired token from the refresh cookie instead of signing out', async () => {
    const fresh = jwt(inOneHour())
    vi.stubGlobal('fetch', vi.fn(async () => json({ token: fresh })))
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')
    const { authProvider } = await loadProvider()

    await expect(authProvider.checkAuth({})).resolves.toBeUndefined()

    expect(localStorage.getItem('token')).toBe(fresh)
  })

  test('signs out when the refresh cookie is refused', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ message: 'Invalid refresh token' }, 401)))
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')
    const { authProvider } = await loadProvider()

    await expect(authProvider.checkAuth({})).rejects.toThrow('Not authenticated')

    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })

  test('stays signed in while the network is down', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')
    const { authProvider } = await loadProvider()

    await expect(authProvider.checkAuth({})).resolves.toBeUndefined()

    expect(localStorage.getItem('user')).not.toBeNull()
  })

  test('rejects a visitor who was never signed in without calling the API', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { authProvider } = await loadProvider()

    await expect(authProvider.checkAuth({})).rejects.toThrow('Not authenticated')

    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('authProvider.checkError', () => {
  beforeEach(() => localStorage.clear())

  test.each([401, 403])('signs out on a %i the retry could not fix', async (status) => {
    localStorage.setItem('token', jwt(inOneHour()))
    localStorage.setItem('user', '{"id":"1"}')
    const { authProvider } = await loadProvider()

    await expect(authProvider.checkError({ status })).rejects.toThrow('Session expired')

    expect(localStorage.getItem('token')).toBeNull()
  })

  test('ignores other errors', async () => {
    localStorage.setItem('token', jwt(inOneHour()))
    const { authProvider } = await loadProvider()

    await expect(authProvider.checkError({ status: 500 })).resolves.toBeUndefined()

    expect(localStorage.getItem('token')).not.toBeNull()
  })

  test('keeps the session on a 401 received while the network is down', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')
    const { authProvider } = await loadProvider()
    await authProvider.checkAuth({})

    await expect(authProvider.checkError({ status: 401 })).resolves.toBeUndefined()

    expect(localStorage.getItem('token')).not.toBeNull()
  })
})

describe('authProvider.logout', () => {
  test('revokes the refresh token and forgets the session', async () => {
    const calledUrls: string[] = []
    vi.stubGlobal('fetch', async (url: RequestInfo | URL) => {
      calledUrls.push(String(url))
      return json({ code: 200 })
    })
    localStorage.setItem('token', jwt(inOneHour()))
    localStorage.setItem('user', '{"id":"1"}')
    const { authProvider } = await loadProvider()

    await authProvider.logout({})

    expect(calledUrls).toHaveLength(1)
    expect(calledUrls[0]).toMatch(/\/token\/invalidate$/)
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })
})

describe('routeVisitorWithoutSessionToLogin while offline', () => {
  test('keeps an expired session whose renewal failed for want of a network', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))))
    window.history.replaceState({}, '', '/admin/#/events')
    localStorage.setItem('token', jwt(1))
    localStorage.setItem('user', '{"id":"1"}')
    vi.resetModules()
    const { restoreSession } = await import('./session')
    const { routeVisitorWithoutSessionToLogin: route } = await import('./authProvider')
    await restoreSession()

    route()

    expect(window.location.hash).toBe('#/events')
    expect(localStorage.getItem('token')).not.toBeNull()
  })
})

describe('routeVisitorWithoutSessionToLogin', () => {
  beforeEach(() => {
    localStorage.clear()
    window.history.replaceState({}, '', '/admin/#/')
  })

  test('sends a visitor with no token to the login page', () => {
    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/login')
    expect(window.location.pathname).toBe('/admin/')
  })

  test.each([
    ['expired', jwt(1)],
    ['malformed', 'not-a-jwt'],
  ])('clears an %s token and sends the visitor to the login page', (_label, token) => {
    localStorage.setItem('token', token)
    localStorage.setItem('user', '{"id":"1"}')

    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/login')
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })

  test('leaves a valid session on the requested page', () => {
    localStorage.setItem('token', jwt(inOneHour()))
    window.history.replaceState({}, '', '/admin/#/events')

    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/events')
    expect(localStorage.getItem('token')).not.toBeNull()
  })

  test('does not rewrite the URL when already on the login page', () => {
    window.history.replaceState({}, '', '/admin/#/login')
    const before = window.history.length

    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/login')
    expect(window.history.length).toBe(before)
  })
})

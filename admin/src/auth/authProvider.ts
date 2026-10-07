import type { AuthProvider } from 'react-admin'
import {
  clearSession,
  endSession,
  getToken,
  isTokenExpired,
  refreshSession,
  sessionAwaitsNetwork,
  startSessionKeeper,
} from './session'

export const authProvider: AuthProvider = {
  login: async ({ token, user }: { token: string; user: string }) => {
    localStorage.setItem('token', token)
    localStorage.setItem('user', user)
    startSessionKeeper()
  },

  logout: async () => {
    await endSession()
  },

  checkAuth: async () => {
    const token = getToken()
    if (token && !isTokenExpired(token)) {
      return
    }
    // An expired token is not a signed-out user: the refresh cookie may still
    // be good, and a tablet that slept past 24 h must wake up signed in.
    const outcome = token || localStorage.getItem('user') ? await refreshSession() : 'rejected'
    if (outcome === 'refreshed' || (outcome === 'unreachable' && sessionAwaitsNetwork())) {
      return
    }
    clearSession()
    throw new Error('Not authenticated')
  },

  checkError: async (error: { status?: number; statusCode?: number }) => {
    const status = error.status ?? error.statusCode
    if (status === 401 || status === 403) {
      // The request was already replayed with a renewed token if one could be
      // had. Without a network there is nothing to conclude about the session.
      if (status === 401 && sessionAwaitsNetwork()) {
        return
      }
      clearSession()
      throw new Error('Session expired')
    }
  },

  getIdentity: async () => {
    const userStr = localStorage.getItem('user')
    if (!userStr) {
      throw new Error('Not authenticated')
    }
    const user = JSON.parse(userStr)
    return {
      id: user.id,
      fullName: user.name,
      avatar: user.avatar,
    }
  },

  getPermissions: async () => [],
}

/**
 * Start a visitor with no valid session on the login page.
 *
 * Left to `requireAuth`, react-admin mounts its auth gate on the first route,
 * which logs out, clears the query cache, re-runs the failing auth check and
 * logs out again. That loop starves the router for 7 to 25 s, during which the
 * page stays blank. Call this before the router reads the location.
 */
export function routeVisitorWithoutSessionToLogin(): void {
  const token = getToken()
  if (token && (!isTokenExpired(token) || sessionAwaitsNetwork())) {
    return
  }
  clearSession()
  if (window.location.hash.startsWith('#/login')) {
    return
  }
  window.history.replaceState(
    {},
    '',
    `${window.location.pathname}${window.location.search}#/login`,
  )
}

/**
 * Check URL for auth callback params and store them.
 * Call this on app init.
 */
export function handleAuthCallback(): boolean {
  const params = new URLSearchParams(window.location.search)
  const token = params.get('token')
  const user = params.get('user')

  if (token && user) {
    localStorage.setItem('token', token)
    localStorage.setItem('user', user)
    // Clean URL
    window.history.replaceState({}, '', window.location.pathname)
    return true
  }

  const authError = params.get('auth_error')
  if (authError) {
    console.error('Auth error:', authError)
    window.history.replaceState({}, '', window.location.pathname)
  }

  return false
}

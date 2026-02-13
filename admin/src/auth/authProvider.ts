import type { AuthProvider } from 'react-admin'

export const authProvider: AuthProvider = {
  login: async ({ token, user }: { token: string; user: string }) => {
    localStorage.setItem('token', token)
    localStorage.setItem('user', user)
  },

  logout: async () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  },

  checkAuth: async () => {
    if (!localStorage.getItem('token')) {
      throw new Error('Not authenticated')
    }
  },

  checkError: async (error: { status: number }) => {
    if (error.status === 401 || error.status === 403) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
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

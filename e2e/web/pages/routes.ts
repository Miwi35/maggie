/**
 * Where the admin lives, in one place.
 *
 * It is served under `/admin` (Vite `base`), and react-admin routes with a
 * hash router — so a deep link is `/admin/#/settings/preferences`. Both halves
 * are the kind of thing a build change moves, so every journey builds its URLs
 * here rather than spelling them out.
 */

export const ADMIN_BASE = '/admin'

export function adminUrl(route = '/'): string {
  const normalised = route.startsWith('/') ? route : `/${route}`

  return `${ADMIN_BASE}/#${normalised}`
}

export const ROUTES = {
  dashboard: '/',
  login: '/login',
  calendar: '/calendar',
  tasks: '/tasks',
  events: '/events',
  grocery: '/grocery',
  recipes: '/recipes',
  preferences: '/settings/preferences',
} as const

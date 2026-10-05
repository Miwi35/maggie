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
  products: '/products',
  recipes: '/recipes',
  preferences: '/settings/preferences',
  // Finance (MAG-102). Four screens are react-admin resources and four are
  // custom routes; nothing on screen says which, so they sit together here.
  accounts: '/accounts',
  categories: '/categories',
  envelopes: '/envelopes',
  loans: '/loans',
  financeOverview: '/finance/dashboard',
  financeBanks: '/finance/banks',
  financeCushion: '/finance/cushion',
  financeReview: '/finance/monthly-review',
} as const

/**
 * The transactions of one account — the only way the admin lists them.
 *
 * `AccountTransactionsView` takes the bare ULID in the path and rebuilds the
 * IRI itself, so pass the id, not `/api/accounts/<id>`.
 */
export function accountTransactionsRoute(accountId: string): string {
  return `/accounts/${accountId}/transactions`
}

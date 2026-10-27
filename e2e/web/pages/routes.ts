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
  home: '/',
  login: '/login',
  calendar: '/calendar',
  tasks: '/tasks',
  events: '/events',
  grocery: '/grocery',
  pastErrands: '/errands',
  products: '/products',
  stores: '/stores',
  recipes: '/recipes',
  meals: '/meals',
  ingredients: '/ingredients',
  preferences: '/settings/preferences',
  taskSettings: '/settings/tasks',
  taskOrderSettings: '/settings/tasks/order',
  mealSettings: '/settings/meals',
  googleSettings: '/settings/google',
  agentSettings: '/settings/agent',
  // Finance (MAG-102). Four screens are react-admin resources and the others are
  // custom routes; nothing on screen says which, so they sit together here.
  accounts: '/accounts',
  categories: '/categories',
  envelopes: '/envelopes',
  loans: '/loans',
  contacts: '/contacts',
  financeOverview: '/finance/dashboard',
  financeMonthBalance: '/finance/dashboard/current',
  financeBanks: '/finance/banks',
  financeImport: '/finance/import',
  financePeerDebts: '/finance/peer-debts',
  financeCushion: '/finance/cushion',
  financeTreasury: '/finance/treasury',
  financeProjects: '/finance/projects',
  // The Placements tab of Planification (MAG-555, MAG-633).
  financePlanningPlacements: '/finance/summary/planning/placements',
  // The Indépendance tab of Planification, where the 20-year frise sits (MAG-554, MAG-633).
  financePlanningIndependence: '/finance/summary/planning/independence',
  financeReview: '/finance/review',
  // The review as a full-screen activity, out of the layout (MAG-456).
  financeReviewActivity: '/finance/review/activity',
  financeRecurring: '/finance/recurring-operations',
  financeSavingsAdvances: '/finance/savings-advances',
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

/**
 * A contact's page, the « fiche contact » (MAG-450). React-admin addresses the
 * record by its IRI, encoded into one path segment.
 */
export function contactRoute(contactId: string): string {
  const iri = contactId.startsWith('/') ? contactId : `/api/contacts/${contactId}`

  return `${ROUTES.contacts}/${encodeURIComponent(iri)}`
}

/**
 * A transaction's sheet (MAG-464), where a spend is shared (MAG-451). Like a
 * contact, react-admin addresses it by its IRI, encoded into one path segment.
 */
export function transactionRoute(transactionId: string): string {
  const iri = transactionId.startsWith('/') ? transactionId : `/api/transactions/${transactionId}`

  return `/transactions/${encodeURIComponent(iri)}`
}

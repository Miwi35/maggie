/**
 * Where each page of the admin sits: module › part › item › action (MAG-352).
 *
 * The single table the breadcrumb is built from — no page writes its own
 * trail. Modules follow `agent-os/standards/global/ux.md` §1 and the menu's
 * wording; a part is a list (or a custom page), reached from its module.
 */

export interface NavPart {
  label: string
  path: string
  /** The API Platform resource behind the part: it has `create`, `:id` and `:id/show` routes. */
  resource?: string
}

export interface NavModule {
  label: string
  /** The module's dashboard, when it has one: its page is the module itself. */
  dashboard?: string
  parts: NavPart[]
}

const resourcePart = (label: string, resource: string): NavPart => ({
  label,
  path: `/${resource}`,
  resource,
})

export const NAVIGATION: NavModule[] = [
  {
    label: 'Agenda',
    dashboard: '/calendar',
    parts: [
      resourcePart('Agendas', 'agendas'),
      resourcePart('Événements', 'events'),
      resourcePart('Tâches', 'tasks'),
    ],
  },
  {
    label: 'Courses',
    dashboard: '/grocery',
    parts: [
      resourcePart('Produits', 'products'),
      resourcePart('Magasins', 'stores'),
      resourcePart('Articles récurrents', 'recurring_grocery_items'),
    ],
  },
  {
    label: 'Cuisine',
    parts: [
      { label: 'Repas de la semaine', path: '/meals' },
      { label: 'Planning des repas', path: '/meals/calendar' },
      resourcePart('Recettes', 'recipes'),
      resourcePart('Ingrédients', 'ingredients'),
    ],
  },
  {
    label: 'Finance',
    dashboard: '/finance/dashboard',
    parts: [
      { label: 'Banques', path: '/finance/banks' },
      { label: 'Import de relevé', path: '/finance/import' },
      resourcePart('Comptes', 'accounts'),
      resourcePart('Transactions', 'transactions'),
      resourcePart('Catégories', 'categories'),
      resourcePart('Budgets', 'envelopes'),
      { label: 'Matelas', path: '/finance/cushion' },
      resourcePart('Prêts', 'loans'),
      { label: 'Revue mensuelle', path: '/finance/monthly-review' },
      resourcePart('Règles de catégorisation', 'categorization_rules'),
    ],
  },
  {
    label: 'Paramètres',
    parts: [
      { label: 'Préférences', path: '/settings/preferences' },
      { label: 'Agent', path: '/settings/agent' },
      { label: 'Google', path: '/settings/google' },
    ],
  },
]

export const HOME_LABEL = 'Accueil'
export const SEARCH_LABEL = 'Recherche'
export const NEW_LABEL = 'Nouveau'
export const EDIT_LABEL = 'Modifier'
export const FALLBACK_ITEM_LABEL = 'Détail'

/** The fields a record is named by, first one filled wins. */
export const NAME_FIELDS = ['summary', 'name', 'label', 'title'] as const

export const recordName = (record: Record<string, unknown> | undefined): string => {
  for (const field of NAME_FIELDS) {
    const value = record?.[field]
    if (typeof value === 'string' && value.trim() !== '') {
      return value
    }
  }
  return FALLBACK_ITEM_LABEL
}

export interface TrailCrumb {
  /** Absent while the record that names it is still loading. */
  label?: string
  to: string
  /** The record whose name is this crumb's label. */
  record?: { resource: string; id: string }
}

const moduleHome = (module: NavModule): string => module.dashboard ?? module.parts[0].path

const moduleCrumb = (module: NavModule): TrailCrumb => ({
  label: module.label,
  to: moduleHome(module),
})

const partCrumb = (part: NavPart): TrailCrumb => ({ label: part.label, to: part.path })

const decode = (segment: string): string => {
  try {
    return decodeURIComponent(segment)
  } catch {
    return segment
  }
}

const finance = (): NavModule => NAVIGATION.find((m) => m.label === 'Finance') as NavModule

/**
 * The trail for a pathname, last crumb included (the caller drops its link).
 * An empty array means the table does not know the page.
 */
export function resolveTrail(pathname: string): TrailCrumb[] {
  const path = pathname.replace(/\/+$/, '') || '/'

  if (path === '/') {
    return [{ label: HOME_LABEL, to: '/' }]
  }
  if (path === '/search') {
    return [{ label: SEARCH_LABEL, to: path }]
  }

  // Transactions are reached through their account, not from a list of their own.
  const accountTransactions = /^\/accounts\/([^/]+)\/transactions$/.exec(path)
  if (accountTransactions) {
    const module = finance()
    const accounts = module.parts.find((p) => p.resource === 'accounts') as NavPart
    return [
      moduleCrumb(module),
      partCrumb(accounts),
      { to: path, record: { resource: 'accounts', id: `/api/accounts/${accountTransactions[1]}` } },
    ]
  }

  for (const module of NAVIGATION) {
    if (module.dashboard === path) {
      return [moduleCrumb(module)]
    }

    for (const part of module.parts) {
      const head = [moduleCrumb(module), partCrumb(part)]

      if (part.path === path) {
        return head
      }
      if (!part.resource) {
        continue
      }

      const detail = new RegExp(`^${part.path}/([^/]+)(/show)?$`).exec(path)
      if (!detail) {
        continue
      }
      if (detail[1] === 'create' && !detail[2]) {
        return [...head, { label: NEW_LABEL, to: path }]
      }

      const record = { resource: part.resource, id: decode(detail[1]) }
      const item: TrailCrumb = { to: `${part.path}/${detail[1]}/show`, record }
      return detail[2]
        ? [...head, item]
        : [...head, item, { label: EDIT_LABEL, to: path }]
    }
  }

  return []
}

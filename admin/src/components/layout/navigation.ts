/**
 * Where each page of the admin sits: module › part › item › action (MAG-352).
 *
 * The single table the breadcrumb is built from — no page writes its own
 * trail. Modules follow `agent-os/standards/global/ux.md` §1 and the menu's
 * wording; a part is a list (or a custom page), reached from its module.
 */

import { DEFAULT_AGENT_NAME } from '../../hooks/useAgentName'

export interface NavPart {
  label: string
  path: string
  /** The API Platform resource behind the part: it has `create`, `:id` and `:id/show` routes. */
  resource?: string
  /** The menu the part is reached from, when the module has menus: one more level in the trail. */
  menu?: { label: string; path: string }
  /** Not an entry of the sidebar: the part is reached from a page (« Courses passées » from the list), its trail and routes stay. */
  reachedFromPage?: boolean
}

/**
 * One aspect of a menu (MAG-427, MAG-518): a tab of the menu's page, whose body
 * is the aspect's own content. `path` is the standalone screen the same content
 * is also reached from (a record's trail, a redirect after saving); no `path`
 * when the aspect has no screen outside its tab.
 */
export interface NavAspect {
  id: string
  label: string
  path?: string
  /** The API Platform resource behind the screen, when it is one. */
  resource?: string
}

/** A menu of the sidebar: it opens a page of one tab per aspect, never a list of links. */
export interface NavMenu {
  key: string
  label: string
  path: string
  aspects: NavAspect[]
}

export interface NavModule {
  label: string
  /** The module's dashboard, when it has one: its page is the module itself. */
  dashboard?: string
  /** What the sidebar calls the dashboard when the module has other entries under it (« Liste »): the trail of the dashboard then reads module › that label. */
  dashboardLabel?: string
  /** The sidebar's entries under the module, when it has more than a flat list of parts. */
  menus?: NavMenu[]
  parts: NavPart[]
}

/** The page is labelled with the name the user gave the agent (`useAgentName`); this is the default. */
export const AGENT_SETTINGS_PATH = '/settings/agent'

const resourcePart = (label: string, resource: string): NavPart => ({
  label,
  path: `/${resource}`,
  resource,
})

export const SETTINGS_LABEL = 'Réglages'

export const FINANCE_DASHBOARD_PATH = '/finance/dashboard'

export const financeSummaryPath = (key: string): string => `/finance/summary/${key}`

/** The menu's page opened on one aspect: the tab lives in the path, so a link or the back button lands on it. */
export const financeAspectPath = (menuKey: string, aspectId: string): string =>
  `${financeSummaryPath(menuKey)}/${aspectId}`

/**
 * The seven Finance menus (owner, 15 Oct.), in the sidebar's order. The
 * Dashboard is the module's home and has no aspects; each of the others opens
 * its page at `/finance/summary/<key>`, one tab per aspect. Every Finance screen sits under
 * exactly one aspect — transactions are reached through their account.
 */
export const FINANCE_MENUS: NavMenu[] = [
  { key: 'dashboard', label: 'Dashboard', path: FINANCE_DASHBOARD_PATH, aspects: [] },
  {
    key: 'accounts',
    label: 'Comptes',
    path: financeSummaryPath('accounts'),
    aspects: [
      { id: 'accounts', label: 'Comptes et transactions', path: '/accounts', resource: 'accounts' },
      { id: 'import', label: 'Import de relevés', path: '/finance/import' },
      { id: 'contacts', label: 'Contacts', path: '/contacts', resource: 'contacts' },
    ],
  },
  {
    key: 'review',
    label: 'Revue',
    path: financeSummaryPath('review'),
    aspects: [
      { id: 'review', label: 'Transactions', path: '/finance/review' },
      { id: 'rules', label: 'Règles de catégorisation', path: '/categorization_rules', resource: 'categorization_rules' },
      { id: 'recurring', label: 'Opérations récurrentes', path: '/finance/recurring-operations' },
    ],
  },
  {
    key: 'budget',
    label: 'Budget',
    path: financeSummaryPath('budget'),
    aspects: [{ id: 'envelopes', label: 'Enveloppes', path: '/envelopes', resource: 'envelopes' }],
  },
  {
    key: 'treasury',
    label: 'Trésorerie',
    path: financeSummaryPath('treasury'),
    aspects: [
      { id: 'cushion', label: 'Matelas', path: '/finance/cushion' },
      { id: 'advances', label: "Avances d'épargne", path: '/finance/savings-advances' },
      { id: 'peer-debts', label: 'Créances et dettes', path: '/finance/peer-debts' },
      { id: 'outlook', label: 'Solde projeté', path: '/finance/treasury' },
    ],
  },
  {
    key: 'planning',
    label: 'Planification',
    path: financeSummaryPath('planning'),
    aspects: [
      { id: 'projects', label: 'Projets', path: '/finance/projects' },
      { id: 'loans', label: 'Prêts', path: '/loans', resource: 'loans' },
      { id: 'placements', label: 'Placements' },
      { id: 'independence', label: 'Indépendance' },
    ],
  },
  {
    key: 'settings',
    label: 'Configuration',
    path: financeSummaryPath('settings'),
    aspects: [
      { id: 'banks', label: 'Banques', path: '/finance/banks' },
      { id: 'categories', label: 'Catégories', path: '/categories', resource: 'categories' },
    ],
  },
]

const financeParts = (): NavPart[] => [
  ...FINANCE_MENUS.flatMap((menu) =>
    menu.aspects.flatMap((aspect): NavPart[] =>
      aspect.path
        ? [{ label: aspect.label, path: aspect.path, resource: aspect.resource, menu: { label: menu.label, path: menu.path } }]
        : [],
    ),
  ),
  resourcePart('Transactions', 'transactions'),
]

/**
 * The modules of the mockup (MAG-620, `charte/Admin-Cible`), in the sidebar's
 * order, Réglages last. Santé and Loisirs join the table with their modules.
 */
export const NAVIGATION: NavModule[] = [
  {
    label: 'Agenda',
    dashboard: '/calendar',
    parts: [],
  },
  {
    label: 'Tâches',
    dashboard: '/tasks',
    parts: [resourcePart('Tâches', 'tasks')],
  },
  {
    label: 'Courses',
    dashboard: '/grocery',
    dashboardLabel: 'Liste',
    parts: [
      resourcePart('Produits', 'products'),
      resourcePart('Magasins', 'stores'),
      { ...resourcePart('Courses passées', 'errands'), reachedFromPage: true },
    ],
  },
  {
    label: 'Cuisine',
    parts: [
      { label: 'Repas', path: '/meals' },
      resourcePart('Recettes', 'recipes'),
    ],
  },
  {
    label: 'Finance',
    dashboard: FINANCE_DASHBOARD_PATH,
    menus: FINANCE_MENUS,
    parts: financeParts(),
  },
  {
    label: SETTINGS_LABEL,
    parts: [
      { label: 'Préférences', path: '/settings/preferences' },
      { label: 'Tâches', path: '/settings/tasks' },
      { label: 'Ce qui passe en premier', path: '/settings/tasks/order' },
      { label: 'Repas', path: '/settings/meals' },
      { label: DEFAULT_AGENT_NAME, path: AGENT_SETTINGS_PATH },
      { label: 'Google', path: '/settings/google' },
      // The raw lists (« Données brutes » of before) live in Réglages: the
      // records Maggie keeps, edited by hand, are settings of the app.
      resourcePart('Agendas', 'agendas'),
      resourcePart('Événements', 'events'),
      resourcePart('Ingrédients', 'ingredients'),
    ],
  },
]

export const HOME_LABEL = 'Accueil'
export const SEARCH_LABEL = 'Recherche'
export const NEW_LABEL = 'Nouveau'
export const EDIT_LABEL = 'Modifier'
export const CURRENT_STATE_LABEL = 'État courant'
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

/**
 * A part's crumbs after the module: its menu, when it has one, then itself —
 * unless the part is the module's own page (« Tâches » of Tâches): one crumb.
 */
const partHead = (module: NavModule, part: NavPart): TrailCrumb[] =>
  part.path === module.dashboard
    ? [moduleCrumb(module)]
    : [
        moduleCrumb(module),
        ...(part.menu ? [{ label: part.menu.label, to: part.menu.path }] : []),
        partCrumb(part),
      ]

/** The Finance menu a page belongs to, so the sidebar can highlight it. */
export function financeMenuOf(pathname: string): string | undefined {
  const trail = resolveTrail(pathname)
  if (trail[0]?.label !== 'Finance') {
    return undefined
  }
  if (trail.length === 1) {
    return 'dashboard'
  }

  return FINANCE_MENUS.find((menu) => menu.path === trail[1].to)?.key
}

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
      ...partHead(module, accounts),
      { to: path, record: { resource: 'accounts', id: `/api/accounts/${accountTransactions[1]}` } },
    ]
  }

  for (const module of NAVIGATION) {
    if (module.dashboard === path) {
      return module.dashboardLabel
        ? [moduleCrumb(module), { label: module.dashboardLabel, to: path }]
        : [moduleCrumb(module)]
    }
    if (module.dashboard && path === `${module.dashboard}/current`) {
      return [moduleCrumb(module), { label: CURRENT_STATE_LABEL, to: path }]
    }

    const menu = module.menus?.find((candidate) => candidate.path === path)
    if (menu) {
      return [moduleCrumb(module), { label: menu.label, to: menu.path }]
    }

    for (const candidate of module.menus ?? []) {
      const aspect = candidate.aspects.find((entry) => financeAspectPath(candidate.key, entry.id) === path)
      if (aspect) {
        return [moduleCrumb(module), { label: candidate.label, to: candidate.path }, { label: aspect.label, to: path }]
      }
    }

    for (const part of module.parts) {
      const head = partHead(module, part)

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

export interface SidebarEntry {
  key: string
  label: string
  path: string
}

export interface SidebarModule {
  label: string
  path: string
  /** Shown only while the page belongs to the module. */
  entries: SidebarEntry[]
}

const sidebarEntries = (module: NavModule): SidebarEntry[] => {
  if (module.menus) {
    return module.menus.map((menu) => ({ key: menu.key, label: menu.label, path: menu.path }))
  }

  const entries = [
    ...(module.dashboard ? [{ key: 'home', label: module.dashboardLabel ?? module.label, path: module.dashboard }] : []),
    ...module.parts
      .filter((part) => part.path !== module.dashboard && !part.reachedFromPage)
      .map((part) => ({ key: part.path, label: part.label, path: part.path })),
  ]

  // A module that is a single page has nothing to list under it.
  return entries.length > 1 ? entries : []
}

/** The sidebar, in the mockup's order: Accueil, the modules, Réglages last. */
export const SIDEBAR: SidebarModule[] = [
  { label: HOME_LABEL, path: '/', entries: [] },
  ...NAVIGATION.map((module) => ({ label: module.label, path: moduleHome(module), entries: sidebarEntries(module) })),
]

/** The sidebar module a page belongs to, so the menu can open it. */
export function sidebarModuleOf(pathname: string): string | undefined {
  const [first] = resolveTrail(pathname)

  return SIDEBAR.find((module) => module.label === first?.label)?.label
}

/** The path of the sidebar entry the page sits under: the deepest crumb that is an entry. */
export function sidebarEntryOf(pathname: string): string | undefined {
  const label = sidebarModuleOf(pathname)
  const module = SIDEBAR.find((candidate) => candidate.label === label)
  if (!module) {
    return undefined
  }

  const paths = new Set(module.entries.map((entry) => entry.path))

  return resolveTrail(pathname)
    .map((crumb) => crumb.to)
    .reverse()
    .find((to) => paths.has(to))
}

import { describe, expect, test } from 'vitest'
import {
  FINANCE_DASHBOARD_PATH,
  FINANCE_MENUS,
  NAVIGATION,
  financeAspectPath,
  financeMenuOf,
  financeSummaryPath,
  SIDEBAR,
  resolveTrail,
  sidebarEntryOf,
  sidebarModuleOf,
} from './navigation'

const labels = (path: string) => resolveTrail(path).map((crumb) => crumb.label)

describe('the seven Finance menus', () => {
  test('are, in order, Dashboard, Comptes, Revue, Budget, Trésorerie, Planification, Configuration', () => {
    expect(FINANCE_MENUS.map((menu) => menu.label)).toEqual([
      'Dashboard',
      'Comptes',
      'Revue',
      'Budget',
      'Trésorerie',
      'Planification',
      'Configuration',
    ])
    expect(FINANCE_MENUS.map((menu) => menu.key)).toEqual([
      'dashboard',
      'accounts',
      'review',
      'budget',
      'treasury',
      'planning',
      'settings',
    ])
  })

  test('open the dashboard, or a summary page of their own', () => {
    const [dashboard, ...others] = FINANCE_MENUS

    expect(dashboard.path).toBe(FINANCE_DASHBOARD_PATH)
    for (const menu of others) {
      expect(menu.path).toBe(financeSummaryPath(menu.key))
    }
  })

  test('have no empty menu: every menu but the dashboard has an aspect', () => {
    for (const menu of FINANCE_MENUS.slice(1)) {
      expect(menu.aspects.length, menu.label).toBeGreaterThan(0)
    }
  })

  test('hold every aspect once, and no two aspects share a screen', () => {
    const aspects = FINANCE_MENUS.flatMap((menu) => menu.aspects)
    const ids = aspects.map((aspect) => aspect.id)
    const paths = aspects.flatMap((aspect) => (aspect.path ? [aspect.path] : []))

    expect(new Set(ids).size).toBe(ids.length)
    expect(new Set(paths).size).toBe(paths.length)
  })

  test('keep every Finance screen of before reachable from exactly one menu', () => {
    const reached = FINANCE_MENUS.flatMap((menu) => menu.aspects.flatMap((aspect) => (aspect.path ? [aspect.path] : [])))

    expect(reached.sort()).toEqual(
      [
        '/accounts',
        '/categories',
        '/categorization_rules',
        '/contacts',
        '/envelopes',
        '/finance/banks',
        '/finance/cushion',
        '/finance/import',
        '/finance/peer-debts',
        '/finance/projects',
        '/finance/recurring-operations',
        '/finance/savings-advances',
        '/finance/review',
        '/finance/treasury',
        '/loans',
      ].sort(),
    )
  })

  test('put Trésorerie › Matelas and Configuration › Banques where the owner expects them', () => {
    expect(labels('/finance/cushion')).toEqual(['Finance', 'Trésorerie', 'Matelas'])
    expect(labels('/finance/banks')).toEqual(['Finance', 'Configuration', 'Banques'])
  })

  test('open Planification on Projets, in the mock-ups\' single row: Projets · Prêts · Placements · Indépendance (MAG-519, MAG-633)', () => {
    const planning = FINANCE_MENUS.find((menu) => menu.key === 'planning')

    expect(planning?.aspects.map((aspect) => aspect.id)).toEqual(['projects', 'loans', 'placements', 'independence'])
    expect(labels('/finance/projects')).toEqual(['Finance', 'Planification', 'Projets'])
  })
})

describe('resolveTrail on the Finance menus', () => {
  test('a menu page is Finance › menu', () => {
    expect(labels('/finance/summary/planning')).toEqual(['Finance', 'Planification'])
  })

  test('an unknown menu has no trail', () => {
    expect(resolveTrail('/finance/summary/nowhere')).toEqual([])
    expect(resolveTrail('/finance/summary/planning/nowhere')).toEqual([])
  })

  test('a tab of a menu is Finance › menu › aspect, and the sidebar keeps the menu lit', () => {
    expect(financeAspectPath('planning', 'independence')).toBe('/finance/summary/planning/independence')
    expect(labels('/finance/summary/planning/independence')).toEqual(['Finance', 'Planification', 'Indépendance'])
    expect(resolveTrail('/finance/summary/planning/loans')[1].to).toBe('/finance/summary/planning')
    expect(financeMenuOf('/finance/summary/treasury/cushion')).toBe('treasury')
  })

  test('every aspect of every menu has a tab address with a trail', () => {
    for (const menu of FINANCE_MENUS) {
      for (const aspect of menu.aspects) {
        expect(labels(financeAspectPath(menu.key, aspect.id))).toEqual(['Finance', menu.label, aspect.label])
      }
    }
  })

  test('the detail of a record keeps its menu', () => {
    expect(labels('/loans/abc/show')).toEqual(['Finance', 'Planification', 'Prêts', undefined])
    expect(labels('/envelopes/create')).toEqual(['Finance', 'Budget', 'Enveloppes', 'Nouveau'])
  })

  test('the transactions of an account go through Comptes', () => {
    expect(labels('/accounts/01ABC/transactions')).toEqual([
      'Finance',
      'Comptes',
      'Comptes et transactions',
      undefined,
    ])
  })

  test('every part of a module without menus keeps its two levels', () => {
    for (const module of NAVIGATION.filter((candidate) => !candidate.menus)) {
      for (const part of module.parts.filter((candidate) => candidate.path !== module.dashboard)) {
        expect(labels(part.path)).toEqual([module.label, part.label])
      }
    }
  })

  test('a part that is the page of its module has one level: Tâches', () => {
    expect(labels('/tasks')).toEqual(['Tâches'])
    expect(labels('/tasks/create')).toEqual(['Tâches', 'Nouveau'])
    expect(labels('/tasks/abc/show')).toEqual(['Tâches', undefined])
  })

  test('the raw lists sit under Réglages', () => {
    expect(labels('/agendas')).toEqual(['Réglages', 'Agendas'])
    expect(labels('/events/create')).toEqual(['Réglages', 'Événements', 'Nouveau'])
    expect(labels('/ingredients')).toEqual(['Réglages', 'Ingrédients'])
  })
})

describe('financeMenuOf', () => {
  test.each([
    ['/finance/dashboard', 'dashboard'],
    ['/finance/summary/review', 'review'],
    ['/finance/cushion', 'treasury'],
    ['/finance/treasury', 'treasury'],
    ['/loans/abc/show', 'planning'],
    ['/finance/projects', 'planning'],
    ['/accounts/01ABC/transactions', 'accounts'],
    ['/categories', 'settings'],
  ])('%s highlights %s', (path, key) => {
    expect(financeMenuOf(path)).toBe(key)
  })

  test.each(['/', '/calendar', '/transactions', '/finance/summary/nowhere'])('%s highlights no Finance menu', (path) => {
    expect(financeMenuOf(path)).toBeUndefined()
  })
})

describe('the sidebar table', () => {
  test('lists Accueil · Agenda · Tâches · Courses · Cuisine · Finance, Réglages last', () => {
    expect(SIDEBAR.map((module) => module.label)).toEqual([
      'Accueil',
      'Agenda',
      'Tâches',
      'Courses',
      'Cuisine',
      'Finance',
      'Réglages',
    ])
  })

  test('has no old name at the first level', () => {
    const labelsOnTop = SIDEBAR.map((module) => module.label)
    for (const old of ['Chat', 'Calendrier', 'Paramètres', 'Nutrition', 'Données brutes']) {
      expect(labelsOnTop).not.toContain(old)
    }
  })

  test('opens the module of the page, and the entry it sits under', () => {
    expect(sidebarModuleOf('/')).toBe('Accueil')
    expect(sidebarModuleOf('/calendar')).toBe('Agenda')
    expect(sidebarModuleOf('/tasks/abc/show')).toBe('Tâches')
    expect(sidebarModuleOf('/recipes/abc/show')).toBe('Cuisine')
    expect(sidebarModuleOf('/ingredients')).toBe('Réglages')
    expect(sidebarModuleOf('/nowhere')).toBeUndefined()
    expect(sidebarEntryOf('/recipes/abc/show')).toBe('/recipes')
    expect(sidebarEntryOf('/grocery')).toBe('/grocery')
    expect(sidebarEntryOf('/finance/cushion')).toBe('/finance/summary/treasury')
    expect(sidebarEntryOf('/calendar')).toBeUndefined()
  })

  test('every entry resolves to a trail of its own module', () => {
    for (const module of SIDEBAR) {
      for (const entry of module.entries) {
        expect(resolveTrail(entry.path)[0]?.label, entry.path).toBe(module.label)
      }
    }
  })
})

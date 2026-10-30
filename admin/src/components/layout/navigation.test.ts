import { describe, expect, test } from 'vitest'
import {
  FINANCE_DASHBOARD_PATH,
  FINANCE_MENUS,
  HEALTH_SUIVI_PATH,
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

  test('hold every aspect once, and no two aspects or screens share an address', () => {
    const aspects = FINANCE_MENUS.flatMap((menu) => [...menu.aspects, ...(menu.screens ?? [])])
    const ids = aspects.map((aspect) => aspect.id)
    const paths = aspects.flatMap((aspect) => (aspect.path ? [aspect.path] : []))

    expect(new Set(ids).size).toBe(ids.length)
    expect(new Set(paths).size).toBe(paths.length)
  })

  test('keep every Finance screen of before reachable from exactly one menu', () => {
    const reached = FINANCE_MENUS.flatMap((menu) =>
      [...menu.aspects, ...(menu.screens ?? [])].flatMap((aspect) => (aspect.path ? [aspect.path] : [])),
    )

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

  test('Comptes and Trésorerie are one page each, without sub-views (MAG-632)', () => {
    for (const key of ['accounts', 'treasury']) {
      expect(FINANCE_MENUS.find((menu) => menu.key === key)?.aspects, key).toHaveLength(1)
    }
  })

  test('Budget has the mock-up\'s two sub-views: Mois · Hors budget (MAG-632)', () => {
    const budget = FINANCE_MENUS.find((menu) => menu.key === 'budget')

    expect(budget?.aspects.map((aspect) => aspect.label)).toEqual(['Mois', 'Hors budget'])
  })

  test('the screens that are no longer tabs keep their trail under their menu (MAG-632)', () => {
    expect(labels('/finance/import')).toEqual(['Finance', 'Comptes', 'Import de relevés'])
    expect(labels('/contacts')).toEqual(['Finance', 'Comptes', 'Contacts'])
    expect(labels('/contacts/create')).toEqual(['Finance', 'Comptes', 'Contacts', 'Nouveau'])
    expect(labels('/finance/savings-advances')).toEqual(['Finance', 'Trésorerie', "Avances d'épargne"])
    expect(labels('/finance/peer-debts')).toEqual(['Finance', 'Trésorerie', 'Créances et dettes'])
    expect(labels('/finance/treasury')).toEqual(['Finance', 'Trésorerie'])
    expect(labels('/accounts')).toEqual(['Finance', 'Comptes'])
    expect(financeMenuOf('/finance/import')).toBe('accounts')
    expect(financeMenuOf('/contacts')).toBe('accounts')
    expect(financeMenuOf('/finance/peer-debts')).toBe('treasury')
  })

  test('Revue is one page without sub-views; Configuration reads Catégories · Banques, rules and recurring operations behind it (MAG-634)', () => {
    const review = FINANCE_MENUS.find((menu) => menu.key === 'review')
    const settings = FINANCE_MENUS.find((menu) => menu.key === 'settings')

    expect(review?.aspects.map((aspect) => aspect.id)).toEqual(['review'])
    expect(settings?.aspects.map((aspect) => aspect.label)).toEqual(['Catégories', 'Banques'])
    expect(settings?.screens?.map((screen) => screen.path)).toEqual(['/categorization_rules', '/finance/recurring-operations'])
    expect(labels('/finance/recurring-operations')).toEqual(['Finance', 'Configuration', 'Opérations récurrentes'])
    expect(financeMenuOf('/categorization_rules')).toBe('settings')
    expect(financeMenuOf('/finance/recurring-operations')).toBe('settings')
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
    expect(financeMenuOf('/finance/summary/budget/outside')).toBe('budget')
  })

  test('every aspect of every menu has a tab address with a trail', () => {
    for (const menu of FINANCE_MENUS) {
      for (const aspect of menu.aspects) {
        // The only tab of a menu is the menu itself: no second crumb.
        const expected = aspect.label === menu.label ? ['Finance', menu.label] : ['Finance', menu.label, aspect.label]
        expect(labels(financeAspectPath(menu.key, aspect.id))).toEqual(expected)
      }
    }
  })

  test('the detail of a record keeps its menu', () => {
    expect(labels('/loans/abc/show')).toEqual(['Finance', 'Planification', 'Prêts', undefined])
    expect(labels('/envelopes/create')).toEqual(['Finance', 'Budget', 'Mois', 'Nouveau'])
  })

  test('the transactions of an account go through Comptes', () => {
    expect(labels('/accounts/01ABC/transactions')).toEqual(['Finance', 'Comptes', undefined])
  })

  test('every part of a module without menus keeps its two levels', () => {
    for (const module of NAVIGATION.filter((candidate) => !candidate.menus)) {
      for (const part of module.parts.filter((candidate) => candidate.path !== module.dashboard && !candidate.bare)) {
        expect(labels(part.path)).toEqual([module.label, part.label])
      }
    }
  })

  test('the views of Tâches are routes, each with its own trail (MAG-644)', () => {
    expect(labels('/tasks')).toEqual(['Tâches', "Aujourd'hui"])
    expect(labels('/tasks/upcoming')).toEqual(['Tâches', 'À venir'])
    expect(labels('/tasks/someday')).toEqual(['Tâches', 'Un jour'])
    expect(labels('/tasks/done')).toEqual(['Tâches', 'Faites'])
    expect(labels('/tasks/routines')).toEqual(['Tâches', 'Routines'])
  })

  test('the records of a task have no crumb of their own between the module and the page', () => {
    expect(labels('/tasks/create')).toEqual(['Tâches', 'Nouveau'])
    expect(labels('/tasks/abc/show')).toEqual(['Tâches', undefined])
    expect(labels('/tasks/abc')).toEqual(['Tâches', undefined, 'Modifier'])
  })

  test('the raw lists sit under Réglages', () => {
    expect(labels('/agendas')).toEqual(['Réglages', 'Agendas'])
    expect(labels('/events/create')).toEqual(['Réglages', 'Événements', 'Nouveau'])
    expect(labels('/ingredients')).toEqual(['Réglages', 'Ingrédients'])
  })
})

describe('the Courses module', () => {
  test('the dashboard is « Courses › Liste »', () => {
    expect(labels('/grocery')).toEqual(['Courses', 'Liste'])
    expect(resolveTrail('/grocery').map((crumb) => crumb.to)).toEqual(['/grocery', '/grocery'])
  })

  test('« Courses passées » keeps its trail and routes but is not in the sidebar', () => {
    expect(labels('/errands')).toEqual(['Courses', 'Courses passées'])
    expect(labels('/errands/abc/show')).toEqual(['Courses', 'Courses passées', undefined])

    const courses = SIDEBAR.find((module) => module.label === 'Courses')
    expect(courses?.entries.map((entry) => entry.label)).toEqual(['Liste', 'Produits', 'Magasins'])
    expect(sidebarEntryOf('/errands')).toBe('/grocery')
  })
})

describe('the Tâches sidebar', () => {
  const tasks = () => SIDEBAR.find((module) => module.label === 'Tâches')

  test("lists Aujourd'hui · À venir · Un jour · Faites, and opens on Aujourd'hui", () => {
    expect(tasks()?.path).toBe('/tasks')
    expect(tasks()?.entries.map((entry) => [entry.label, entry.path])).toEqual([
      ["Aujourd'hui", '/tasks'],
      ['À venir', '/tasks/upcoming'],
      ['Un jour', '/tasks/someday'],
      ['Faites', '/tasks/done'],
    ])
  })

  test('« Routines » is reached from the page, not from the menu', () => {
    expect(tasks()?.entries.map((entry) => entry.label)).not.toContain('Routines')
    expect(sidebarEntryOf('/tasks/routines')).toBe('/tasks')
  })

  test.each([
    ['/tasks', '/tasks'],
    ['/tasks/upcoming', '/tasks/upcoming'],
    ['/tasks/someday', '/tasks/someday'],
    ['/tasks/done', '/tasks/done'],
    ['/tasks/create', '/tasks'],
  ])('%s highlights %s', (path, entry) => {
    expect(sidebarEntryOf(path)).toBe(entry)
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
    ['/finance/import', 'accounts'],
    ['/categories', 'settings'],
  ])('%s highlights %s', (path, key) => {
    expect(financeMenuOf(path)).toBe(key)
  })

  test.each(['/', '/calendar', '/transactions', '/finance/summary/nowhere'])('%s highlights no Finance menu', (path) => {
    expect(financeMenuOf(path)).toBeUndefined()
  })
})

describe('the sidebar table', () => {
  test('lists Accueil · Agenda · Tâches · Courses · Cuisine · Santé · Finance, Réglages last', () => {
    expect(SIDEBAR.map((module) => module.label)).toEqual([
      'Accueil',
      'Agenda',
      'Tâches',
      'Courses',
      'Cuisine',
      'Santé',
      'Finance',
      'Réglages',
    ])
  })

  test('Santé opens on Suivi, listed before Sport', () => {
    const health = SIDEBAR.find((module) => module.label === 'Santé')

    expect(health?.path).toBe(HEALTH_SUIVI_PATH)
    expect(health?.entries.map((entry) => [entry.label, entry.path])).toEqual([
      ['Suivi', '/health/suivi'],
      ['Sport', '/workouts'],
    ])
    expect(labels('/health/suivi')).toEqual(['Santé', 'Suivi'])
    expect(sidebarEntryOf('/health/suivi')).toBe('/health/suivi')
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

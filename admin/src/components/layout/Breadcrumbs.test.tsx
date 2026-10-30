import { describe, test, expect, afterEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient } from '@tanstack/react-query'
import type { DataProvider } from 'react-admin'
import { Breadcrumbs, PageHeading } from './Breadcrumbs'
import { CustomMenu } from './Menu'
import { NAVIGATION, SIDEBAR, resolveTrail } from './navigation'
import { veilleuseLightTheme } from '../../theme'
import { PHONE_WIDTH, resetViewport, setViewportWidth } from '../../test/viewport'

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

const getOne = (record: Record<string, unknown>, delay = 0) =>
  vi.fn(
    () =>
      new Promise((resolve) => setTimeout(() => resolve({ data: record }), delay)),
  ) as unknown as DataProvider['getOne']

const renderAt = (path: string, dataProvider: DataProvider = testDataProvider(), queryClient?: QueryClient) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <AdminContext dataProvider={dataProvider} theme={veilleuseLightTheme} store={memoryStore()} queryClient={queryClient}>
        <Breadcrumbs />
        <Routes>
          <Route path="*" element={<Where />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

const nav = () => screen.getByRole('navigation', { name: "Fil d'Ariane" })
const labels = () =>
  within(nav())
    .getAllByText(/\S/)
    .map((node) => node.textContent)
    .filter((text) => text !== '›')

afterEach(() => {
  resetViewport()
})

describe('Breadcrumbs', () => {
  test('a list shows its module and its part, the part being the current page', () => {
    renderAt('/categories')

    expect(labels()).toEqual(['Finance', 'Configuration', 'Catégories'])
    expect(screen.getByRole('link', { name: 'Finance' })).toHaveAttribute('href', '/finance/dashboard')
    expect(screen.getByRole('link', { name: 'Configuration' })).toHaveAttribute('href', '/finance/summary/settings')
    expect(screen.queryByRole('link', { name: 'Catégories' })).toBeNull()
    expect(screen.getByText('Catégories')).toHaveAttribute('aria-current', 'page')
  })

  test('the workouts read « Santé › Sport », Sport being the current page', () => {
    renderAt('/workouts')

    expect(labels()).toEqual(['Santé', 'Sport'])
    expect(screen.getByRole('link', { name: 'Santé' })).toHaveAttribute('href', '/workouts')
    expect(screen.getByText('Sport')).toHaveAttribute('aria-current', 'page')
  })

  test('the grocery list reads « Courses › Liste », the list being the current page', () => {
    renderAt('/grocery')

    expect(labels()).toEqual(['Courses', 'Liste'])
    expect(screen.getByRole('link', { name: 'Courses' })).toHaveAttribute('href', '/grocery')
    expect(screen.getByText('Liste')).toHaveAttribute('aria-current', 'page')
  })

  test('the past errands, no longer in the menu, still read « Courses › Courses passées »', () => {
    renderAt('/errands')

    expect(labels()).toEqual(['Courses', 'Courses passées'])
  })

  test('a record shows its name, then the action on its edit page', async () => {
    const dataProvider = testDataProvider({ getOne: getOne({ id: '/api/transactions/01', label: 'Prélèvement EDF' }) })

    renderAt(`/transactions/${encodeURIComponent('/api/transactions/01')}`, dataProvider)

    expect(await screen.findByText('Prélèvement EDF')).toBeInTheDocument()
    expect(labels()).toEqual(['Finance', 'Transactions', 'Prélèvement EDF', 'Modifier'])
    expect(dataProvider.getOne).toHaveBeenCalledWith('transactions', expect.objectContaining({ id: '/api/transactions/01' }))
    expect(screen.getByText('Modifier')).toHaveAttribute('aria-current', 'page')
  })

  test('a show page ends on the record', async () => {
    const dataProvider = testDataProvider({ getOne: getOne({ id: '/api/recipes/01', name: 'Tarte aux poireaux' }) })

    renderAt(`/recipes/${encodeURIComponent('/api/recipes/01')}/show`, dataProvider)

    expect(await screen.findByText('Tarte aux poireaux')).toHaveAttribute('aria-current', 'page')
    expect(labels()).toEqual(['Cuisine', 'Recettes', 'Tarte aux poireaux'])
  })

  test('the balance of the month is a page of the finance dashboard', () => {
    renderAt('/finance/dashboard/current')

    expect(labels()).toEqual(['Finance', 'État courant'])
    expect(screen.getByRole('link', { name: 'Finance' })).toHaveAttribute('href', '/finance/dashboard')
    expect(screen.getByText('État courant')).toHaveAttribute('aria-current', 'page')
  })

  test('a creation ends on « Nouveau »', () => {
    renderAt('/events/create')

    expect(labels()).toEqual(['Réglages', 'Événements', 'Nouveau'])
  })

  test('the task settings sit under Réglages', () => {
    renderAt('/settings/tasks')

    expect(labels()).toEqual(['Réglages', 'Tâches'])
  })

  test('the task order settings sit under Réglages', () => {
    renderAt('/settings/tasks/order')

    expect(labels()).toEqual(['Réglages', 'Ce qui passe en premier'])
  })

  test('the meal settings sit under Réglages', () => {
    renderAt('/settings/meals')

    expect(labels()).toEqual(['Réglages', 'Repas'])
  })

  test('a settings page sits under its module', () => {
    renderAt('/settings/agent')

    expect(labels()).toEqual(['Réglages', 'Maggie'])
    expect(screen.getByRole('link', { name: 'Réglages' })).toHaveAttribute('href', '/settings/preferences')
  })

  test.each([
    ['summary', { summary: 'Soirée à Rennes avec Julie', name: 'ignoré' }, 'Soirée à Rennes avec Julie'],
    ['name', { name: 'Tarte', label: 'ignoré' }, 'Tarte'],
    ['label', { label: 'Prélèvement EDF', title: 'ignoré' }, 'Prélèvement EDF'],
    ['title', { title: 'Appeler le médecin' }, 'Appeler le médecin'],
  ])('names a record by its %s', async (_field, record, expected) => {
    renderAt('/events/%2Fapi%2Fevents%2F01/show', testDataProvider({ getOne: getOne(record) }))

    expect(await screen.findByText(expected)).toBeInTheDocument()
  })

  test('holds a grey placeholder while the record loads, then names it', async () => {
    renderAt('/recipes/%2Fapi%2Frecipes%2F01/show', testDataProvider({ getOne: getOne({ name: 'Tarte' }, 50) }))

    expect(screen.getByTestId('breadcrumb-loading')).toBeInTheDocument()
    expect(await screen.findByText('Tarte')).toBeInTheDocument()
    expect(screen.queryByTestId('breadcrumb-loading')).toBeNull()
  })

  test('falls back to « Détail » when the record has no readable name', async () => {
    renderAt('/loans/%2Fapi%2Floans%2F01/show', testDataProvider({ getOne: getOne({ id: '/api/loans/01' }) }))

    expect(await screen.findByText('Détail')).toBeInTheDocument()
  })

  test('falls back to « Détail » when the record cannot be read', async () => {
    const failing = vi.fn(() => Promise.reject(new Error('404'))) as unknown as DataProvider['getOne']
    vi.spyOn(console, 'error').mockImplementation(() => {})

    renderAt(
      '/loans/%2Fapi%2Floans%2F01/show',
      testDataProvider({ getOne: failing }),
      new QueryClient({ defaultOptions: { queries: { retry: false } } }),
    )

    expect(await screen.findByText('Détail')).toBeInTheDocument()
  })

  test('the recurring operations page sits under Finance', () => {
    renderAt('/finance/recurring-operations')

    expect(labels()).toEqual(['Finance', 'Configuration', 'Opérations récurrentes'])
    expect(screen.getByText('Opérations récurrentes')).toHaveAttribute('aria-current', 'page')
  })

  test('a menu page sits under Finance', () => {
    renderAt('/finance/summary/treasury')

    expect(labels()).toEqual(['Finance', 'Trésorerie'])
    expect(screen.getByText('Trésorerie')).toHaveAttribute('aria-current', 'page')
  })

  test('an aspect sits under its menu: Finance › Trésorerie › Matelas', () => {
    renderAt('/finance/cushion')

    expect(labels()).toEqual(['Finance', 'Trésorerie', 'Matelas'])
    expect(screen.getByRole('link', { name: 'Trésorerie' })).toHaveAttribute('href', '/finance/summary/treasury')
  })

  test('the Dashboard is the module itself', () => {
    renderAt('/finance/dashboard')

    expect(labels()).toEqual(['Finance'])
  })

  test('a page the table does not know has no breadcrumb', () => {
    renderAt('/nowhere')

    expect(screen.queryByRole('navigation', { name: "Fil d'Ariane" })).toBeNull()
  })

  test('an account\'s transactions page is named after the account', async () => {
    renderAt('/accounts/01ABC/transactions', testDataProvider({ getOne: getOne({ name: 'Compte joint' }) }))

    expect(await screen.findByText('Compte joint')).toBeInTheDocument()
    expect(labels()).toEqual(['Finance', 'Comptes', 'Compte joint'])
  })

  test('clicking a level goes there: the part, then the module dashboard', async () => {
    const user = userEvent.setup()
    renderAt('/transactions/%2Fapi%2Ftransactions%2F01', testDataProvider({ getOne: getOne({ label: 'Prélèvement EDF' }) }))
    await screen.findByText('Prélèvement EDF')

    await user.click(screen.getByRole('link', { name: 'Transactions' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/transactions')
    expect(labels()).toEqual(['Finance', 'Transactions'])

    await user.click(screen.getByRole('link', { name: 'Finance' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/finance/dashboard')
    expect(labels()).toEqual(['Finance'])
  })

  test('below md only the parent is shown, with a way back', async () => {
    setViewportWidth(PHONE_WIDTH)
    const user = userEvent.setup()
    renderAt('/transactions/%2Fapi%2Ftransactions%2F01', testDataProvider({ getOne: getOne({ label: 'Prélèvement EDF' }) }))
    await screen.findByRole('link', { name: /Prélèvement EDF/ })

    expect(within(nav()).getAllByRole('link')).toHaveLength(1)
    expect(screen.queryByText('Finance')).toBeNull()

    await user.click(screen.getByRole('link', { name: /Prélèvement EDF/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/transactions/%2Fapi%2Ftransactions%2F01/show')
  })

  test('below md a top-level page just names itself', () => {
    setViewportWidth(PHONE_WIDTH)
    renderAt('/')

    expect(within(nav()).queryByRole('link')).toBeNull()
    expect(screen.getByText('Accueil')).toHaveAttribute('aria-current', 'page')
  })
})

describe('the menu and the breadcrumb agree', () => {
  /** The routes the menu offers, once the page of each module has opened its entries. */
  const openWholeMenu = async () => {
    const hrefs = new Set<string>()

    for (const module of SIDEBAR) {
      const { container, unmount } = render(
        <MemoryRouter initialEntries={[module.path]}>
          <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme} store={memoryStore()}>
            <CustomMenu />
          </AdminContext>
        </MemoryRouter>,
      )
      await screen.findAllByRole('menuitem')
      container.querySelectorAll('a[href]').forEach((link) => hrefs.add(link.getAttribute('href') as string))
      unmount()
    }

    return [...hrefs]
  }

  test('every route the menu offers has a trail', async () => {
    const hrefs = await openWholeMenu()
    expect(hrefs.length).toBeGreaterThan(15)
    expect(hrefs).toContain('/finance/summary/treasury')

    for (const href of hrefs) {
      expect(resolveTrail(href), `${href} has no breadcrumb`).not.toHaveLength(0)
    }
  })

  test('the recurring grocery items are a dialog of the list, not an entry of the menu', async () => {
    const hrefs = await openWholeMenu()

    expect(hrefs.some((href) => href.includes('recurring_grocery_items'))).toBe(false)
    expect(hrefs.some((href) => href.endsWith('/grocery'))).toBe(true)
    expect(NAVIGATION.flatMap((module) => module.parts).some((part) => part.resource === 'recurring_grocery_items')).toBe(false)
  })

  test('every part of the table resolves to its own trail, through its menu when it has one', () => {
    for (const module of NAVIGATION) {
      for (const part of module.parts.filter((candidate) => candidate.path !== module.dashboard && !candidate.bare)) {
        const trail = resolveTrail(part.path)
        // The only page of its menu is the menu itself: one crumb, not two.
        const expected = !part.menu
          ? [module.label, part.label]
          : part.menu.label === part.label
            ? [module.label, part.menu.label]
            : [module.label, part.menu.label, part.label]
        expect(trail.map((crumb) => crumb.label)).toEqual(expected)
      }
    }
  })
})

describe('PageHeading — the h1 of every page', () => {
  const renderHeading = (path: string, dataProvider: DataProvider = testDataProvider()) =>
    render(
      <MemoryRouter initialEntries={[path]}>
        <AdminContext dataProvider={dataProvider} theme={veilleuseLightTheme} store={memoryStore()}>
          <PageHeading />
        </AdminContext>
      </MemoryRouter>,
    )

  test.each([
    ['/calendar', 'Agenda'],
    ['/tasks', "Aujourd'hui"],
    ['/tasks/upcoming', 'À venir'],
    ['/tasks/someday', 'Un jour'],
    ['/tasks/done', 'Faites'],
    ['/tasks/routines', 'Routines'],
    ['/grocery', 'Liste'],
    ['/products', 'Produits'],
    ['/meals', 'Repas'],
    ['/finance/dashboard', 'Finance'],
    ['/workouts', 'Sport'],
    ['/accounts', 'Comptes'],
    ['/settings/preferences', 'Préférences'],
    ['/agendas', 'Agendas'],
  ])('%s has the h1 « %s »', (path, title) => {
    renderHeading(path)

    expect(screen.getByRole('heading', { level: 1, name: title })).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  test('the Accueil is titled with the date of the day it shows', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 25, 19, 50))

    try {
      renderHeading('/')
      expect(screen.getByRole('heading', { level: 1, name: 'Accueil · dimanche 25 octobre' })).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  test('the Accueil on « Demain » is titled with tomorrow', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 25, 19, 50))

    try {
      renderHeading('/?day=tomorrow')
      expect(screen.getByRole('heading', { level: 1, name: 'Accueil · lundi 26 octobre' })).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  test('an edit page is titled with the name of the record', async () => {
    const dataProvider = testDataProvider({ getOne: getOne({ id: '/api/events/01', summary: 'Dentiste' }) })

    renderHeading(`/events/${encodeURIComponent('/api/events/01')}`, dataProvider)

    expect(await screen.findByRole('heading', { level: 1, name: 'Dentiste' })).toBeInTheDocument()
  })

  test('a creation is titled « Nouveau » and the list it adds to', () => {
    renderHeading('/events/create')

    expect(screen.getByRole('heading', { level: 1, name: 'Nouveau · Événements' })).toBeInTheDocument()
  })

  test.each(['/finance/review', '/finance/summary/review'])('the Revue %s draws its own h1, which carries its count', (path) => {
    renderHeading(path)

    expect(screen.queryByRole('heading')).toBeNull()
  })

  test.each(['/recipes/create', `/recipes/${encodeURIComponent('/api/recipes/01')}/show`])(
    'the recipe sheet %s draws its own h1, not a second one',
    (path) => {
      renderHeading(path)

      expect(screen.queryByRole('heading')).toBeNull()
    },
  )

  test('a page the table does not know has no h1', () => {
    renderHeading('/nowhere')

    expect(screen.queryByRole('heading')).toBeNull()
  })
})

describe('Breadcrumbs — the mockup style', () => {
  test('is drawn at 13px in normal case, never in small caps', () => {
    renderAt('/products')

    const style = getComputedStyle(screen.getByText('Produits'))
    expect(style.fontSize).toBe('0.8125rem')
    expect(style.fontVariant).not.toContain('small-caps')
    expect(style.letterSpacing).not.toBe('0.08em')
  })

  test.each([
    ['/tasks', "Aujourd'hui"],
    ['/tasks/upcoming', 'À venir'],
    ['/tasks/someday', 'Un jour'],
    ['/tasks/done', 'Faites'],
    ['/tasks/routines', 'Routines'],
    ['/tasks/create', 'Nouveau'],
  ])('%s reads Tâches › %s', (path, view) => {
    renderAt(path)

    expect(labels()).toEqual(['Tâches', view])
  })
})

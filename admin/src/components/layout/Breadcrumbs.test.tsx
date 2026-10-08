import { describe, test, expect, afterEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { QueryClient } from '@tanstack/react-query'
import type { DataProvider } from 'react-admin'
import { Breadcrumbs } from './Breadcrumbs'
import { CustomMenu } from './Menu'
import { NAVIGATION, resolveTrail } from './navigation'
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

    expect(labels()).toEqual(['Finance', 'Catégories'])
    expect(screen.getByRole('link', { name: 'Finance' })).toHaveAttribute('href', '/finance/dashboard')
    expect(screen.queryByRole('link', { name: 'Catégories' })).toBeNull()
    expect(screen.getByText('Catégories')).toHaveAttribute('aria-current', 'page')
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

  test('a creation ends on « Nouveau »', () => {
    renderAt('/events/create')

    expect(labels()).toEqual(['Agenda', 'Événements', 'Nouveau'])
  })

  test('a settings page sits under its module', () => {
    renderAt('/settings/agent')

    expect(labels()).toEqual(['Paramètres', 'Agent'])
    expect(screen.getByRole('link', { name: 'Paramètres' })).toHaveAttribute('href', '/settings/preferences')
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
  test('every route the menu offers has a trail', async () => {
    const user = userEvent.setup()
    const { container } = render(
      <MemoryRouter initialEntries={['/']}>
        <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme} store={memoryStore()}>
          <CustomMenu />
        </AdminContext>
      </MemoryRouter>,
    )

    // Open every group, nested ones included, until none is left folded.
    for (let guard = 0; guard < 10; guard++) {
      const folded = screen.queryAllByTestId('ExpandMoreIcon')
      if (folded.length === 0) {
        break
      }
      for (const icon of folded) {
        await user.click(icon.closest('[role="button"]') as HTMLElement)
      }
    }

    const hrefs = Array.from(container.querySelectorAll('a[href]')).map(
      (link) => link.getAttribute('href') as string,
    )
    expect(hrefs.length).toBeGreaterThan(15)

    for (const href of hrefs) {
      expect(resolveTrail(href), `${href} has no breadcrumb`).not.toHaveLength(0)
    }
  })

  test('every part of the table resolves to its own two-level trail', () => {
    for (const module of NAVIGATION) {
      for (const part of module.parts) {
        const trail = resolveTrail(part.path)
        expect(trail.map((crumb) => crumb.label)).toEqual([module.label, part.label])
      }
    }
  })
})

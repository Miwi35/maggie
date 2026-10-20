import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { FINANCE_MENUS } from '../../components/layout/navigation'
import { veilleuseLightTheme } from '../../theme'
import { FinanceSummaryPage } from './FinanceSummaryPage'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

// The aspects' own screens have their tests; what is checked here is which one the page shows.
vi.mock('./FinanceAspectContent', async () => {
  const { createElement } = await import('react')

  return {
    ASPECT_CONTENT: new Proxy({}, { get: (_, id) => () => createElement('p', null, `content:${String(id)}`) }),
  }
})

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

type Body = Record<string, unknown>

/** Nothing anywhere: every aspect is empty. */
const EMPTY: Record<string, Body> = {
  '/api/finance/dashboard': { independence: { hasPassiveIncomeCategories: false } },
  '/api/finance/transactions/to-sort': { total: 0, transactions: [] },
  '/api/finance/review/recognised': { month: '2026-10', total: 0, groups: [] },
  '/api/finance/monthly-review': { pendingCount: 0 },
  '/api/finance/recurring-operations/overview': {
    operations: [],
    proposals: [],
    totals: { activeCount: 0, monthlyCostCents: 0, yearlyCostCents: 0, monthlyIncomeCents: 0 },
  },
  '/api/finance/cushion-status': { isConfigured: false, state: 'unset', currentCents: 0, targetCents: 0 },
  '/api/finance/savings-advances': { advances: [], totalOutstandingCents: 0 },
  '/api/finance/peer-debts/summary': { owedToMeCents: 0, iOweCents: 0, byContact: [] },
  '/api/finance/treasury-outlook': { balanceCents: 0, salary: { isConfigured: false }, overdraft: { isForecast: false }, projection: [] },
  '/api/finance/treasury-settings': { salaryExpectedDay: null, overdraftLimitCents: 0 },
  '/api/finance/bank-connections': { connections: [] },
  '/api/finance/projects': { member: [] },
}

/** The API answers each path from `EMPTY` and the overrides; a path in `failing` answers 500. */
const stubApi = (overrides: Record<string, Body> = {}, failing: string[] = []) => {
  const answers = { ...EMPTY, ...overrides }
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input).split('?')[0]

    if (failing.includes(path) || !(path in answers)) {
      return new Response('down', { status: 500 })
    }

    return new Response(JSON.stringify(answers[path]))
  })
  vi.stubGlobal('fetch', fetch)

  return fetch
}

const renderPage = (path: string, rows = 0) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <AdminContext
        dataProvider={testDataProvider({ getList: () => Promise.resolve({ data: [], total: rows }) })}
        theme={veilleuseLightTheme}
        store={memoryStore()}
      >
        <Routes>
          <Route path="/finance/summary/:key/:aspect?" element={<FinanceSummaryPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

const selectedTab = async () => {
  const tabs = await screen.findAllByRole('tab')

  return tabs.find((tab) => tab.getAttribute('aria-selected') === 'true')
}

const opensOn = async (label: string, aspect: string) => {
  expect(await screen.findByText(`content:${aspect}`)).toBeInTheDocument()
  expect(await selectedTab()).toHaveAccessibleName(label)
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('FinanceSummaryPage', () => {
  test.each(FINANCE_MENUS.slice(1).filter((menu) => menu.aspects.length > 1).map((menu) => [menu.key, menu.label, menu.aspects] as const))(
    '%s (%s) is one tab per aspect of the table, and its body is the content, not a summary',
    async (key, label, aspects) => {
      stubApi()
      renderPage(`/finance/summary/${key}`)

      expect(screen.getByRole('tablist', { name: label })).toBeInTheDocument()
      const tabs = await screen.findAllByRole('tab')
      expect(tabs.map((tab) => tab.textContent)).toEqual(aspects.map((aspect) => aspect.label))

      expect(await screen.findByText(`content:${aspects[0].id}`)).toBeInTheDocument()
      expect(screen.queryAllByTestId('summary-block')).toHaveLength(0)
    },
  )

  test('a menu with a single aspect has no tabs: the content, directly', async () => {
    stubApi()
    renderPage('/finance/summary/budget')

    expect(await screen.findByText('content:envelopes')).toBeInTheDocument()
    expect(screen.queryByRole('tablist')).toBeNull()
  })

  test('opens on the first aspect when none has data', async () => {
    stubApi()
    renderPage('/finance/summary/planning')

    await opensOn('Projets', 'projects')
  })

  test('opens on the first aspect that has data', async () => {
    stubApi({ '/api/finance/transactions/to-sort': { total: 0, transactions: [] }, '/api/finance/review/recognised': { month: '2026-10', total: 5, groups: [] } })
    renderPage('/finance/summary/review')

    await opensOn('Reconnues à contrôler', 'to-control')
  })

  test('the first aspect with data wins even when a later one has some too', async () => {
    stubApi({
      '/api/finance/transactions/to-sort': { total: 3, transactions: [] },
      '/api/finance/review/recognised': { month: '2026-10', total: 5, groups: [] },
    })
    renderPage('/finance/summary/review')

    await opensOn('À qualifier', 'to-sort')
  })

  test('Planification opens on Indépendance when only the rentes are declared', async () => {
    stubApi({ '/api/finance/dashboard': { independence: { hasPassiveIncomeCategories: true } } })
    renderPage('/finance/summary/planning')

    await opensOn('Indépendance', 'independence')
  })

  test('Planification opens on Prêts when a loan exists', async () => {
    stubApi({ '/api/finance/dashboard': { independence: { hasPassiveIncomeCategories: true } } })
    renderPage('/finance/summary/planning', 2)

    await opensOn('Prêts', 'loans')
  })

  test('Planification opens on Projets as soon as a project exists, loans or not (MAG-519)', async () => {
    stubApi({ '/api/finance/projects': { member: [{ id: 'p1', name: 'Canapé', amountCents: 120000, position: 0, dateMode: 'none' }] } })
    renderPage('/finance/summary/planning', 2)

    await opensOn('Projets', 'projects')
  })

  test('an aspect whose read fails counts as empty and does not hold the menu back', async () => {
    stubApi(
      { '/api/finance/savings-advances': { advances: [], totalOutstandingCents: 100000 } },
      ['/api/finance/cushion-status'],
    )
    renderPage('/finance/summary/treasury')

    await opensOn("Avances d'épargne", 'advances')
  })

  test('the tab named in the URL is the one shown, whatever has data', async () => {
    const fetch = stubApi({ '/api/finance/transactions/to-sort': { total: 3, transactions: [] } })
    renderPage('/finance/summary/review/monthly-review')

    await opensOn('Revue mensuelle', 'monthly-review')
    expect(fetch).not.toHaveBeenCalled()
  })

  test('a tab is a link to its own address: it shows the aspect and the URL keeps it', async () => {
    stubApi()
    const user = userEvent.setup()
    renderPage('/finance/summary/treasury/cushion')

    const outlook = await screen.findByRole('tab', { name: 'Solde projeté' })
    expect(outlook).toHaveAttribute('href', '/finance/summary/treasury/outlook')

    await user.click(outlook)

    await opensOn('Solde projeté', 'outlook')
  })

  test.each([['nowhere'], ['dashboard']])('the unknown menu « %s » says so and leads back to the Dashboard', (key) => {
    stubApi()
    renderPage(`/finance/summary/${key}`)

    expect(screen.getByText("Ce menu n'existe pas.")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Retour au Dashboard' })).toHaveAttribute('href', '/finance/dashboard')
    expect(screen.queryByRole('tab')).toBeNull()
  })

  test('an unknown aspect says so too', () => {
    stubApi()
    renderPage('/finance/summary/planning/nowhere')

    expect(screen.getByText("Ce menu n'existe pas.")).toBeInTheDocument()
    expect(within(document.body).queryByRole('tab')).toBeNull()
  })
})

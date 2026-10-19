import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { FINANCE_MENUS } from '../../components/layout/navigation'
import { veilleuseLightTheme } from '../../theme'
import { FinanceSummaryPage } from './FinanceSummaryPage'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

type Body = Record<string, unknown>

const ANSWERS: Record<string, Body> = {
  '/api/finance/dashboard': {
    balance: {
      totalCents: 604550,
      cushionCents: 420000,
      availableCents: 184550,
      accounts: [{ id: 'a' }, { id: 'b' }],
    },
    independence: { hasPassiveIncomeCategories: true, isMeasurable: true, coveragePercent: 12.4 },
  },
  '/api/finance/transactions/to-sort': { total: 3, transactions: [] },
  '/api/finance/review/recognised': { month: '2026-10', total: 5, groups: [] },
  '/api/finance/monthly-review': { pendingCount: 4 },
  '/api/finance/recurring-operations/overview': {
    operations: [],
    proposals: [{}, {}],
    totals: { activeCount: 7, monthlyCostCents: 90000, yearlyCostCents: 0, monthlyIncomeCents: 0 },
  },
  '/api/finance/budget-status': {
    totalBudgetedCents: 200000,
    totalRemainingCents: 42000,
    budgets: [{ isOverspent: false }],
  },
  '/api/finance/cushion-status': { isConfigured: true, state: 'building', currentCents: 420000, targetCents: 700000 },
  '/api/finance/savings-advances': { advances: [], totalOutstandingCents: 100000 },
  '/api/finance/peer-debts/summary': { owedToMeCents: 25000, iOweCents: 0, byContact: [] },
  '/api/finance/treasury-outlook': {
    balanceCents: 184550,
    salary: { isConfigured: true },
    overdraft: { isForecast: false },
    projection: [],
  },
  '/api/finance/treasury-settings': { salaryExpectedDay: 25, overdraftLimitCents: 0 },
  '/api/finance/bank-connections': {
    connections: [
      { id: '1', bankName: 'Boursorama', needsReconnecting: true },
      { id: '2', bankName: 'Crédit Mutuel', needsReconnecting: false },
    ],
  },
}

/** The API answers each path from `ANSWERS`; a path in `failing` answers 500. */
const stubApi = (failing: string[] = []) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input).split('?')[0]

      if (failing.includes(path) || !(path in ANSWERS)) {
        return new Response('down', { status: 500 })
      }

      return new Response(JSON.stringify(ANSWERS[path]))
    }),
  )

const renderPage = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <AdminContext
        dataProvider={testDataProvider({ getList: () => Promise.resolve({ data: [], total: 3 }) })}
        theme={veilleuseLightTheme}
        store={memoryStore()}
      >
        <Routes>
          <Route path="/finance/summary/:key" element={<FinanceSummaryPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

const block = async (aspect: string) => {
  const blocks = await screen.findAllByTestId('summary-block')

  return blocks.find((candidate) => candidate.getAttribute('data-aspect') === aspect) as HTMLElement
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('FinanceSummaryPage', () => {
  test('Trésorerie: one block per aspect, each with its figures and opening its own screen', async () => {
    stubApi()
    renderPage('/finance/summary/treasury')

    const blocks = await screen.findAllByTestId('summary-block')
    expect(blocks.map((candidate) => candidate.getAttribute('data-aspect'))).toEqual(['cushion', 'advances', 'peer-debts', 'outlook'])

    const cushion = await block('cushion')
    expect(await within(cushion).findByText(/4 200,00 € \/ 7 000,00 €/)).toBeInTheDocument()
    expect(within(cushion).getByText('Constitution en cours')).toBeInTheDocument()
    expect(within(cushion).getByRole('link', { name: 'Matelas' })).toHaveAttribute('href', '/finance/cushion')

    const advances = await block('advances')
    expect(await within(advances).findByText(/1 000,00 €/)).toBeInTheDocument()
    expect(within(advances).getByRole('link', { name: "Avances d'épargne" })).toHaveAttribute(
      'href',
      '/finance/savings-advances',
    )

    const peerDebts = await block('peer-debts')
    expect(await within(peerDebts).findByText(/On te doit 250,00 €/)).toBeInTheDocument()
    expect(within(peerDebts).getByRole('link', { name: 'Créances et dettes' })).toHaveAttribute(
      'href',
      '/finance/peer-debts',
    )

    const outlook = await block('outlook')
    expect(await within(outlook).findByText("Aucun découvert prévu jusqu'au salaire")).toBeInTheDocument()
    expect(within(outlook).getByRole('link', { name: 'Solde projeté' })).toHaveAttribute('href', '/finance/treasury')
  })

  test('a block opens its detailed screen', async () => {
    stubApi()
    const user = userEvent.setup()
    renderPage('/finance/summary/treasury')

    await user.click(await screen.findByRole('link', { name: 'Matelas' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/cushion')
  })

  test('Configuration: banks say what to renew, categories are counted', async () => {
    stubApi()
    renderPage('/finance/summary/settings')

    const banks = await block('banks')
    expect(await within(banks).findByText('2 banques connectées')).toBeInTheDocument()
    expect(within(banks).getByText('1 consentement à renouveler')).toBeInTheDocument()
    expect(within(banks).getByRole('link', { name: 'Banques' })).toHaveAttribute('href', '/finance/banks')

    const categories = await block('categories')
    expect(await within(categories).findByText('3 catégories')).toBeInTheDocument()
    expect(within(categories).getByRole('link', { name: 'Catégories' })).toHaveAttribute('href', '/categories')
  })

  test('Revue, Comptes, Budget and Planification read their own figures', async () => {
    stubApi()
    const { unmount } = renderPage('/finance/summary/review')
    expect(await within(await block('to-sort')).findByText('3 transactions')).toBeInTheDocument()
    expect(await within(await block('to-control')).findByText('5 transactions')).toBeInTheDocument()
    expect(await within(await block('monthly-review')).findByText('4 dépenses')).toBeInTheDocument()
    expect(await within(await block('recurring')).findByText('7 opérations récurrentes actives')).toBeInTheDocument()
    expect(within(await block('rules')).getByRole('link', { name: 'Règles de catégorisation' })).toBeInTheDocument()
    unmount()

    const accounts = renderPage('/finance/summary/accounts')
    expect(await within(await block('accounts')).findByText(/6 045,50 €/)).toBeInTheDocument()
    expect(within(await block('import')).getByRole('link', { name: 'Import de relevés' })).toHaveAttribute(
      'href',
      '/finance/import',
    )
    expect(await within(await block('contacts')).findByText('3 contacts')).toBeInTheDocument()
    expect(within(await block('contacts')).getByRole('link', { name: 'Contacts' })).toHaveAttribute('href', '/contacts')
    accounts.unmount()

    const budget = renderPage('/finance/summary/budget')
    expect(await within(await block('envelopes')).findByText(/420,00 €/)).toBeInTheDocument()
    budget.unmount()

    renderPage('/finance/summary/planning')
    expect(await within(await block('independence')).findByText('12 %')).toBeInTheDocument()
    expect(await within(await block('loans')).findByText('3 prêts')).toBeInTheDocument()
  })

  test('an aspect without a detailed screen is a block without a link', async () => {
    stubApi()
    renderPage('/finance/summary/planning')

    expect(within(await block('independence')).queryByRole('link')).toBeNull()
  })

  test('a block whose read fails says so and still opens its screen', async () => {
    stubApi(['/api/finance/cushion-status'])
    renderPage('/finance/summary/treasury')

    const cushion = await block('cushion')
    expect(await within(cushion).findByText('Indisponible pour le moment.')).toBeInTheDocument()
    expect(within(cushion).getByRole('link', { name: 'Matelas' })).toHaveAttribute('href', '/finance/cushion')
    expect(await within(await block('advances')).findByText(/1 000,00 €/)).toBeInTheDocument()
  })

  test.each(['nowhere', 'dashboard'])('the unknown menu « %s » says so and leads back to the Dashboard', (key) => {
    stubApi()
    renderPage(`/finance/summary/${key}`)

    expect(screen.getByText("Ce menu n'existe pas.")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Retour au Dashboard' })).toHaveAttribute('href', '/finance/dashboard')
    expect(screen.queryAllByTestId('summary-block')).toHaveLength(0)
  })

  test.each(FINANCE_MENUS.slice(1).map((menu) => [menu.key, menu.label, menu.aspects.map((aspect) => aspect.id)] as const))(
    '%s (%s) shows one block per aspect of the table, never a list of links',
    async (key, label, aspects) => {
      stubApi()
      renderPage(`/finance/summary/${key}`)

      expect(screen.getByRole('heading', { name: label })).toBeInTheDocument()
      const blocks = await screen.findAllByTestId('summary-block')
      expect(blocks.map((candidate) => candidate.getAttribute('data-aspect'))).toEqual(aspects)
      expect(screen.queryByRole('list')).toBeNull()
    },
  )
})

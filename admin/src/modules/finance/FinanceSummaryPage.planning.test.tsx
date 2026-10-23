import { describe, test, expect, vi, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { FinanceSummaryPage } from './FinanceSummaryPage'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

const LOAN = {
  id: '/api/loans/01LOAN',
  name: 'Prêt immobilier',
  lender: 'Crédit Agricole',
  principalRemainingCents: 18000000,
  monthlyPaymentCents: 95000,
  annualRateBasisPoints: 150,
  priority: 1,
  currency: 'EUR',
}

const TIMELINE = {
  horizonMonths: 60,
  totalPrincipalRemainingCents: 18000000,
  totalMonthlyPaymentCents: 95000,
  totalInterestOverHorizonCents: 0,
  loans: [{ id: '/api/loans/01LOAN', name: 'Prêt immobilier', freedOn: '2030-01-01', endsBeyondHorizon: false }],
  reliefByMonth: [],
  savingCapacity: { monthlyNetIncomeCents: 0, loanPaymentsCents: 95000, estimatedLifestyleCents: 0, netCapacityCents: 0, isIncomeKnown: false },
}

const counter = (overrides: Record<string, unknown>) => ({
  hasPassiveIncomeCategories: false,
  isMeasurable: false,
  isReached: false,
  coveragePercent: 0,
  sampleMonths: 3,
  lifestyleCents: 0,
  passiveIncomeCents: 0,
  gapCents: 0,
  loanPaymentsCents: 0,
  monthlyNeedCents: 0,
  coverageWithDebtPercent: 0,
  nextMilestonePercent: null,
  nextMilestoneGapCents: null,
  byCategory: [],
  projection: { startMonth: '2026-10', nDate: null, curve: [], milestones: { '25': null, '50': null, '75': null, '100': null } },
  ...overrides,
})

const PEA = {
  '@id': '/api/finance/placements/01PEA',
  id: '01PEA',
  name: 'PEA',
  initial: 200000,
  deposit: 15000,
  frequency: 'monthly',
  annualRate: 600,
  years: 20,
  final: 7443007,
  paid: 3800000,
  interest: 3643007,
  rent: 24810,
  monthlyDeposit: 15000,
}

const stubApi = (independence: Record<string, unknown>, loans: boolean) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input).split('?')[0]
      if (path === '/api/finance/dashboard') return new Response(JSON.stringify({ independence: counter(independence) }))
      if (path === '/api/finance/debt-timeline') {
        return new Response(JSON.stringify(loans ? TIMELINE : { ...TIMELINE, loans: [], totalMonthlyPaymentCents: 0 }))
      }
      if (path === '/api/finance/placements') return new Response(JSON.stringify({ member: [PEA] }))
      if (path === '/api/finance/placements/01PEA/simulation') {
        return new Response(JSON.stringify({ final: 7443007, paid: 3800000, interest: 3643007, rent: 24810, curve: [] }))
      }

      return new Response('down', { status: 500 })
    }),
  )

const renderPlanning = (loans: boolean, path = '/finance/summary/planning') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <AdminContext
        dataProvider={testDataProvider({
          getList: () => Promise.resolve(loans ? { data: [LOAN] as never[], total: 1 } : { data: [], total: 0 }),
        })}
        theme={veilleuseLightTheme}
        store={memoryStore()}
      >
        <Routes>
          <Route path="/finance/summary/:key/:aspect?" element={<FinanceSummaryPage />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Finance › Planification, with its real screens', () => {
  test('with a loan it opens on Prêts: the list and the timeline, no click needed', async () => {
    stubApi({}, true)
    renderPlanning(true)

    expect(await screen.findByText('Prêt immobilier')).toBeInTheDocument()
    expect(await screen.findByText('Libération des charges')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Prêts' })).toHaveAttribute('aria-selected', 'true')
  })

  test('the Indépendance tab shows the counter', async () => {
    stubApi(
      { hasPassiveIncomeCategories: true, isMeasurable: true, coveragePercent: 12, passiveIncomeCents: 20000, lifestyleCents: 160000, gapCents: 140000 },
      true,
    )
    const user = userEvent.setup()
    renderPlanning(true)

    await user.click(await screen.findByRole('tab', { name: 'Indépendance' }))

    expect(await screen.findByText(/12 %/)).toBeInTheDocument()
    expect(screen.getByText(/de rentes sur/)).toBeInTheDocument()
    expect(screen.queryByText('Prêt immobilier')).toBeNull()
  })

  test('the Indépendance tab shows the date N, the curve and the dated milestones', async () => {
    stubApi(
      {
        hasPassiveIncomeCategories: true,
        isMeasurable: true,
        coveragePercent: 12,
        projection: {
          startMonth: '2026-10',
          nDate: '2045-09',
          curve: [
            { month: '2026-10', coverage: 0.12 },
            { month: '2045-09', coverage: 1 },
          ],
          milestones: { '25': '2028-03', '50': '2033-01', '75': '2039-06', '100': '2045-09' },
        },
      },
      false,
    )
    renderPlanning(false, '/finance/summary/planning/independence')

    expect(await screen.findByText('Date N projetée · septembre 2045')).toBeInTheDocument()
    expect(screen.getByTestId('independence-curve')).toBeInTheDocument()
    expect(screen.getByTestId('independence-milestones')).toHaveTextContent('25 % · mars 2028')
  })

  test('the Indépendance page says so when the dashboard cannot be read', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('down', { status: 500 })))
    renderPlanning(false, '/finance/summary/planning/independence')

    const alert = (await screen.findByText(/compteur d'indépendance n'a pas pu être chargé/)).closest('[role="alert"]')
    expect(alert).not.toBeNull()
    expect(within(alert as HTMLElement).getByRole('button', { name: 'Réessayer' })).toBeInTheDocument()
  })

  test('without data, Prêts says what to do: a way to add a loan', async () => {
    stubApi({}, false)
    renderPlanning(false, '/finance/summary/planning/loans')

    expect(await screen.findByText('Aucun prêt enregistré')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Ajouter un prêt/ })).toBeInTheDocument()
  })

  test('without a rente, Indépendance offers to declare one and leads to the categories', async () => {
    stubApi({}, false)
    renderPlanning(false, '/finance/summary/planning/independence')

    expect(await screen.findByText('Aucune rente déclarée')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Déclarer une rente' })).toHaveAttribute('href', '/categories')
  })

  test('the Indépendance tab shows the placements under the counter, a rente declared or not (MAG-555)', async () => {
    stubApi({}, false)
    renderPlanning(false, '/finance/summary/planning/independence')

    expect(await screen.findByText('Aucune rente déclarée')).toBeInTheDocument()
    const placements = await screen.findByRole('region', { name: 'Placements' })
    expect(await within(placements).findByText('PEA')).toBeInTheDocument()
    expect(within(placements).getByText(/^Retiré à 4 % par an/)).toBeInTheDocument()
  })

  test('with rentes declared but no loan, the menu opens on Indépendance', async () => {
    stubApi({ hasPassiveIncomeCategories: true }, false)
    renderPlanning(false)

    expect(await screen.findByText(/Pas encore de train de vie mesuré/)).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Indépendance' })).toHaveAttribute('aria-selected', 'true')
  })
})

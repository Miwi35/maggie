import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { FinanceDashboardPage } from './FinanceDashboardPage'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => undefined }))

const score = (referenceSalary: { monthlyCents: number | null; source: string }) => ({
  score: 'neutral',
  year: 2026,
  month: 10,
  reasons: [],
  budget: {
    totalBudgetedCents: 0,
    totalConsumedCents: 0,
    totalPlannedCents: 0,
    totalAvailableCents: 0,
    overspentCategories: [],
  },
  cushion: { state: 'building', blocksGreenScore: false },
  comparison: { thisMonthCents: 0, sameMonthLastYearCents: 0, differenceCents: 0, isBetter: false },
  referenceSalary,
})

const dashboard = (referenceSalary: { monthlyCents: number | null; source: string }) => ({
  year: 2026,
  month: 10,
  score: score(referenceSalary),
  balance: { totalCents: 0, cushionCents: 0, availableCents: 0, accounts: [] },
  monthlyFlows: [],
  budgets: [],
  topPosts: [],
  savingCapacity: {
    monthlyNetIncomeCents: 0,
    loanPaymentsCents: 0,
    estimatedLifestyleCents: 0,
    netCapacityCents: 0,
    isIncomeKnown: false,
  },
  independence: {
    coveragePercent: 0,
    lifestyleCents: 0,
    passiveIncomeCents: 0,
    gapCents: 0,
    sampleMonths: 0,
    isMeasurable: false,
    hasPassiveIncomeCategories: false,
    isReached: false,
    loanPaymentsCents: 0,
    monthlyNeedCents: 0,
    coverageWithDebtPercent: 0,
    nextMilestonePercent: null,
    nextMilestoneGapCents: null,
    byCategory: [],
  },
})

const stubApi = (payload: unknown, ok = true) => {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.includes('/api/finance/dashboard')) {
        return Promise.resolve({ ok, json: () => Promise.resolve(payload) })
      }

      return Promise.resolve({ ok: false, json: () => Promise.resolve({}) })
    }),
  )
}

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()}>
        <FinanceDashboardPage />
      </AdminContext>
    </MemoryRouter>,
  )

describe('FinanceDashboardPage — reference salary', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('shows a declared reference salary', async () => {
    stubApi(dashboard({ monthlyCents: 300000, source: 'declared' }))
    renderPage()

    const card = await screen.findByTestId('reference-salary-card')
    expect(card).toHaveTextContent(/3\s000,00\s€/)
    expect(card).toHaveTextContent('déclaré sur le matelas')
  })

  test('shows a measured reference salary', async () => {
    stubApi(dashboard({ monthlyCents: 495000, source: 'measured' }))
    renderPage()

    const card = await screen.findByTestId('reference-salary-card')
    expect(card).toHaveTextContent(/4\s950,00\s€/)
    expect(card).toHaveTextContent('mesuré sur 12 mois')
  })

  test('shows « non renseigné » and a link, not 0 €, when the reference is unknown', async () => {
    stubApi(dashboard({ monthlyCents: null, source: 'unknown' }))
    renderPage()

    const card = await screen.findByTestId('reference-salary-card')
    expect(card).toHaveTextContent('non renseigné')
    expect(card).not.toHaveTextContent(/0,00\s€/)
    expect(screen.getByRole('link', { name: /Ajouter l'opération récurrente/ })).toHaveAttribute(
      'href',
      '/finance/recurring-operations',
    )
  })

  test('lists each account of the balance as bank · currency · name', async () => {
    const payload = dashboard({ monthlyCents: 300000, source: 'declared' })
    payload.balance.accounts = [
      { id: 'a1', name: 'Compte courant', bank: 'Boursorama', type: 'checking', balanceCents: 1000, currency: 'EUR', isCushion: false },
      { id: 'a2', name: 'Compte courant', bank: 'Crédit Agricole', type: 'checking', balanceCents: 2000, currency: 'EUR', isCushion: false },
      { id: 'a3', name: 'Livret', bank: null, type: 'savings', balanceCents: 3000, currency: 'EUR', isCushion: true },
    ] as never
    stubApi(payload)
    renderPage()

    expect(await screen.findByText('Boursorama · EUR · Compte courant')).toBeInTheDocument()
    expect(screen.getByText('Crédit Agricole · EUR · Compte courant')).toBeInTheDocument()
    expect(screen.getByText('EUR · Livret · matelas')).toBeInTheDocument()
  })

  test('shows no salary at all, and no 0 €, when the API fails', async () => {
    stubApi({ error: 'boom' }, false)
    renderPage()

    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByTestId('reference-salary-card')).not.toBeInTheDocument()
    expect(screen.queryByText(/0,00\s€/)).not.toBeInTheDocument()
  })
})

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { PeriodBalanceStep } from './PeriodBalanceStep'
import type { PeriodBalance } from './usePeriodBalance'

const BALANCE: PeriodBalance = {
  period: { key: '2027-Q1', level: 'quarter', from: '2027-01', to: '2027-03', months: 3 },
  months: [
    { month: '2027-01', budgetCents: 100000, spentCents: 80000, savedCents: 20000 },
    { month: '2027-02', budgetCents: 100000, spentCents: 110000, savedCents: 0 },
    { month: '2027-03', budgetCents: 100000, spentCents: 90000, savedCents: 10000 },
  ],
  totals: { budgetCents: 300000, spentCents: 280000, savedCents: 30000 },
  envelopes: [
    { categoryId: 'c1', name: 'Courses', budgetCents: 150000, consumedCents: 120000, percent: 80, status: 'held' },
    { categoryId: 'c2', name: 'Loisirs', budgetCents: 30000, consumedCents: 42000, percent: 140, status: 'over' },
  ],
  satisfaction: { judgedCents: 100000, keptCents: 75000, avoidableCents: 25000, keptPercent: 75 },
  savings: { savedCents: 30000, plannedCents: 60000 },
  projects: {
    done: [{ id: 'p1', label: 'Vélo', amountCents: 40000, currency: 'EUR', month: '2027-02', status: 'committed', categoryName: 'Loisirs' }],
    postponed: [{ id: 'p2', label: 'Dentiste', amountCents: 20000, currency: 'EUR', month: '2027-03', status: 'planned', categoryName: 'Santé' }],
  },
}

const fetchMock = vi.fn()
let answer: () => Promise<Partial<Response>>

describe('PeriodBalanceStep', () => {
  beforeEach(() => {
    answer = async () => ({ ok: true, json: async () => BALANCE })
    fetchMock.mockImplementation(() => answer())
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows the totals, the months, each envelope, the savings and the projects of the period', async () => {
    renderWithTheme(<PeriodBalanceStep month="2027-03" />)

    const step = await screen.findByTestId('review-period-balance')
    expect(fetchMock).toHaveBeenCalledWith('/api/finance/review/period-balance?month=2027-03', expect.anything())
    expect(step).toHaveAttribute('data-period', '2027-Q1')
    expect(within(step).getByText(/Bilan du trimestre · janvier à mars 2027/)).toBeInTheDocument()

    const totals = within(screen.getByTestId('period-totals'))
    expect(totals.getByText(/Dépensé : 2\s?800,00/)).toBeInTheDocument()
    expect(screen.getByTestId('period-curve')).toBeInTheDocument()
    expect(screen.getByTestId('period-line-spentCents')).toBeInTheDocument()

    const bars = screen.getAllByTestId('envelope-bar')
    expect(bars.map((bar) => [bar.dataset.name, bar.dataset.status])).toEqual([
      ['Courses', 'held'],
      ['Loisirs', 'over'],
    ])
    expect(screen.getByTestId('satisfaction-ring')).toBeInTheDocument()
    expect(screen.getByTestId('period-savings-text')).toHaveTextContent(/300,00.*épargnés sur.*600,00.*prévus/)
    expect(within(screen.getByTestId('period-projects-done')).getByText(/Vélo/)).toBeInTheDocument()
    expect(within(screen.getByTestId('period-projects-postponed')).getByText(/Dentiste/)).toBeInTheDocument()
  })

  test('an unknown capacity and an empty period say so instead of showing zeros', async () => {
    answer = async () => ({
      ok: true,
      json: async () => ({
        ...BALANCE,
        envelopes: [],
        savings: { savedCents: 0, plannedCents: null },
        projects: { done: [], postponed: [] },
      }),
    })
    renderWithTheme(<PeriodBalanceStep month="2027-03" />)

    expect(await screen.findByTestId('period-envelopes-empty')).toBeInTheDocument()
    expect(screen.getByTestId('period-savings-text')).toHaveTextContent('capacité inconnue')
    expect(screen.getByTestId('period-projects-empty')).toBeInTheDocument()
  })

  test('a bilan that cannot be read says so and can be read again', async () => {
    answer = async () => ({ ok: false, json: async () => ({}) })
    renderWithTheme(<PeriodBalanceStep month="2027-03" />)

    expect(await screen.findByText("Le bilan de la période n'a pas pu être chargé.")).toBeInTheDocument()
    answer = async () => ({ ok: true, json: async () => BALANCE })
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('review-period-balance')).toBeInTheDocument()
  })
})

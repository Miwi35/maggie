import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { BudgetStatusPanel } from './BudgetStatusPanel'
import type { BudgetLine, BudgetStatus } from './useBudgetStatus'

const notify = vi.fn()
const rollOver = vi.fn()
const refresh = vi.fn()
let status: BudgetStatus | null = null

vi.mock('../../hooks/useMercure', () => ({ useMercure: vi.fn() }))
vi.mock('./FinanceScoresSection', () => ({ FinanceScoresSection: () => null }))
vi.mock('./useRollOverEnvelopes', () => ({
  useRollOverEnvelopes: () => ({ rollOver, running: false }),
}))
vi.mock('react-admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-admin')>()),
  useNotify: () => notify,
}))

const line = (overrides: Partial<BudgetLine>): BudgetLine => ({
  id: 'x',
  categoryId: 'c',
  categoryName: 'X',
  categoryPath: 'X',
  parentEnvelopeId: null,
  mode: 'monthly',
  amountCents: 10000,
  currency: 'EUR',
  year: 2026,
  month: 10,
  spentCents: 0,
  committedCents: 0,
  plannedCents: 0,
  toArbitrateCents: 0,
  allocatedToChildrenCents: null,
  freeCents: null,
  consumedCents: 0,
  remainingCents: 10000,
  availableCents: 10000,
  isOverspent: false,
  isOvercommitted: false,
  ...overrides,
})

const statusOf = (budgets: BudgetLine[]): BudgetStatus => ({
  year: 2026,
  month: 10,
  totalBudgetedCents: 30000,
  totalSpentCents: 4000,
  totalCommittedCents: 0,
  totalPlannedCents: 0,
  totalConsumedCents: 4000,
  totalRemainingCents: 26000,
  totalAvailableCents: 26000,
  budgets,
})

const onYearChange = vi.fn()
const onMonthChange = vi.fn()

const renderPanel = () =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <BudgetStatusPanel
        year={2026}
        month={10}
        onYearChange={onYearChange}
        onMonthChange={onMonthChange}
        status={status}
        loading={false}
        refresh={refresh}
      />
    </AdminContext>,
  )

describe('BudgetStatusPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  test('nests an envelope under its mother with both consumptions', () => {
    status = statusOf([
      line({
        id: 'obsydian',
        categoryName: 'Obsydian',
        categoryPath: 'Loisirs › Bars › Obsydian',
        parentEnvelopeId: 'loisirs',
        amountCents: 10000,
        consumedCents: 4000,
        remainingCents: 6000,
        availableCents: 6000,
      }),
      line({
        id: 'loisirs',
        categoryName: 'Loisirs',
        categoryPath: 'Loisirs',
        amountCents: 30000,
        consumedCents: 4000,
        remainingCents: 26000,
        availableCents: 26000,
      }),
    ])

    renderPanel()

    const gauges = screen.getAllByTestId('budget-gauge')
    expect(gauges.map((g) => g.getAttribute('data-category'))).toEqual(['Loisirs', 'Obsydian'])
    expect(gauges.map((g) => g.getAttribute('data-depth'))).toEqual(['1', '2'])
    expect(within(gauges[1]).getByText(/Bars › Obsydian/)).toBeInTheDocument()
    expect(gauges[0]).toHaveTextContent(/40,00\s€\s\/\s300,00\s€/)
    expect(gauges[1]).toHaveTextContent(/40,00\s€\s\/\s100,00\s€/)
  })

  test('says overspent and overcommitted with a word and an icon, not only a colour', () => {
    status = statusOf([
      line({
        id: 'a',
        categoryName: 'Sorties',
        categoryPath: 'Sorties',
        consumedCents: 12000,
        remainingCents: -2000,
        availableCents: -2000,
        isOverspent: true,
        isOvercommitted: true,
      }),
      line({
        id: 'b',
        categoryName: 'Voyages',
        categoryPath: 'Voyages',
        consumedCents: 8000,
        plannedCents: 5000,
        remainingCents: 2000,
        availableCents: -3000,
        isOvercommitted: true,
      }),
    ])

    renderPanel()

    const [over, committed] = screen.getAllByTestId('budget-state')
    expect(over).toHaveAttribute('data-state', 'overspent')
    expect(over).toHaveTextContent(/Dépassement de 20,00\s€/)
    expect(over.parentElement?.querySelector('svg')).not.toBeNull()
    expect(committed).toHaveAttribute('data-state', 'overcommitted')
    expect(committed).toHaveTextContent(/Surengagement de 30,00\s€/)
    expect(committed.parentElement?.querySelector('svg')).not.toBeNull()
  })

  test('asks the screen to move the period, which it no longer owns', async () => {
    status = statusOf([line({ id: 'a' })])

    renderPanel()
    expect(screen.getByRole('heading', { name: 'Budgets' })).toBeInTheDocument()
    expect(screen.getByLabelText('Année')).toHaveValue(2026)
    await userEvent.type(screen.getByLabelText('Année'), '7')

    expect(onYearChange).toHaveBeenLastCalledWith(20267)
  })

  test('rolls the previous month over and refreshes', async () => {
    status = statusOf([line({ id: 'a', categoryName: 'Sorties', categoryPath: 'Sorties' })])
    rollOver.mockResolvedValue({ created: 2, skipped: 1 })

    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: /Reconduire le mois précédent/ }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('2 enveloppe(s) reconduite(s), 1 déjà en place', { type: 'info' }),
    )
    expect(refresh).toHaveBeenCalled()
  })

  test('tells the user when the rollover fails', async () => {
    status = statusOf([line({ id: 'a', categoryName: 'Sorties', categoryPath: 'Sorties' })])
    rollOver.mockResolvedValue(null)

    renderPanel()
    await userEvent.click(screen.getByRole('button', { name: /Reconduire le mois précédent/ }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Impossible de reconduire les enveloppes', { type: 'error' }),
    )
    expect(refresh).not.toHaveBeenCalled()
  })

  test('shows the annual and one-off envelopes apart, out of the month total', () => {
    status = {
      ...statusOf([line({ id: 'a', categoryName: 'Loisirs', categoryPath: 'Loisirs', amountCents: 30000, consumedCents: 2500 })]),
      outsideMonthlyBudget: {
        annual: [line({ id: 'v', categoryName: 'Voyages', categoryPath: 'Voyages', mode: 'annual', month: null, amountCents: 120000, consumedCents: 6000 })],
        oneOff: [
          {
            id: 'h',
            name: 'Hellfest 2026',
            mode: 'one_off',
            amountCents: 40000,
            currency: 'EUR',
            spentCents: 8900,
            committedCents: 0,
            plannedCents: 0,
            toArbitrateCents: 0,
            consumedCents: 8900,
            remainingCents: 31100,
            availableCents: 31100,
            isOverspent: false,
            isOvercommitted: false,
          },
        ],
      },
    }

    renderPanel()

    const outside = screen.getByTestId('outside-monthly-budget')
    expect(within(outside).getByRole('heading', { name: 'Hors budget mensuel' })).toBeInTheDocument()
    const gauges = within(outside).getAllByTestId('budget-gauge')
    expect(gauges.map((g) => g.dataset.category)).toEqual(['Voyages', 'Hellfest 2026'])
    expect(gauges[0]).toHaveTextContent('Année 2026')
    expect(gauges[1]).toHaveTextContent(/89,00\s€ \/ 400,00\s€/)
    expect(gauges[1]).toHaveTextContent('Ponctuelle')
  })

  test('draws no « Hors budget mensuel » section when there is nothing apart', () => {
    status = { ...statusOf([line({ id: 'a' })]), outsideMonthlyBudget: { annual: [], oneOff: [] } }

    renderPanel()

    expect(screen.queryByText('Hors budget mensuel')).not.toBeInTheDocument()
  })

  test('names the season budgeting the month', () => {
    status = statusOf([
      line({
        id: 'cinema',
        categoryName: 'Cinéma',
        categoryPath: 'Cinéma',
        month: 11,
        amountCents: 4500,
        season: { fromMonth: 11, toMonth: 2, amountCents: 4500 },
      }),
    ])

    renderPanel()

    const gauge = screen.getByTestId('budget-gauge')
    expect(within(gauge).getByText(/Novembre 2026 · saison nov\.–févr\./)).toBeInTheDocument()
    expect(within(gauge).getByText(/45,00/)).toBeInTheDocument()
  })

  test('opens the ruler of a mother and shares her amount between her daughters', async () => {
    status = statusOf([
      line({ id: 'loisirs', categoryName: 'Loisirs', categoryPath: 'Loisirs', amountCents: 30000, allocatedToChildrenCents: 15000, freeCents: 15000 }),
      line({ id: 'bars', categoryName: 'Bars', categoryPath: 'Loisirs › Bars', parentEnvelopeId: 'loisirs', amountCents: 10000 }),
      line({ id: 'cinema', categoryName: 'Cinéma', categoryPath: 'Loisirs › Cinéma', parentEnvelopeId: 'loisirs', amountCents: 5000 }),
    ])

    renderPanel()

    // Only the mother has a ruler.
    expect(screen.getAllByRole('button', { name: 'Répartir' })).toHaveLength(1)
    expect(screen.queryByTestId('envelope-split')).toBeNull()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Répartir' }))

    const ruler = screen.getByTestId('envelope-split')
    expect(within(ruler).getAllByRole('slider')).toHaveLength(2)
    expect(ruler).toHaveTextContent(/Libre 150,00\s€/)
  })
})

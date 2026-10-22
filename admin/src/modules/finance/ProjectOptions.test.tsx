import { beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProjectOptions } from './ProjectOptions'
import type { Project, ProjectOption } from './projectsApi'

const CAR: Project = {
  id: 'CAR',
  name: 'Voiture',
  amountCents: 1_000_000,
  currency: 'EUR',
  wellbeing: 'medium',
  wellbeingScore: 2,
  position: 0,
  dateMode: 'none',
  targetOn: null,
  dependsOn: [],
  note: null,
  unmetDependencies: [],
  fundingSources: [{ kind: 'loan', amountCents: 600_000, accountId: null, loanId: 'L1' }],
  salaryCents: 400_000,
}

const option = (overrides: Partial<ProjectOption>): ProjectOption => ({
  totalCostCents: 0,
  netCostCents: 0,
  monthlyPaymentCents: 0,
  durationMonths: 0,
  availableInMonths: 0,
  capitalKeptCents: 0,
  nImpactMonths: null,
  ...overrides,
})

const ANSWER = {
  projectId: 'CAR',
  amountCents: 1_000_000,
  netCapacityCents: 50_000,
  loanRateBasisPoints: 400,
  loanMonths: 24,
  downPaymentCents: 400_000,
  yieldBasisPoints: 300,
  cash: option({ totalCostCents: 1_000_000, availableInMonths: 20 }),
  loan: option({ totalCostCents: 1_042_200, netCostCents: 42_200, monthlyPaymentCents: 43_400, durationMonths: 24, availableInMonths: 0 }),
  partial: option({ totalCostCents: 1_025_300, netCostCents: 25_300, monthlyPaymentCents: 26_000, durationMonths: 24, availableInMonths: 8 }),
}

const stubOptions = (answer: Record<string, unknown> = ANSWER, status = 200) => {
  const fetchMock = vi.fn<typeof fetch>(async () => new Response(JSON.stringify(answer), { status }))
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const columns = () => screen.getAllByTestId('project-option')

describe('ProjectOptions', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.setItem('token', 'test-jwt')
  })

  test('prices the three options for the loan source and the default yield', async () => {
    const fetchMock = stubOptions()
    render(<ProjectOptions project={CAR} />)

    await waitFor(() => expect(columns()).toHaveLength(3))
    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost')
    expect(url.pathname).toBe('/api/finance/projects/CAR/options')
    expect(Object.fromEntries(url.searchParams)).toEqual({ downPaymentCents: '400000', yieldBasisPoints: '300' })

    expect(columns().map((column) => column.getAttribute('data-option'))).toEqual(['Comptant', 'Emprunt', 'Partiel'])
    expect(within(columns()[0]).getByText(/10\s000,00\s€/, { selector: 'p' })).toBeInTheDocument()
    expect(columns()[1]).toHaveTextContent(/10\s422,00\s€/)
    expect(columns()[2]).toHaveTextContent(/10\s253,00\s€/)
    expect(columns().map((column) => within(column).getByText(/Délai avant jouissance/).nextSibling?.textContent)).toEqual(['20 mois', 'Tout de suite', '8 mois'])
  })

  test('the three columns share one style and nothing recommends one of them', async () => {
    stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    const classes = columns().map((column) => column.className)
    expect(new Set(classes).size).toBe(1)
    expect(new Set(columns().map((column) => column.getAttribute('style'))).size).toBe(1)
    expect(screen.getByTestId('project-options').textContent).not.toMatch(/recommand|préférable|meilleur/i)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  test('the bars are on one scale for the three columns', async () => {
    stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    const widthOf = (column: HTMLElement, title: string) =>
      within(column).getByRole('img', { name: new RegExp(`^${title} :`) }).style.width ||
      getComputedStyle(within(column).getByRole('img', { name: new RegExp(`^${title} :`) })).width

    const totals = columns().map((column) => within(column).getByRole('img', { name: /^Coût total :/ }))
    // The widest cost is the loan's 10 422 €: the cash column's 10 000 € is shorter on the same scale.
    expect(totals).toHaveLength(3)
    expect(widthOf(columns()[0], 'Coût total')).not.toBe(widthOf(columns()[1], 'Coût total'))
  })

  test('shows the impact on N only once it is computed', async () => {
    stubOptions({ ...ANSWER, loan: { ...ANSWER.loan, nImpactMonths: 3 } })
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    expect(screen.getAllByText(/Impact sur N/)).toHaveLength(1)
    expect(columns()[1]).toHaveTextContent('Impact sur N : +3 mois')
  })

  test('asks again with the parameters typed', async () => {
    const user = userEvent.setup()
    const fetchMock = stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    await user.type(screen.getByLabelText(/Taux de l'emprunt/), '4,5')
    await user.type(screen.getByLabelText(/Durée de l'emprunt/), '36')
    await user.clear(screen.getByLabelText(/Rendement/))
    await user.type(screen.getByLabelText(/Rendement/), '2')

    await waitFor(() => {
      const last = new URL(String(fetchMock.mock.calls.at(-1)?.[0]), 'http://localhost')
      expect(Object.fromEntries(last.searchParams)).toEqual({
        loanRateBasisPoints: '450',
        loanMonths: '36',
        downPaymentCents: '400000',
        yieldBasisPoints: '200',
      })
    })
  })

  test('without a loan source nothing is priced until the loan and the down payment are given', async () => {
    const user = userEvent.setup()
    const fetchMock = stubOptions()
    render(<ProjectOptions project={{ ...CAR, fundingSources: [], salaryCents: 1_000_000 }} />)

    await new Promise((resolve) => setTimeout(resolve, 400))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.queryByTestId('project-options')).toBeNull()

    await user.type(screen.getByLabelText(/Taux de l'emprunt/), '4')
    await user.type(screen.getByLabelText(/Durée de l'emprunt/), '24')
    await user.type(screen.getByLabelText(/Apport/), '4000')

    await waitFor(() => expect(columns()).toHaveLength(3))
  })

  test("shows the API's refusal in its own words", async () => {
    stubOptions({ error: 'downPaymentCents must be from 0 to less than the project\'s 1000000 cents.' }, 400)
    render(<ProjectOptions project={CAR} />)

    expect(await screen.findByRole('alert')).toHaveTextContent("downPaymentCents must be from 0 to less than the project's 1000000 cents.")
    expect(screen.queryByTestId('project-options')).toBeNull()
  })
})

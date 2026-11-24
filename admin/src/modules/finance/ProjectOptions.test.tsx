import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
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
  fundingSources: [{ kind: 'loan', amountCents: 600_000, accountId: null, loanId: 'L1', sourceProjectId: null }],
  salaryCents: 400_000,
  investment: null,
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

const rowOf = (label: string) => screen.getByRole('row', { name: new RegExp(`^${label}`) })

const cellsOf = (label: string) => within(rowOf(label)).getAllByRole('cell').map((cell) => cell.textContent)

describe('ProjectOptions', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.setItem('token', 'test-jwt')
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 12))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('prices the three options for the loan source and the default yield', async () => {
    const fetchMock = stubOptions()
    render(<ProjectOptions project={CAR} />)

    await waitFor(() => expect(columns()).toHaveLength(3))
    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost')
    expect(url.pathname).toBe('/api/finance/projects/CAR/options')
    expect(Object.fromEntries(url.searchParams)).toEqual({ downPaymentCents: '400000', yieldBasisPoints: '300' })

    expect(columns().map((column) => column.getAttribute('data-option'))).toEqual(['Comptant', 'Emprunt', 'Partiel'])
    expect(screen.getByRole('table', { name: 'Options' })).toBeInTheDocument()
    expect(cellsOf('Coût réel total').map((text) => text?.replace(/\s/g, ' '))).toEqual(['Coût réel total', '10 000 €', '10 422 €', '10 253 €'])
  })

  test('the three columns share one style and nothing recommends one of them', async () => {
    stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    expect(new Set(columns().map((column) => column.className)).size).toBe(1)
    expect(new Set(columns().map((column) => column.getAttribute('style'))).size).toBe(1)
    const bars = screen.getAllByTestId('option-bar')
    expect(new Set(bars.map((bar) => bar.className.replace(/\s?css-\S+/g, ''))).size).toBe(1)
    expect(screen.getByTestId('project-options').textContent).not.toMatch(/recommand|préférable|meilleur/i)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  test('the bars are on one scale for the three columns', async () => {
    stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    const heights = screen.getAllByTestId('option-bar').map((bar) => bar.style.height)
    expect(heights).toHaveLength(3)
    expect(heights[1]).toBe('100%')
    expect(parseFloat(heights[0])).toBeLessThan(100)
    expect(parseFloat(heights[0])).toBeGreaterThan(90)
  })

  test('the rows of the mockup, in its order: Coût réel total, Coût net réel, Disponible, Mensualité, Impact sur N', async () => {
    stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    const labels = within(screen.getByRole('table', { name: 'Options' }))
      .getAllByRole('row')
      .slice(1)
      .map((row) => within(row).getAllByRole('cell')[0].textContent)
    expect(labels).toEqual(['Coût réel total', 'Coût net réel', 'Disponible', 'Mensualité', 'Impact sur N'])
  })

  test("« Disponible » is today's month plus the months to save: juin 2028, oct. 2026, juin 2027", async () => {
    stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    expect(cellsOf('Disponible').slice(1)).toEqual(['juin 2028', 'oct. 2026', 'juin 2027'])
  })

  test('« Mensualité » reads « — » for cash and « 434 € × 24 » for the loan', async () => {
    stubOptions()
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    expect(cellsOf('Mensualité').map((text) => text?.replace(/\s/g, ' '))).toEqual(['Mensualité', '—', '434 € × 24', '260 € × 24'])
  })

  test('« Impact sur N » reads « — » until it is computed, then « + 3 mois »', async () => {
    stubOptions({ ...ANSWER, loan: { ...ANSWER.loan, nImpactMonths: 3 } })
    render(<ProjectOptions project={CAR} />)
    await waitFor(() => expect(columns()).toHaveLength(3))

    expect(cellsOf('Impact sur N').map((text) => text?.replace(/\s/g, ' '))).toEqual(['Impact sur N', '—', '+ 3 mois', '—'])
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

import { beforeEach, describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProjectInvestmentCard } from './ProjectInvestmentCard'
import type { PlannedProject, Project, ProjectInvestment } from './projectsApi'

const project = (fields: Partial<Project> = {}): Project => ({
  id: 'BOX',
  name: 'Box à louer',
  amountCents: 1_800_000,
  currency: 'EUR',
  wellbeing: 'medium',
  wellbeingScore: 2,
  position: 0,
  dateMode: 'none',
  targetOn: null,
  dependsOn: [],
  note: null,
  unmetDependencies: [],
  fundingSources: [],
  salaryCents: 1_800_000,
  investment: null,
  ...fields,
})

const CAR = project({ id: 'CAR', name: 'Voiture', amountCents: 1_000_000 })

const INVESTMENT: ProjectInvestment = {
  priceCents: 1_800_000,
  downPaymentCents: 300_000,
  loanId: 'L1',
  loanPaymentCents: 14_000,
  monthlyRentCents: 12_000,
  monthlyBalanceCents: -2_000,
  resale: { month: '2028-06', netAmountCents: 1_000_000, targetProjectId: 'CAR' },
}
const BOX = project({ investment: INVESTMENT })

const planned = (rent: Partial<NonNullable<PlannedProject['investment']>> | null): PlannedProject => ({
  projectId: 'BOX',
  name: 'Box à louer',
  position: 0,
  wellbeing: 'medium',
  amountCents: 1_800_000,
  dateMode: 'none',
  targetOn: null,
  savingFromMonth: '2026-10',
  purchaseMonth: '2026-12',
  gatheredCents: 0,
  loan: null,
  resaleFromMonth: null,
  investment: rent && {
    monthlyRentCents: 12_000,
    monthlyBalanceCents: -2_000,
    rentFromMonth: '2027-01',
    rentUntilMonth: '2028-06',
    resaleMonth: '2028-06',
    resaleNetCents: 1_000_000,
    resaleTargetProjectId: 'CAR',
    ...rent,
  },
  isBeyondHorizon: false,
  isDeadlineMissed: false,
  delayMonths: 0,
  unmetDependencies: [],
})

interface Write {
  method: string
  url: string
  body: unknown
}

/** The loan's schedule and conditions, and the investment writes answered by `answer`. */
function stubApi(answer: (write: Write) => Response) {
  const writes: Write[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      if (url.endsWith('/investment')) {
        const write = { method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined }
        writes.push(write)

        return answer(write)
      }
      if (url.endsWith('/schedule')) {
        const months = Array.from({ length: 120 }, (_, index) => ({ month: `m${index}`, remainingCents: 1_500_000 - index * 12_000 }))

        return new Response(JSON.stringify({ months }), { status: 200 })
      }
      if (url.startsWith('/api/loans/')) return new Response(JSON.stringify({ annualRateBasisPoints: 350, durationMonths: 120 }), { status: 200 })

      return new Response('{}', { status: 404 })
    }),
  )

  return writes
}

const saved = (investment: ProjectInvestment | null) => (): Response =>
  new Response(JSON.stringify({ success: true, project: { id: 'BOX', name: 'Box à louer', amountCents: 1_800_000, investment } }), { status: 200 })

const renderCard = (target: Project, planning: PlannedProject | null = planned({}), onSaved = vi.fn()) => {
  render(<ProjectInvestmentCard project={target} projects={[target, CAR]} planned={planning} onSaved={onSaved} />)

  return onSaved
}

const figure = (id: string) =>
  screen.getAllByTestId('investment-figure').find((node) => node.getAttribute('data-figure') === id) as HTMLElement

describe('ProjectInvestmentCard', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.setItem('token', 'test-jwt')
  })

  test('shows the price, the down payment, the loan, the rent, the payment and what is left each month', async () => {
    stubApi(saved(null))
    renderCard(BOX)

    expect(screen.getByRole('heading', { name: 'Investissement · Box à louer' })).toBeInTheDocument()
    expect(figure('price')).toHaveTextContent(/18\s000,00\s€/)
    expect(figure('down-payment')).toHaveTextContent(/3\s000,00\s€/)
    expect(figure('loan')).toHaveTextContent(/15\s000,00\s€/)
    expect(figure('rent')).toHaveTextContent(/120,00\s€ \/ mois/)
    expect(figure('payment')).toHaveTextContent(/140,00\s€ \/ mois/)
    expect(figure('balance')).toHaveTextContent(/20,00\s€ \/ mois/)
    expect(figure('balance')).toHaveTextContent('Le reste est à votre charge.')
    expect(screen.getByTestId('investment-resale')).toHaveTextContent(/Revente en juin 2028 · net 10\s000,00\s€ · finance Voiture/)
    expect(screen.getByTestId('investment-rent-dates')).toHaveTextContent('Loyers perçus de janv. 2027 à juin 2028')
  })

  test('draws the capital owed against the rents, up to the resale', async () => {
    stubApi(saved(null))
    renderCard(BOX)

    const chart = await screen.findByTestId('investment-chart')
    expect(chart.querySelector('[data-testid="investment-chart-owed"]')?.getAttribute('points')?.split(' ')).toHaveLength(18)
  })

  test('says a resale that finances no project speeds up the waiting ones, and an undated rent', () => {
    stubApi(saved(null))
    renderCard(project({ investment: { ...INVESTMENT, resale: { month: '2028-06', netAmountCents: 500_000, targetProjectId: null } } }), planned({ rentFromMonth: null }))

    expect(screen.getByTestId('investment-resale')).toHaveTextContent('accélère les projets en attente')
    expect(screen.getByTestId('investment-rent-dates')).toHaveTextContent('Achat au-delà de 36 mois')
  })

  test('says there is no resale, and loyers run on while the project is held', () => {
    stubApi(saved(null))
    renderCard(project({ investment: { ...INVESTMENT, resale: null } }), planned({ rentUntilMonth: null, resaleMonth: null }))

    expect(screen.getByTestId('investment-resale')).toHaveTextContent('Aucune revente planifiée.')
    expect(screen.getByTestId('investment-rent-dates')).toHaveTextContent('Loyers perçus dès janv. 2027')
  })

  test('offers to define an investment on a plain project', () => {
    stubApi(saved(null))
    renderCard(project())

    expect(screen.getByRole('heading', { name: 'Investissement' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Définir un investissement' })).toBeInTheDocument()
    expect(screen.queryByTestId('investment-figure')).toBeNull()
  })

  test('creates the investment and hands the project the API answered to the page', async () => {
    const user = userEvent.setup()
    const writes = stubApi(saved(INVESTMENT))
    const onSaved = renderCard(project())

    await user.click(screen.getByRole('button', { name: 'Définir un investissement' }))
    const form = await screen.findByRole('form', { name: 'Investissement · Box à louer' })
    fireEvent.change(within(form).getByLabelText(/^Apport/), { target: { value: '3000' } })
    fireEvent.change(within(form).getByLabelText(/^Loyer mensuel/), { target: { value: '120' } })
    fireEvent.change(within(form).getByLabelText(/Taux annuel/), { target: { value: '3,5' } })
    fireEvent.change(within(form).getByLabelText(/Durée du prêt/), { target: { value: '120' } })
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(writes).toEqual([
      {
        method: 'PUT',
        url: '/api/finance/projects/BOX/investment',
        body: {
          priceCents: 1_800_000,
          downPaymentCents: 300_000,
          loan: { type: 'amortizing_fixed', annualRateBasisPoints: 350, durationMonths: 120 },
          monthlyRentCents: 12_000,
        },
      },
    ])
    expect(onSaved.mock.calls[0][0]).toMatchObject({ id: 'BOX', investment: { loanId: 'L1', monthlyRentCents: 12_000 } })
    expect(screen.queryByRole('form')).toBeNull()
  })

  test("keeps the form open with the API's refusal", async () => {
    const user = userEvent.setup()
    stubApi(() => new Response(JSON.stringify({ error: 'The down payment is more than the price.' }), { status: 400 }))
    const onSaved = renderCard(BOX)

    await user.click(screen.getByRole('button', { name: "Modifier l'investissement" }))
    const form = await screen.findByRole('form', { name: "Modifier l'investissement « Box à louer »" })
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(form).findByRole('alert')).toBeInTheDocument()
    expect(onSaved).not.toHaveBeenCalled()
  })

  test('removes the investment after a confirmation', async () => {
    const user = userEvent.setup()
    const writes = stubApi(saved(null))
    const onSaved = renderCard(BOX)

    await user.click(screen.getByRole('button', { name: "Supprimer l'investissement" }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent("Supprimer l'investissement « Box à louer » ?")
    expect(writes).toEqual([])
    await user.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(writes).toEqual([{ method: 'DELETE', url: '/api/finance/projects/BOX/investment', body: undefined }])
    expect(onSaved.mock.calls[0][0].investment).toBeNull()
  })

  test('keeps the investment when the removal is cancelled or refused', async () => {
    const user = userEvent.setup()
    stubApi(() => new Response(JSON.stringify({ error: 'Nope.' }), { status: 400 }))
    const onSaved = renderCard(BOX)

    await user.click(screen.getByRole('button', { name: "Supprimer l'investissement" }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Annuler' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await user.click(screen.getByRole('button', { name: "Supprimer l'investissement" }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Supprimer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Nope.')
    expect(onSaved).not.toHaveBeenCalled()
  })
})

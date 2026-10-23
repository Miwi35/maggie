import { beforeEach, describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { InvestmentDialog } from './InvestmentDialog'
import { ProjectError } from './projectsApi'
import type { InvestmentInput, Project, ProjectInvestment } from './projectsApi'

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
const BOX = project()

const INVESTMENT: ProjectInvestment = {
  priceCents: 1_800_000,
  downPaymentCents: 300_000,
  loanId: 'L1',
  loanPaymentCents: 14_000,
  monthlyRentCents: 12_000,
  monthlyBalanceCents: -2_000,
  resale: null,
}

const stubLoan = (conditions: Record<string, unknown> | null) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => (conditions ? new Response(JSON.stringify(conditions), { status: 200 }) : new Response('{}', { status: 404 }))),
  )

const renderDialog = (target: Project, onSubmit = vi.fn<(input: InvestmentInput) => Promise<void>>(async () => {}), onClose = vi.fn()) => {
  render(<InvestmentDialog project={target} projects={[BOX, CAR]} onClose={onClose} onSubmit={onSubmit} />)

  return { onSubmit, onClose }
}

const type = (label: string | RegExp, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })

describe('InvestmentDialog', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.setItem('token', 'test-jwt')
  })

  test('opens on the project price, asking for the loan conditions once something is borrowed', () => {
    renderDialog(BOX)

    expect(screen.getByRole('form', { name: 'Investissement · Box à louer' })).toBeInTheDocument()
    expect(screen.getByLabelText(/^Prix/)).toHaveValue('18000')
    expect(screen.queryByLabelText(/Taux annuel du prêt/)).toBeNull()

    type(/^Apport/, '3000')

    expect(screen.getByTestId('investment-borrowed')).toHaveTextContent(/Prêt de 15\s000,00\s€/)
    expect(screen.getByLabelText(/Taux annuel du prêt/)).toBeRequired()
    expect(screen.getByLabelText(/Durée du prêt/)).toBeRequired()
  })

  test('sends the investment with its loan and its resale', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog(BOX)

    type(/^Apport/, '3000')
    type(/^Loyer mensuel/, '120,50')
    type(/Taux annuel du prêt/, '3,5')
    type(/Durée du prêt/, '120')
    await user.click(screen.getByRole('checkbox', { name: 'Revente planifiée' }))
    type(/Mois de la revente/, '2028-06')
    type(/Net de la revente/, '10000')
    await user.click(screen.getByRole('combobox', { name: 'Projet financé par la revente' }))
    expect(screen.queryByRole('option', { name: 'Box à louer' })).toBeNull()
    await user.click(await screen.findByRole('option', { name: 'Voiture' }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit).toHaveBeenCalledWith({
      priceCents: 1_800_000,
      downPaymentCents: 300_000,
      loan: { type: 'amortizing_fixed', annualRateBasisPoints: 350, durationMonths: 120 },
      monthlyRentCents: 12_050,
      resale: { month: '2028-06', netAmountCents: 1_000_000, targetProjectId: 'CAR' },
    })
  })

  test('sends nothing and says what is missing', async () => {
    const user = userEvent.setup()
    const { onSubmit } = renderDialog(BOX)

    type(/^Apport/, '3000')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(screen.getByText('Indiquez un taux de 0 à 50 %.')).toBeInTheDocument()
    expect(screen.getByText('Indiquez une durée de 1 à 600 mois.')).toBeInTheDocument()
    expect(screen.getByText(/Indiquez le loyer mensuel/)).toBeInTheDocument()
    expect(onSubmit).not.toHaveBeenCalled()
  })

  test("shows the API's refusal above the form and keeps it open", async () => {
    const user = userEvent.setup()
    const refusing = vi.fn<(input: InvestmentInput) => Promise<void>>(async () => {
      throw new ProjectError('Le net de la revente dépasse le prix.')
    })
    const { onClose } = renderDialog(BOX, refusing)

    type(/^Apport/, '18000')
    type(/^Loyer mensuel/, '0')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Le net de la revente dépasse le prix.')
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeEnabled()
  })

  test('edits an investment from the loan conditions it was taken with', async () => {
    const user = userEvent.setup()
    stubLoan({ annualRateBasisPoints: 350, durationMonths: 120 })
    const { onSubmit } = renderDialog(project({ investment: INVESTMENT }))

    expect(screen.getByRole('form', { name: "Modifier l'investissement « Box à louer »" })).toBeInTheDocument()
    expect(screen.getByLabelText(/^Apport/)).toHaveValue('3000')
    await waitFor(() => expect(screen.getByLabelText(/Taux annuel du prêt/)).toHaveValue('3,5'))
    expect(screen.getByLabelText(/Durée du prêt/)).toHaveValue('120')

    type(/^Loyer mensuel/, '130')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit).toHaveBeenCalledWith({
      priceCents: 1_800_000,
      downPaymentCents: 300_000,
      loan: { type: 'amortizing_fixed', annualRateBasisPoints: 350, durationMonths: 120 },
      monthlyRentCents: 13_000,
    })
  })

  test('says when the loan conditions could not be read, and keeps the stored ones when left empty', async () => {
    const user = userEvent.setup()
    stubLoan(null)
    const { onSubmit } = renderDialog(project({ investment: INVESTMENT }))

    expect(await screen.findByText(/Les conditions du prêt n'ont pas pu être lues/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0]).not.toHaveProperty('loan')
  })

  test('closes on cancel', async () => {
    const user = userEvent.setup()
    const { onClose } = renderDialog(BOX)

    await user.click(screen.getByRole('button', { name: 'Annuler' }))

    expect(onClose).toHaveBeenCalled()
  })
})

import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import { HttpError } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { LoanCreate } from './LoanCreate'
import { LOAN_TYPES, PENALTY_MODES } from './loans'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const renderCreate = (create = vi.fn().mockResolvedValue({ data: { id: '/api/loans/1' } })) => {
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider({ create })} i18nProvider={i18nProvider}>
        <ResourceContextProvider value="loans">
          <LoanCreate />
        </ResourceContextProvider>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

  return create
}

const chooseType = async (user: ReturnType<typeof userEvent.setup>, name: string) => {
  await user.click(await screen.findByRole('combobox', { name: /Type de prêt/ }))
  await user.click(await screen.findByRole('option', { name }))
}

const fillTheLoan = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.type(await screen.findByLabelText(/Nom/), 'Prêt immo')
  await user.type(screen.getByLabelText(/Capital restant dû/), '98000')
  await user.type(screen.getByLabelText(/Mensualité \(€\)/), '625')
}

describe('LoanForm, conditions by type', () => {
  test('a fixed-rate loan shows none of the type-specific fields', async () => {
    renderCreate()

    expect(await screen.findByRole('combobox', { name: /Type de prêt/ })).toHaveTextContent('Amortissable taux fixe')
    expect(screen.queryByLabelText(/Indice/)).not.toBeInTheDocument()
    expect(screen.queryByText('Paliers')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Durée du différé/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Montant de l'IRA/)).not.toBeInTheDocument()
  })

  test('a variable loan shows the index, margin, cap and floor', async () => {
    const user = userEvent.setup()
    renderCreate()

    await chooseType(user, 'Amortissable taux variable')

    for (const label of [/Indice/, /Marge/, /Plafond du taux/, /Plancher du taux/]) {
      expect(await screen.findByLabelText(label)).toBeInTheDocument()
    }
  })

  test('a stepped loan shows the steps', async () => {
    const user = userEvent.setup()
    renderCreate()

    await chooseType(user, 'À paliers')

    expect(await screen.findByText('Paliers')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Indice/)).not.toBeInTheDocument()
  })

  test('a total deferral shows its duration and hides the steps', async () => {
    const user = userEvent.setup()
    renderCreate()

    await chooseType(user, 'À paliers')
    expect(await screen.findByText('Paliers')).toBeInTheDocument()

    await chooseType(user, 'Différé total')

    expect(await screen.findByLabelText(/Durée du différé/)).toBeInTheDocument()
    expect(screen.queryByText('Paliers')).not.toBeInTheDocument()
  })

  test('the fixed penalty amount only shows with the fixed mode', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(await screen.findByRole('combobox', { name: /^IRA/ }))
    await user.click(await screen.findByRole('option', { name: 'Montant fixe' }))

    expect(await screen.findByLabelText(/Montant de l'IRA/)).toBeInTheDocument()
  })

  test('the end date is shown, never typed', async () => {
    renderCreate()

    expect(await screen.findByTestId('loan-end-date')).toHaveTextContent('Fin')
    expect(screen.queryByLabelText(/Date de fin/)).not.toBeInTheDocument()
  })

  test('the type and the penalty mode are sent as chosen, in cents and basis points', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await fillTheLoan(user)
    await chooseType(user, 'In fine')
    await user.type(screen.getByLabelText(/Durée \(mois\)/), '120')
    await user.click(screen.getByRole('button', { name: /Enregistrer/ }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({
      name: 'Prêt immo',
      principalRemainingCents: 9800000,
      monthlyPaymentCents: 62500,
      type: 'in_fine',
      durationMonths: 120,
      prepaymentPenaltyMode: 'legal_cap',
    })
  })

  test('saving a total deferral with no duration shows the error the API answered', async () => {
    const user = userEvent.setup()
    const create = renderCreate(
      vi.fn().mockRejectedValue(new HttpError('type deferred_total requires deferralMonths above 0.', 400)),
    )

    await fillTheLoan(user)
    await chooseType(user, 'Différé total')
    await user.click(screen.getByRole('button', { name: /Enregistrer/ }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(await screen.findByText('type deferred_total requires deferralMonths above 0.')).toBeInTheDocument()
  })

  test('no type or penalty mode is recommended', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(await screen.findByRole('combobox', { name: /Type de prêt/ }))
    await screen.findByRole('option', { name: 'Différé total' })
    const text = [...LOAN_TYPES, ...PENALTY_MODES].map((choice) => choice.name).join(' ') + document.body.textContent

    expect(text).not.toMatch(/conseillé|préférable/i)
  })
})

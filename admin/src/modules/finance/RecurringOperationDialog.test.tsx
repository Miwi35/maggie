import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { RecurringOperationDialog } from './RecurringOperationDialog'

const getList = vi.fn(async (resource: string) => {
  if (resource === 'accounts') return { data: [{ id: '/api/accounts/01ACC', name: 'Courant' }], total: 1 }

  return {
    data: [
      { id: '/api/categories/01SUB', name: 'Abonnements', obligation: 'optional' },
      { id: '/api/categories/01PAY', name: 'Salaire', obligation: 'income' },
    ],
    total: 2,
  }
})

const create = vi.fn(async (_resource: string, params: { data: Record<string, unknown> }) => ({
  data: { id: '/api/recurring_operations/01NEW', ...params.data },
}))
const update = vi.fn(async (_resource: string, params: { id: string; data: Record<string, unknown> }) => ({
  data: { id: params.id, ...params.data },
}))

const renderDialog = (props: Partial<React.ComponentProps<typeof RecurringOperationDialog>> = {}) => {
  const onClose = vi.fn()
  const onSaved = vi.fn()
  render(
    <AdminContext
      dataProvider={testDataProvider({
        getList: getList as unknown as DataProvider['getList'],
        create: create as unknown as DataProvider['create'],
        update: update as unknown as DataProvider['update'],
      })}
    >
      <RecurringOperationDialog onClose={onClose} onSaved={onSaved} {...props} />
    </AdminContext>,
  )

  return { onClose, onSaved }
}

const pick = async (label: RegExp | string, option: string) => {
  await userEvent.click(screen.getByRole('combobox', { name: label }))
  await userEvent.click(await screen.findByRole('option', { name: option }))
}

describe('RecurringOperationDialog', () => {
  beforeEach(() => {
    create.mockClear()
    update.mockClear()
  })

  test('opens with the default tolerances: 20 % on the amount, 5 days on the date', () => {
    renderDialog()

    expect(screen.getByLabelText('Tolérance sur le montant (%)')).toHaveValue(20)
    expect(screen.getByLabelText('Tolérance sur la date (jours)')).toHaveValue(5)
    expect(screen.getByRole('heading', { name: 'Nouvelle opération récurrente' })).toBeInTheDocument()
  })

  test('submitting an empty form names what is missing and writes nothing', async () => {
    renderDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))

    expect(await screen.findByText('Donnez un nom à la série')).toBeInTheDocument()
    expect(screen.getByText('Saisissez un montant supérieur à 0')).toBeInTheDocument()
    expect(screen.getByText('Choisissez un compte')).toBeInTheDocument()
    expect(screen.getByText('Choisissez une catégorie')).toBeInTheDocument()
    expect(screen.getByText('Indiquez la date d’une échéance')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })

  test('creates the series with a signed reference amount, and tells the page', async () => {
    const { onSaved } = renderDialog()

    await userEvent.type(screen.getByLabelText(/Libellé/), 'Abonnement Flixo')
    await userEvent.type(screen.getByLabelText('Contrepartie attendue'), 'Flixo')
    await userEvent.type(screen.getByLabelText(/Montant de référence/), '13.49')
    await pick(/Compte/, 'Courant')
    await pick(/Catégorie/, 'Abonnements')
    await userEvent.type(screen.getByLabelText(/Date d’une échéance/), '2026-10-12')
    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][0]).toBe('recurring_operations')
    expect(create.mock.calls[0][1].data).toEqual({
      label: 'Abonnement Flixo',
      counterpartyName: 'Flixo',
      account: '/api/accounts/01ACC',
      category: '/api/categories/01SUB',
      period: 'monthly',
      dayRule: 'fixed_day',
      anchorOn: '2026-10-12',
      referenceAmountCents: -1349,
      referenceSource: 'declared',
      amountTolerancePercent: 20,
      dateToleranceDays: 5,
      endsOn: null,
    })
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
  })

  test('an amount taken from a line stays measured; typing another one declares it', async () => {
    renderDialog({
      initial: {
        label: 'Flixo',
        referenceAmountCents: -1349,
        account: '/api/accounts/01ACC',
        category: '/api/categories/01SUB',
        anchorOn: '2026-10-12',
      },
    })

    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).not.toHaveProperty('referenceSource')

    await userEvent.clear(screen.getByLabelText(/Montant de référence/))
    await userEvent.type(screen.getByLabelText(/Montant de référence/), '17.99')
    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))
    await waitFor(() => expect(create).toHaveBeenCalledTimes(2))
    expect(create.mock.calls[1][1].data).toMatchObject({ referenceAmountCents: -1799, referenceSource: 'declared' })
  })

  test('a recette offers recette categories only', async () => {
    renderDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Recette' }))
    await userEvent.click(screen.getByRole('combobox', { name: /Catégorie/ }))

    const options = await screen.findAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['Salaire'])
  })

  test('editing opens filled, updates the series and keeps its tolerances', async () => {
    const { onSaved } = renderDialog({
      recordId: '/api/recurring_operations/01OP',
      initial: {
        label: 'Loyer',
        counterpartyName: 'Agence',
        referenceAmountCents: -80000,
        account: '/api/accounts/01ACC',
        category: '/api/categories/01SUB',
        dayRule: 'last_day_of_month',
        anchorOn: '2026-09-30',
        amountTolerancePercent: 10,
        dateToleranceDays: 2,
      },
    })

    expect(screen.getByLabelText(/Libellé/)).toHaveValue('Loyer')
    expect(screen.getByLabelText(/Montant de référence/)).toHaveValue(800)
    expect(screen.getByLabelText('Tolérance sur le montant (%)')).toHaveValue(10)
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].id).toBe('/api/recurring_operations/01OP')
    expect(update.mock.calls[0][1].data).toMatchObject({
      label: 'Loyer',
      dayRule: 'last_day_of_month',
      referenceAmountCents: -80000,
      amountTolerancePercent: 10,
      dateToleranceDays: 2,
    })
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
  })

  test('an API refusal is shown in the dialog and nothing is reported saved', async () => {
    create.mockRejectedValueOnce(new Error('Une série existe déjà'))
    const { onSaved } = renderDialog({
      initial: {
        label: 'Flixo',
        referenceAmountCents: -1349,
        account: '/api/accounts/01ACC',
        category: '/api/categories/01SUB',
        anchorOn: '2026-10-12',
      },
    })

    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))

    expect(await screen.findByText('Une série existe déjà')).toBeInTheDocument()
    expect(onSaved).not.toHaveBeenCalled()
  })

  test('cancelling closes without writing', async () => {
    const { onClose } = renderDialog()

    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Annuler' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

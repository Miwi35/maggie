import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { RecurringOperationDialog } from './RecurringOperationDialog'

const getList = vi.fn(async (resource: string, params?: { filter?: Record<string, unknown> }) => {
  if (resource === 'accounts') {
    return {
      data: [
        { id: '/api/accounts/01ACC', name: 'Courant', bank: 'Crédit Agricole', currency: 'EUR' },
        { id: '/api/accounts/01BOU', name: 'Courant', bank: 'Boursorama', currency: 'EUR' },
        { id: '/api/accounts/01LIV', name: 'Livret', bank: null, currency: 'EUR' },
      ],
      total: 3,
    }
  }
  if (resource === 'transactions') return { data: [], total: params?.filter?.qualifiedBySeries ? 3 : 0 }

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

const MATCH = { transactionId: 't1', label: 'VIR SEPA XYZ', amountCents: 465000, bookedAt: '2026-10-02', accountId: '01ACC' }

const fetchMock = vi.fn()
const respond = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body } as Response)
const previewBodies = () => fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string))

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
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation(() => respond(true, { total: 0, matches: [] }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
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

  test('offers each account as bank · currency · name, so two accounts of the same name differ', async () => {
    renderDialog()

    await userEvent.click(screen.getByRole('combobox', { name: /Compte/ }))

    const options = (await screen.findAllByRole('option')).map((option) => option.textContent)
    expect(options).toEqual(['Crédit Agricole · EUR · Courant', 'Boursorama · EUR · Courant', 'EUR · Livret'])
  })

  test('creates the series with a signed reference amount, and tells the page', async () => {
    const { onSaved } = renderDialog()

    await userEvent.type(screen.getByLabelText(/Libellé/), 'Abonnement Flixo')
    await userEvent.type(screen.getByLabelText('Contrepartie attendue'), 'Flixo')
    await userEvent.type(screen.getByLabelText(/Montant de référence/), '13.49')
    await pick(/Compte/, 'Crédit Agricole · EUR · Courant')
    await pick(/Catégorie/, 'Abonnements')
    await userEvent.type(screen.getByLabelText(/Date d’une échéance/), '2026-10-12')
    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][0]).toBe('recurring_operations')
    expect(create.mock.calls[0][1].data).toEqual({
      label: 'Abonnement Flixo',
      counterpartyName: 'Flixo',
      labelPattern: null,
      account: '/api/accounts/01ACC',
      category: '/api/categories/01SUB',
      qualification: { mandatory: false, kind: 'none' },
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
        counterpartyName: 'Flixo',
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

  test('a recette offers every category, a Salaire series included', async () => {
    renderDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Recette' }))
    await userEvent.click(screen.getByRole('combobox', { name: /Catégorie/ }))

    const options = await screen.findAllByRole('option')
    expect(options.map((o) => o.textContent)).toEqual(['Abonnements', 'Salaire'])
  })

  test('the qualification chosen with the category is what the series is sent', async () => {
    renderDialog()

    await userEvent.type(screen.getByLabelText(/Libellé/), 'Crédit auto')
    await userEvent.type(screen.getByLabelText('Contrepartie attendue'), 'Banque Auto')
    await userEvent.type(screen.getByLabelText(/Montant de référence/), '250')
    await pick(/Compte/, 'Crédit Agricole · EUR · Courant')
    await pick(/Catégorie/, 'Abonnements')
    await userEvent.click(screen.getByRole('switch', { name: 'Obligatoire' }))
    await pick('Type', 'Remboursement')
    await userEvent.type(screen.getByLabelText(/Date d’une échéance/), '2026-10-05')
    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({
      category: '/api/categories/01SUB',
      qualification: { mandatory: true, kind: 'repayment' },
    })
  })

  test('on a recette, remboursement reads « on me rembourse » — the sense comes from the amount', async () => {
    renderDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Recette' }))
    await userEvent.type(screen.getByLabelText(/Montant de référence/), '40')
    await pick('Type', 'Remboursement')

    expect(screen.queryByText(/Remboursement : ici/)).toBeNull()
  })

  test('editing says how many attached lines will be requalified, once the qualification changes', async () => {
    renderDialog({
      recordId: '/api/recurring_operations/01OP',
      initial: {
        label: 'Crédit auto',
        counterpartyName: 'Banque Auto',
        referenceAmountCents: -25000,
        account: '/api/accounts/01ACC',
        category: '/api/categories/01SUB',
        qualification: { mandatory: true, kind: 'repayment' },
        anchorOn: '2026-10-05',
      },
    })

    expect(screen.queryByText(/seront requalifiées/)).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('switch', { name: 'Obligatoire' }))

    expect(await screen.findByText(/3 transactions rattachées seront requalifiées/)).toBeInTheDocument()
    expect(screen.getByText(/qualifiées à la main ne bougent pas/)).toBeInTheDocument()
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
        counterpartyName: 'Flixo',
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

  test('shows the label pattern field, without a help text (MAG-634)', () => {
    renderDialog()

    expect(screen.getByLabelText(/Motif du libellé/)).toBeInTheDocument()
    expect(screen.queryByText(/reconnaît les transactions dont le libellé contient/i)).toBeNull()
  })

  test('with neither a payee nor a label pattern, the amount and its sign are enough', async () => {
    renderDialog({
      initial: {
        label: 'Salaire',
        referenceAmountCents: 475000,
        account: '/api/accounts/01ACC',
        category: '/api/categories/01PAY',
        anchorOn: '2026-10-28',
        amountTolerancePercent: 10,
      },
    })

    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({
      counterpartyName: null,
      labelPattern: null,
      referenceAmountCents: 475000,
      amountTolerancePercent: 10,
    })
    expect(screen.queryByText(/Indique une contrepartie/)).not.toBeInTheDocument()
  })

  test('a label pattern alone is enough, and is sent trimmed', async () => {
    renderDialog({
      initial: {
        label: 'Sas Jardis',
        referenceAmountCents: -5000,
        account: '/api/accounts/01ACC',
        category: '/api/categories/01SUB',
        anchorOn: '2026-10-12',
      },
    })

    await userEvent.type(screen.getByLabelText(/Motif du libellé/), '  Sas Jardis ')
    await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({ counterpartyName: null, labelPattern: 'Sas Jardis' })
  })

  describe('preview of the transactions the series would recognise', () => {
    const salary = {
      label: 'Salaire',
      referenceAmountCents: 475000,
      account: '/api/accounts/01ACC',
      category: '/api/categories/01PAY',
      anchorOn: '2026-10-28',
      amountTolerancePercent: 10,
    }

    test('asks nothing until an amount is typed', async () => {
      renderDialog()

      expect(await screen.findByText(/Saisissez un montant pour voir les transactions/)).toBeInTheDocument()
      expect(fetchMock).not.toHaveBeenCalled()
    })

    test('lists the transactions recognised by the amount alone, with their total', async () => {
      fetchMock.mockImplementation(() => respond(true, { total: 1, matches: [MATCH] }))
      renderDialog({ initial: salary })

      const panel = await screen.findByRole('region', { name: 'Transactions reconnues' })
      expect(await within(panel).findByText('VIR SEPA XYZ')).toBeInTheDocument()
      expect(within(panel).getByText('1 transaction(s) reconnue(s)')).toBeInTheDocument()
      expect(fetchMock.mock.calls[0][0]).toBe('/api/finance/recurring-operations/preview')
      expect(previewBodies()[0]).toEqual({ referenceAmountCents: 475000, amountTolerancePercent: 10, accountId: '01ACC' })
    })

    test('follows the amount, the sign, the tolerance and the identity as they are typed', async () => {
      renderDialog({ initial: salary })
      await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

      await userEvent.click(screen.getByRole('button', { name: 'Dépense' }))
      await userEvent.type(screen.getByLabelText('Contrepartie attendue'), ' Acme ')

      await waitFor(() =>
        expect(previewBodies().at(-1)).toEqual({
          referenceAmountCents: -475000,
          amountTolerancePercent: 10,
          counterpartyName: 'Acme',
          accountId: '01ACC',
        }),
      )
    })

    test('says when nothing is recognised', async () => {
      renderDialog({ initial: salary })

      expect(await screen.findByText('Aucune transaction reconnue')).toBeInTheDocument()
    })

    test('reports a refused preview without blocking the save', async () => {
      fetchMock.mockImplementation(() => respond(false, { error: 'Tolérance invalide.' }))
      renderDialog({ initial: salary })

      expect(await screen.findByText(/Tolérance invalide\./)).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Créer la série' }))
      await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    })

    test('reports an unreachable preview without blocking the save', async () => {
      fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
      renderDialog({ initial: salary })

      expect(await screen.findByText(/L'aperçu est indisponible/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Créer la série' })).toBeEnabled()
    })
  })

  test('cancelling closes without writing', async () => {
    const { onClose } = renderDialog()

    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Annuler' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

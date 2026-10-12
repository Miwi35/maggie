import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ListContextProvider, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MakeRecurringButton, RecurringBadge } from './RecurringTransactionActions'

interface Call {
  method: string
  url: string
  body: unknown
}

const calls: Call[] = []
const refetch = vi.fn()
let attachReport: unknown

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  calls.push({ method: init?.method ?? 'GET', url, body: init?.body ? JSON.parse(init.body as string) : undefined })
  const dryRun = init?.body ? (JSON.parse(init.body as string) as { dryRun?: boolean }).dryRun : undefined

  if (url.endsWith('/attach')) return { ok: true, status: 200, json: async () => ({ ...(attachReport as object), dryRun }) } as Response
  if (url.startsWith('/api/transactions/')) return { ok: !refuse, status: refuse ? 422 : 200, json: async () => ({}) } as Response

  return { ok: false, status: 404, json: async () => ({}) } as Response
})
let refuse = false

const getOne = vi.fn(async () => ({ data: { id: '/api/recurring_operations/01FLIXO', label: 'Abonnement Flixo' } }))
const getList = vi.fn(async (resource: string) =>
  resource === 'accounts'
    ? { data: [{ id: '/api/accounts/01ACC', name: 'Courant' }], total: 1 }
    : { data: [{ id: '/api/categories/01SUB', name: 'Abonnements', obligation: 'optional' }], total: 1 },
)
const create = vi.fn(async (_resource: string, params: { data: Record<string, unknown> }) => ({
  data: { id: '/api/recurring_operations/01NEW', ...params.data },
}))

const renderWith = (ui: React.ReactNode) =>
  render(
    <AdminContext
      dataProvider={testDataProvider({
        getOne: getOne as unknown as DataProvider['getOne'],
        getList: getList as unknown as DataProvider['getList'],
        create: create as unknown as DataProvider['create'],
      })}
    >
      <ListContextProvider value={{ refetch } as never}>{ui}</ListContextProvider>
      <Notification />
    </AdminContext>,
  )

const TRANSACTION = {
  id: '/api/transactions/01TX',
  label: 'PRLV FLIXO',
  counterpartyName: 'Flixo',
  amountCents: -1349,
  bookedAt: '2026-10-12T00:00:00+00:00',
  account: '/api/accounts/01ACC',
  category: '/api/categories/01SUB',
  recurringOperation: null,
}

const line = (id: string, bookedAt: string, seriesId: string | null = '01NEW') => ({
  transactionId: id,
  label: `FLIXO ${bookedAt}`,
  bookedAt,
  amountCents: -1349,
  reason: null,
  recurringOperationId: seriesId,
  recurringOperationLabel: 'Flixo',
  occurrenceOn: bookedAt,
})

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  refuse = false
  attachReport = { dryRun: true, scanned: 0, attached: 0, proposed: 0, attachments: [], proposals: [] }
})

afterEach(() => {
  vi.unstubAllGlobals()
  calls.length = 0
  refetch.mockClear()
  getOne.mockClear()
  create.mockClear()
})

describe('RecurringBadge', () => {
  test('a line with no series shows nothing', () => {
    renderWith(<RecurringBadge record={TRANSACTION} />)

    expect(screen.queryByText(/Série ·/)).not.toBeInTheDocument()
    expect(getOne).not.toHaveBeenCalled()
  })

  test('a line of a series carries the name of the series', async () => {
    renderWith(<RecurringBadge record={{ ...TRANSACTION, recurringOperation: '/api/recurring_operations/01FLIXO' }} />)

    expect(await screen.findByText('Série · Abonnement Flixo')).toBeInTheDocument()
    expect(getOne).toHaveBeenCalledWith('recurring_operations', expect.objectContaining({ id: '/api/recurring_operations/01FLIXO' }))
  })

  test('detaching patches the line free and refreshes the list', async () => {
    renderWith(<RecurringBadge record={{ ...TRANSACTION, recurringOperation: '/api/recurring_operations/01FLIXO' }} />)
    await screen.findByText('Série · Abonnement Flixo')

    await userEvent.click(screen.getByLabelText('Détacher PRLV FLIXO de la série'))

    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'PATCH',
        url: '/api/transactions/01TX',
        body: { recurringOperation: null, recurringOccurrenceOn: null },
      }),
    )
    await waitFor(() => expect(refetch).toHaveBeenCalled())
    expect(await screen.findByText(/n’est plus rattachée à « Abonnement Flixo »/)).toBeInTheDocument()
  })

  test('a refused detachment says so and keeps the list as it is', async () => {
    refuse = true
    renderWith(<RecurringBadge record={{ ...TRANSACTION, recurringOperation: '/api/recurring_operations/01FLIXO' }} />)
    await screen.findByText('Série · Abonnement Flixo')

    await userEvent.click(screen.getByLabelText('Détacher PRLV FLIXO de la série'))

    expect(await screen.findByText('Impossible de détacher cette transaction')).toBeInTheDocument()
    expect(refetch).not.toHaveBeenCalled()
  })
})

describe('MakeRecurringButton', () => {
  const open = async () => {
    await userEvent.click(screen.getByRole('button', { name: 'En faire une opération récurrente : PRLV FLIXO' }))

    return screen.findByRole('dialog')
  }

  test('a line already in a series does not offer it', () => {
    renderWith(<MakeRecurringButton record={{ ...TRANSACTION, recurringOperation: '/api/recurring_operations/01FLIXO' }} />)

    expect(screen.queryByRole('button', { name: /En faire une opération récurrente/ })).not.toBeInTheDocument()
  })

  test('opens the form filled from the line: payee, amount, day, account and category', async () => {
    renderWith(<MakeRecurringButton record={TRANSACTION} />)

    const dialog = await open()

    expect(within(dialog).getByLabelText(/Libellé/)).toHaveValue('Flixo')
    expect(within(dialog).getByLabelText('Contrepartie attendue')).toHaveValue('Flixo')
    expect(within(dialog).getByLabelText(/Montant de référence/)).toHaveValue(13.49)
    expect(within(dialog).getByLabelText(/Date d’une échéance/)).toHaveValue('2026-10-12')
    expect(within(dialog).getByLabelText('Tolérance sur le montant (%)')).toHaveValue(20)
    await waitFor(() => expect(within(dialog).getByRole('combobox', { name: /Compte/ })).toHaveTextContent('Courant'))
    expect(within(dialog).getByRole('combobox', { name: /Catégorie/ })).toHaveTextContent('Abonnements')
  })

  test('clicking inside the dialog does not reach the row, whose click opens the edit page', async () => {
    const rowClick = vi.fn()
    renderWith(
      <div onClick={rowClick}>
        <MakeRecurringButton record={TRANSACTION} />
      </div>,
    )

    const dialog = await open()
    await userEvent.click(within(dialog).getByLabelText(/Libellé/))

    expect(rowClick).not.toHaveBeenCalled()
  })

  test('a line on the last day of its month opens the series on « last day of the month »', async () => {
    renderWith(<MakeRecurringButton record={{ ...TRANSACTION, bookedAt: '2026-09-30T00:00:00+00:00' }} />)

    const dialog = await open()

    expect(within(dialog).getByRole('combobox', { name: /Jour attendu/ })).toHaveTextContent('Le dernier jour du mois')
  })

  test('after saving, proposes the past lines of the same payee with a preview before writing', async () => {
    attachReport = {
      dryRun: true,
      scanned: 20,
      attached: 3,
      proposed: 0,
      attachments: [line('01A', '2026-01-12'), line('01B', '2026-02-12'), line('01C', '2026-03-12')],
      proposals: [],
    }
    renderWith(<MakeRecurringButton record={TRANSACTION} />)
    const form = await open()
    await waitFor(() => expect(within(form).getByRole('combobox', { name: /Compte/ })).toHaveTextContent('Courant'))
    await userEvent.clear(within(form).getByLabelText(/Libellé/))
    await userEvent.type(within(form).getByLabelText(/Libellé/), 'Abonnement Flixo')
    await userEvent.click(within(form).getByRole('button', { name: 'Créer la série' }))

    const preview = await screen.findByRole('dialog', { name: 'Rattacher l’historique à cette série' })
    expect(within(preview).getByText(/3 transactions Abonnement Flixo depuis janvier, les rattacher \?/)).toBeInTheDocument()
    expect(calls.filter((c) => c.url.endsWith('/attach')).map((c) => c.body)).toEqual([{ dryRun: true }])

    await userEvent.click(within(preview).getByRole('button', { name: /Rattacher ces 3 transaction/ }))

    await waitFor(() =>
      expect(calls.filter((c) => c.url.endsWith('/attach')).map((c) => c.body)).toEqual([{ dryRun: true }, { dryRun: false }]),
    )
    expect(await screen.findByText('Opération récurrente créée, 3 transaction(s) rattachée(s)')).toBeInTheDocument()
    expect(refetch).toHaveBeenCalled()
  })

  test('with no past line to attach, the series is created without a preview', async () => {
    renderWith(<MakeRecurringButton record={TRANSACTION} />)
    const form = await open()
    await waitFor(() => expect(within(form).getByRole('combobox', { name: /Compte/ })).toHaveTextContent('Courant'))
    await userEvent.click(within(form).getByRole('button', { name: 'Créer la série' }))

    expect(await screen.findByText('Opération récurrente créée')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(calls.filter((c) => c.url.endsWith('/attach')).map((c) => c.body)).toEqual([{ dryRun: true }])
  })

  test('closing the preview writes nothing', async () => {
    attachReport = { dryRun: true, scanned: 5, attached: 1, proposed: 0, attachments: [line('01A', '2026-01-12')], proposals: [] }
    renderWith(<MakeRecurringButton record={TRANSACTION} />)
    const form = await open()
    await waitFor(() => expect(within(form).getByRole('combobox', { name: /Compte/ })).toHaveTextContent('Courant'))
    await userEvent.click(within(form).getByRole('button', { name: 'Créer la série' }))
    const preview = await screen.findByRole('dialog', { name: 'Rattacher l’historique à cette série' })

    await userEvent.click(within(preview).getByRole('button', { name: 'Annuler' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(calls.filter((c) => c.url.endsWith('/attach')).map((c) => c.body)).toEqual([{ dryRun: true }])
  })
})

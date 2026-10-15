import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { RecurringOperationsPage } from './RecurringOperationsPage'
import type { RecurringOverview, RecurringSeries, RecurringSuggestion } from './recurringOperations'

const mercureListeners: Array<(data?: string) => void> = []
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], cb: (data?: string) => void) => {
    mercureListeners.push(cb)
  },
}))

const series = (overrides: Partial<RecurringSeries>): RecurringSeries => ({
  id: '01FLIXO',
  label: 'Abonnement Flixo',
  categoryId: '01CAT',
  categoryName: 'Abonnements',
  accountId: '01ACC',
  accountName: 'Courant',
  accountBank: 'Crédit Agricole',
  period: 'monthly',
  dayRule: 'fixed_day',
  referenceAmountCents: -1349,
  referenceSource: 'declared',
  currency: 'EUR',
  monthlyCostCents: -1349,
  yearlyCostCents: -16188,
  endsOn: null,
  nextOccurrenceOn: '2026-11-12',
  lastAttachedOn: '2026-10-12',
  attachmentCount: 3,
  missedOccurrence: null,
  priceChange: null,
  ...overrides,
})

const FLIXO = series({
  priceChange: { previousCents: -1349, currentCents: -1799, percent: 33, since: '2026-10-12' },
})
const LOYER = series({
  id: '01LOYER',
  label: 'Loyer',
  categoryName: 'Logement',
  referenceAmountCents: -80000,
  referenceSource: 'measured',
  monthlyCostCents: -80000,
  yearlyCostCents: -960000,
  dayRule: 'last_day_of_month',
  nextOccurrenceOn: '2026-10-31',
  missedOccurrence: { occurrenceOn: '2026-09-30', daysOverdue: 12 },
})
const SALAIRE = series({
  id: '01SAL',
  label: 'Salaire',
  categoryName: 'Revenus',
  referenceAmountCents: 250000,
  monthlyCostCents: 250000,
  yearlyCostCents: 3000000,
})
const CALME = series({ id: '01CALM', label: 'Assurance' })

const PROPOSAL = {
  transactionId: '01TX',
  label: 'PAIEMENT FLIXO 12/11',
  bookedAt: '2026-11-12',
  amountCents: -1799,
  reason: 'amount_up' as const,
  recurringOperationId: '01FLIXO',
  recurringOperationLabel: 'Abonnement Flixo',
  occurrenceOn: '2026-11-12',
  dateGapDays: 0,
  referenceAmountCents: -1349,
  amountChangePercent: 33,
}

const overviewOf = (operations: RecurringSeries[], proposals: RecurringOverview['proposals'] = []): RecurringOverview => ({
  operations,
  proposals,
  totals: { monthlyCostCents: -81349, yearlyCostCents: -976188, monthlyIncomeCents: 250000, activeCount: operations.length },
})

const SUGGESTION: RecurringSuggestion = {
  label: 'Netflix',
  counterpartyName: 'Netflix',
  counterpartyKey: 'netflix',
  labelPattern: null,
  accountId: '01ACC',
  accountName: 'Courant',
  period: 'monthly',
  anchorOn: '2026-01-05',
  dayRule: 'fixed_day',
  referenceAmountCents: -1399,
  amountSpreadPercent: 0,
  occurrences: 11,
  categoryId: '01CAT',
  categoryName: 'Abonnements',
  samples: ['NETFLIX 05/01', 'NETFLIX 05/02', 'NETFLIX 05/03'],
}

const STORED = [
  {
    id: '/api/recurring_operations/01FLIXO',
    counterpartyName: 'Flixo',
    labelPattern: null,
    anchorOn: '2026-10-12T00:00:00+00:00',
    amountTolerancePercent: 20,
    dateToleranceDays: 5,
  },
  {
    id: '/api/recurring_operations/01LOYER',
    counterpartyName: null,
    labelPattern: 'LOYER',
    anchorOn: '2026-09-30T00:00:00+00:00',
    amountTolerancePercent: 20,
    dateToleranceDays: 5,
  },
  {
    id: '/api/recurring_operations/01SAL',
    counterpartyName: 'Employeur',
    labelPattern: null,
    anchorOn: '2026-10-28T00:00:00+00:00',
    amountTolerancePercent: 20,
    dateToleranceDays: 5,
  },
]

interface Calls {
  method: string
  url: string
  body: unknown
}

const calls: Calls[] = []
let overviews: Array<RecurringOverview | number> = []
let suggestions: RecurringSuggestion[] | number = []
let attachReport: unknown = { dryRun: true, scanned: 0, attached: 0, proposed: 0, attachments: [], proposals: [] }

const json = (status: number, body: unknown) =>
  Promise.resolve({ ok: status >= 200 && status < 300, status, json: async () => body } as Response)

const fetchMock = vi.fn((url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  calls.push({ method, url, body: init?.body ? JSON.parse(init.body as string) : undefined })

  if (url.endsWith('/overview')) {
    const next = overviews.length > 1 ? overviews.shift()! : overviews[0]

    return typeof next === 'number' ? json(next, { error: 'boom' }) : json(200, next)
  }
  if (url.endsWith('/suggestions/accept')) return json(200, { success: true, created: 1, labels: ['Netflix'], attached: 11, proposed: 0 })
  if (url.endsWith('/suggestions')) {
    return typeof suggestions === 'number' ? json(suggestions, { error: 'boom' }) : json(200, { suggestions })
  }
  if (url.endsWith('/attach')) {
    const dryRun = (JSON.parse(init!.body as string) as { dryRun: boolean }).dryRun

    return json(200, { ...(attachReport as object), dryRun })
  }
  if (url.startsWith('/api/transactions/')) return json(200, {})

  return json(404, {})
})

const tx = (id: string, overrides: Record<string, unknown> = {}) => ({
  id: `/api/transactions/${id}`,
  label: `PAIEMENT FLIXO ${id}`,
  bookedAt: '2026-10-14T00:00:00+00:00',
  amountCents: -1349,
  currency: 'EUR',
  recurringOperation: '/api/recurring_operations/01FLIXO',
  recurringOccurrenceOn: '2026-10-12T00:00:00+00:00',
  recurringSource: 'auto',
  ...overrides,
})
let seriesTransactions: Array<ReturnType<typeof tx>> = []
let seriesTransactionsFail = false

const defaultGetList = async (resource: string, params?: { pagination?: { page: number } }) => {
  if (resource === 'transactions') {
    if (seriesTransactionsFail) throw new Error('boom')
    const page = params?.pagination?.page ?? 1

    return { data: seriesTransactions.slice((page - 1) * 100, page * 100), total: seriesTransactions.length }
  }
  if (resource === 'recurring_operations') return { data: STORED, total: STORED.length }
  if (resource === 'accounts') return { data: [{ id: '/api/accounts/01ACC', name: 'Courant' }], total: 1 }

  return { data: [{ id: '/api/categories/01CAT', name: 'Abonnements', obligation: 'optional' }], total: 1 }
}

const getList = vi.fn(defaultGetList)

const deleteMock = vi.fn(async () => ({ data: { id: '/api/recurring_operations/01CALM' } }))

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext
        dataProvider={testDataProvider({
          getList: getList as unknown as DataProvider['getList'],
          delete: deleteMock as unknown as DataProvider['delete'],
        })}
      >
        <RecurringOperationsPage />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

const nbsp = (text: string | null) => (text ?? '').replace(/\s/g, ' ')

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  localStorage.clear()
  mercureListeners.length = 0
  overviews = [overviewOf([FLIXO, LOYER, SALAIRE, CALME])]
  suggestions = []
  seriesTransactions = []
  seriesTransactionsFail = false
  attachReport = { dryRun: true, scanned: 0, attached: 0, proposed: 0, attachments: [], proposals: [] }
})

afterEach(() => {
  vi.unstubAllGlobals()
  fetchMock.mockClear()
  getList.mockClear()
  deleteMock.mockClear()
  calls.length = 0
})

const rowOf = (label: string) => screen.getByText(label).closest('tr') as HTMLElement

describe('RecurringOperationsPage — the list', () => {
  test('reads each series: counterparty, category, account, schedule, reference with its source, cost and next due date', async () => {
    renderPage()

    const flixo = await waitFor(() => rowOf('Abonnement Flixo'))
    await waitFor(() => expect(within(flixo).getByText('Flixo')).toBeInTheDocument())
    expect(within(flixo).getByText('Abonnements')).toBeInTheDocument()
    expect(within(flixo).getByText('Courant')).toBeInTheDocument()
    expect(within(flixo).getByText('Crédit Agricole')).toBeInTheDocument()
    expect(within(flixo).getByText('Le 12 de chaque mois')).toBeInTheDocument()
    expect(within(flixo).getByText('déclaré')).toBeInTheDocument()
    expect(nbsp(within(flixo).getByText(/ \/ mois$/).textContent)).toBe('-13,49 € / mois')
    expect(nbsp(within(flixo).getByText(/ \/ an$/).textContent)).toBe('-161,88 € / an')
    expect(within(flixo).getByText('12/11/2026')).toBeInTheDocument()

    const loyer = rowOf('Loyer')
    expect(within(loyer).getByText('Le dernier jour du mois')).toBeInTheDocument()
    expect(within(loyer).getByText('mesuré')).toBeInTheDocument()
  })

  test('shows the bank under the account, and nothing when the account has none', async () => {
    overviews = [overviewOf([FLIXO, series({ id: '01SAL', label: 'Salaire', accountBank: null })])]
    renderPage()

    const flixo = await waitFor(() => rowOf('Abonnement Flixo'))
    expect(within(flixo).getByText('Crédit Agricole')).toBeInTheDocument()
    expect(within(rowOf('Salaire')).queryByText('Crédit Agricole')).not.toBeInTheDocument()
  })

  test('filters the list by account', async () => {
    const user = userEvent.setup()
    overviews = [
      overviewOf([
        FLIXO,
        series({ id: '01SAL', label: 'Salaire', accountId: '01REV', accountName: 'Revolut', accountBank: 'Revolut' }),
      ]),
    ]
    renderPage()
    await waitFor(() => rowOf('Abonnement Flixo'))
    expect(rowOf('Salaire')).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Compte' }))
    await user.click(await screen.findByRole('option', { name: 'Revolut' }))

    expect(screen.queryByText('Abonnement Flixo')).not.toBeInTheDocument()
    expect(rowOf('Salaire')).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Compte' }))
    await user.click(await screen.findByRole('option', { name: 'Tous les comptes' }))
    expect(rowOf('Abonnement Flixo')).toBeInTheDocument()
  })

  describe('when the search index trails the overview', () => {
    const storedReads = () => getList.mock.calls.filter(([resource]) => resource === 'recurring_operations').length
    const withoutFlixo = STORED.filter((record) => !String(record.id).endsWith('01FLIXO'))
    const staleFor = (reads: number) => {
      let served = 0
      getList.mockImplementation(async (resource: string) => {
        if (resource === 'recurring_operations') {
          served += 1

          return served <= reads
            ? { data: withoutFlixo, total: withoutFlixo.length }
            : { data: STORED, total: STORED.length }
        }
        if (resource === 'accounts') return { data: [{ id: '/api/accounts/01ACC', name: 'Courant' }], total: 1 }

        return { data: [{ id: '/api/categories/01CAT', name: 'Abonnements', obligation: 'optional' }], total: 1 }
      })
    }

    beforeEach(() => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
    })

    afterEach(() => {
      vi.useRealTimers()
      getList.mockImplementation(defaultGetList)
    })

    test('re-reads the stored list until the series is there, however many reads that takes', async () => {
      staleFor(3)
      renderPage()

      const flixo = await waitFor(() => rowOf('Abonnement Flixo'))
      expect(within(flixo).getByText('Chaque mois')).toBeInTheDocument()

      await act(() => vi.advanceTimersByTimeAsync(10000))

      await waitFor(() => expect(within(rowOf('Abonnement Flixo')).getByText('Le 12 de chaque mois')).toBeInTheDocument())
      expect(storedReads()).toBe(4)
    })

    test('gives up after a few reads when the series never shows up in the stored list', async () => {
      staleFor(Number.POSITIVE_INFINITY)
      renderPage()
      await waitFor(() => rowOf('Abonnement Flixo'))

      await act(() => vi.advanceTimersByTimeAsync(60000))

      expect(storedReads()).toBe(6)
    })
  })

  test('puts expenses and incomes apart in the totals header', async () => {
    renderPage()

    const costs = (await screen.findByText('Dépenses par mois')).parentElement!
    expect(nbsp(costs.textContent)).toContain('-813,49 €')
    const incomes = screen.getByText('Recettes par mois').parentElement!
    expect(nbsp(incomes.textContent)).toContain('2 500,00 €')
    expect(screen.getByText('Séries actives').parentElement).toHaveTextContent('4')
  })

  test('shows the missed-due-date and price-change badges only where the API renders them', async () => {
    renderPage()

    await screen.findByText('Abonnement Flixo')
    expect(nbsp(within(rowOf('Abonnement Flixo')).getByText(/Hausse de prix/).textContent)).toBe(
      'Hausse de prix · 13,49 € → 17,99 €, +33 %',
    )
    expect(within(rowOf('Loyer')).getByText('Échéance manquée')).toBeInTheDocument()
    expect(within(rowOf('Loyer')).queryByText(/Hausse de prix/)).not.toBeInTheDocument()
    expect(within(rowOf('Assurance')).queryByText(/Échéance manquée|Hausse de prix/)).not.toBeInTheDocument()
    expect(screen.getAllByText('Échéance manquée')).toHaveLength(1)
  })

  test('with no series it says so and offers to create one', async () => {
    overviews = [overviewOf([])]
    renderPage()

    expect(await screen.findByText('Aucune opération récurrente pour l’instant')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /opération récurrente/ }).length).toBeGreaterThan(0)
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  test('an API error is shown with a way to retry, never a blank page', async () => {
    overviews = [500, overviewOf([FLIXO])]
    renderPage()

    expect(await screen.findByText('Impossible de lire les opérations récurrentes')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))
    expect(await screen.findByText('Abonnement Flixo')).toBeInTheDocument()
    expect(screen.queryByText('Impossible de lire les opérations récurrentes')).not.toBeInTheDocument()
  })

  test('a Mercure message reads the overview again', async () => {
    renderPage()
    await screen.findByText('Abonnement Flixo')
    const before = calls.filter((c) => c.url.endsWith('/overview')).length

    await act(async () => mercureListeners.forEach((listener) => listener('{}')))

    await waitFor(() => expect(calls.filter((c) => c.url.endsWith('/overview')).length).toBeGreaterThan(before))
  })

  test('editing a series recognised by its label pattern opens with the pattern, never wipes it', async () => {
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Modifier Loyer' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText(/Motif du libellé/)).toHaveValue('LOYER')
    expect(within(dialog).getByLabelText('Contrepartie attendue')).toHaveValue('')
  })

  test('deleting a series asks first, then removes it', async () => {
    renderPage()
    await screen.findByText('Assurance')

    await userEvent.click(screen.getByRole('button', { name: 'Supprimer Assurance' }))
    const dialog = await screen.findByRole('dialog')
    expect(deleteMock).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(deleteMock).toHaveBeenCalledTimes(1))
    expect(deleteMock.mock.calls[0]).toEqual(['recurring_operations', expect.objectContaining({ id: '/api/recurring_operations/01CALM' })])
  })
})

describe('RecurringOperationsPage — unrolling a series', () => {
  const unroll = async (label = 'Abonnement Flixo') => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: `Dérouler les transactions de ${label}` }))
  }

  test('reads nothing until a row is unrolled, then lists its transactions newest first with their due date and origin', async () => {
    seriesTransactions = [
      tx('3', { bookedAt: '2026-10-14T00:00:00+00:00', recurringOccurrenceOn: '2026-10-12T00:00:00+00:00' }),
      tx('2', { bookedAt: '2026-09-13T00:00:00+00:00', recurringOccurrenceOn: '2026-09-12T00:00:00+00:00', recurringSource: 'manual' }),
      tx('1', { bookedAt: '2026-08-12T00:00:00+00:00', recurringOccurrenceOn: '2026-08-12T00:00:00+00:00' }),
    ]
    renderPage()
    await screen.findByText('Abonnement Flixo')
    expect(getList.mock.calls.some(([resource]) => resource === 'transactions')).toBe(false)

    await userEvent.click(screen.getByRole('button', { name: 'Dérouler les transactions de Abonnement Flixo' }))

    const table = await screen.findByRole('table', { name: 'Transactions de la série' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('PAIEMENT FLIXO 3')).toBeInTheDocument()
    expect(within(rows[0]).getByText('14/10/2026')).toBeInTheDocument()
    expect(within(rows[0]).getByText('12/10/2026')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Automatique')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Manuel')).toBeInTheDocument()
    expect(within(rows[2]).getByText('PAIEMENT FLIXO 1')).toBeInTheDocument()

    const read = getList.mock.calls.find(([resource]) => resource === 'transactions') as unknown as [string, Record<string, unknown>]
    expect(read[1]).toMatchObject({
      filter: { recurringOperation: '/api/recurring_operations/01FLIXO' },
      sort: { field: 'bookedAt', order: 'DESC' },
    })
  })

  test('reads every page of a long series', async () => {
    seriesTransactions = Array.from({ length: 130 }, (_, i) => tx(String(i)))
    await unroll()

    const table = await screen.findByRole('table', { name: 'Transactions de la série' })
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(131))
  })

  test('a series with no transaction says so', async () => {
    await unroll()

    expect(await screen.findByText('Aucune transaction reconnue pour l’instant')).toBeInTheDocument()
  })

  test('detaching a line takes it off the series without a reload', async () => {
    seriesTransactions = [tx('2'), tx('1')]
    await unroll()
    await screen.findByText('PAIEMENT FLIXO 2')

    await userEvent.click(screen.getByRole('button', { name: 'Détacher PAIEMENT FLIXO 2 de la série' }))

    await waitFor(() => expect(screen.queryByText('PAIEMENT FLIXO 2')).not.toBeInTheDocument())
    expect(screen.getByText('PAIEMENT FLIXO 1')).toBeInTheDocument()
    const patch = calls.find((c) => c.method === 'PATCH')
    expect(patch?.url).toBe('/api/transactions/2')
    expect(patch?.body).toEqual({ recurringOperation: null, recurringOccurrenceOn: null })
  })

  test('a refused detach says so and keeps the line', async () => {
    seriesTransactions = [tx('1')]
    await unroll()
    await screen.findByText('PAIEMENT FLIXO 1')
    fetchMock.mockImplementationOnce(() => json(500, {}))

    await userEvent.click(screen.getByRole('button', { name: 'Détacher PAIEMENT FLIXO 1 de la série' }))

    expect(await screen.findByText('Impossible de détacher cette transaction')).toBeInTheDocument()
    expect(screen.getByText('PAIEMENT FLIXO 1')).toBeInTheDocument()
  })

  test('a line attached elsewhere appears when Mercure announces it', async () => {
    seriesTransactions = [tx('1')]
    await unroll()
    await screen.findByText('PAIEMENT FLIXO 1')

    seriesTransactions = [tx('2'), tx('1')]
    await act(async () => mercureListeners.forEach((listener) => listener('{}')))

    expect(await screen.findByText('PAIEMENT FLIXO 2')).toBeInTheDocument()
  })

  test('a failed read shows an error with a way to retry', async () => {
    seriesTransactionsFail = true
    await unroll()
    expect(await screen.findByText('Impossible de lire les transactions de cette série')).toBeInTheDocument()

    seriesTransactionsFail = false
    seriesTransactions = [tx('1')]
    await userEvent.click(screen.getAllByRole('button', { name: 'Réessayer' }).at(-1)!)

    expect(await screen.findByText('PAIEMENT FLIXO 1')).toBeInTheDocument()
  })

  test('folding the row hides the transactions', async () => {
    seriesTransactions = [tx('1')]
    await unroll()
    await screen.findByText('PAIEMENT FLIXO 1')

    await userEvent.click(screen.getByRole('button', { name: 'Replier les transactions de Abonnement Flixo' }))

    await waitFor(() => expect(screen.queryByText('PAIEMENT FLIXO 1')).not.toBeInTheDocument())
  })
})

describe('RecurringOperationsPage — proposals', () => {
  beforeEach(() => {
    overviews = [overviewOf([FLIXO], [PROPOSAL]), overviewOf([FLIXO])]
  })

  test('shows the line, its series and why it was only proposed', async () => {
    renderPage()

    const panel = (await screen.findByRole('region', { name: 'Rattachements proposés' }))
    expect(within(panel).getByText(/PAIEMENT FLIXO 12\/11/)).toBeInTheDocument()
    expect(nbsp(panel.textContent)).toContain('hausse de prix 13,49 € → 17,99 €, +33 %')
    expect(within(panel).getByRole('button', { name: 'Rattacher PAIEMENT FLIXO 12/11' })).toBeInTheDocument()
  })

  test('accepting attaches the line to its occurrence and the panel empties', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher PAIEMENT FLIXO 12/11' }))

    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'PATCH',
        url: '/api/transactions/01TX',
        body: { recurringOperation: '/api/recurring_operations/01FLIXO', recurringOccurrenceOn: '2026-11-12' },
      }),
    )
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Rattachements proposés' })).not.toBeInTheDocument())
  })

  test('ignoring removes the line from the panel without writing anything, and keeps it away', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Ignorer PAIEMENT FLIXO 12/11' }))

    expect(screen.queryByRole('region', { name: 'Rattachements proposés' })).not.toBeInTheDocument()
    expect(calls.filter((c) => c.method === 'PATCH')).toEqual([])
    expect(JSON.parse(localStorage.getItem('maggie.finance.recurring.ignoredProposals') ?? '[]')).toEqual(['01TX'])
  })

  test('an ambiguous line offers one button per candidate series', async () => {
    overviews = [
      overviewOf([FLIXO], [
        {
          ...PROPOSAL,
          reason: 'ambiguous',
          recurringOperationId: null,
          candidates: [
            { recurringOperationId: '01FLIXO', recurringOperationLabel: 'Abonnement Flixo', occurrenceOn: '2026-11-12' },
            { recurringOperationId: '01OTHER', recurringOperationLabel: 'Flixo Plus', occurrenceOn: '2026-11-10' },
          ],
        },
      ]),
    ]
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher PAIEMENT FLIXO 12/11 à Flixo Plus' }))

    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'PATCH',
        url: '/api/transactions/01TX',
        body: { recurringOperation: '/api/recurring_operations/01OTHER', recurringOccurrenceOn: '2026-11-10' },
      }),
    )
  })

  test('a refused attachment says so and keeps the proposal', async () => {
    renderPage()
    await screen.findByRole('button', { name: 'Rattacher PAIEMENT FLIXO 12/11' })
    fetchMock.mockImplementationOnce(() => json(422, { detail: 'no' }))

    await userEvent.click(screen.getByRole('button', { name: 'Rattacher PAIEMENT FLIXO 12/11' }))

    expect(await screen.findByText('Impossible de rattacher cette transaction')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Rattachements proposés' })).toBeInTheDocument()
  })
})

describe('RecurringOperationsPage — suggestions', () => {
  beforeEach(() => {
    suggestions = [SUGGESTION]
  })

  test('shows the reason, the schedule and three examples', async () => {
    renderPage()

    const panel = await screen.findByRole('region', { name: 'Suggestions' })
    expect(within(panel).getByText(/11 transactions le 5 de chaque mois, montant stable à 0 % près/)).toBeInTheDocument()
    expect(within(panel).getByText(/NETFLIX 05\/01 · NETFLIX 05\/02 · NETFLIX 05\/03/)).toBeInTheDocument()
  })

  test('« Créer la série » accepts the suggestion and reads the list again', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Créer la série Netflix' }))

    await waitFor(() =>
      expect(calls).toContainEqual({
        method: 'POST',
        url: '/api/finance/recurring-operations/suggestions/accept',
        body: { suggestions: [SUGGESTION] },
      }),
    )
    expect(await screen.findByText('Série « Netflix » créée, 11 transaction(s) rattachée(s)')).toBeInTheDocument()
  })

  test('a suggestion with no category opens the form to choose one instead of guessing', async () => {
    suggestions = [{ ...SUGGESTION, categoryId: null, categoryName: null }]
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Créer la série Netflix' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText(/Libellé/)).toHaveValue('Netflix')
    expect(calls.filter((c) => c.url.endsWith('/accept'))).toEqual([])
  })

  test('without suggestions the panel is not there', async () => {
    suggestions = []
    renderPage()

    await screen.findByText('Abonnement Flixo')
    expect(screen.queryByRole('region', { name: 'Suggestions' })).not.toBeInTheDocument()
  })
})

describe('RecurringOperationsPage — catching up the history', () => {
  const lines = [
    { transactionId: '01A', label: 'FLIXO 12/08', bookedAt: '2026-08-12', amountCents: -1349, reason: null, recurringOperationId: '01FLIXO', recurringOperationLabel: 'Abonnement Flixo', occurrenceOn: '2026-08-12' },
    { transactionId: '01B', label: 'FLIXO 12/09', bookedAt: '2026-09-12', amountCents: -1349, reason: null, recurringOperationId: '01FLIXO', recurringOperationLabel: 'Abonnement Flixo', occurrenceOn: '2026-09-12' },
  ]

  beforeEach(() => {
    attachReport = { dryRun: true, scanned: 40, attached: 2, proposed: 0, attachments: lines, proposals: [] }
  })

  test('shows what it would attach before writing anything', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher l’historique' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/FLIXO 12\/08/)).toBeInTheDocument()
    expect(within(dialog).getByText(/FLIXO 12\/09/)).toBeInTheDocument()
    expect(calls.filter((c) => c.url.endsWith('/attach')).map((c) => c.body)).toEqual([{ dryRun: true }])
  })

  test('writes only once confirmed', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher l’historique' }))
    await userEvent.click(await screen.findByRole('button', { name: /Rattacher ces 2 transaction/ }))

    await waitFor(() =>
      expect(calls.filter((c) => c.url.endsWith('/attach')).map((c) => c.body)).toEqual([{ dryRun: true }, { dryRun: false }]),
    )
    expect(await screen.findByText('2 transaction(s) rattachée(s) sur 40 analysée(s)')).toBeInTheDocument()
  })

  test('cancelling never reaches the write', async () => {
    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher l’historique' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Annuler' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(calls.filter((c) => c.url.endsWith('/attach'))).toHaveLength(1)
  })
})

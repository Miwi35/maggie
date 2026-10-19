import { describe, test, expect, vi, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { AccountTransactionsEmpty, AccountTransactionsView } from './AccountTransactionsView'
import { dayTitle, dueDay, monthTitle } from './timelineFormat'
import type { AccountTimelinePage, TimelineDay, TimelineTransaction } from './useAccountTimeline'

const mercureListeners: Array<(data?: string) => void> = []
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], cb: (data?: string) => void) => {
    mercureListeners.length = 0
    mercureListeners.push(cb)
  },
}))
const emitMercure = () => act(() => mercureListeners.forEach((listener) => listener('{}')))

const ACCOUNT_IRI = '/api/accounts/01ABC'

const accounts = [
  { id: ACCOUNT_IRI, name: 'Compte joint' },
  { id: '/api/accounts/02XYZ', name: 'Livret' },
]

const providerOf = (account: Record<string, unknown> = { id: ACCOUNT_IRI, name: 'Compte joint' }) =>
  testDataProvider({
    getList: ((resource: string) =>
      Promise.resolve(resource === 'accounts' ? { data: accounts, total: accounts.length } : { data: [], total: 0 })) as unknown as DataProvider['getList'],
    getOne: ((resource: string, params: { id: string }) =>
      Promise.resolve({
        data:
          resource === 'recurring_operations'
            ? { id: params.id, label: 'Loyer mensuel' }
            : account,
      })) as unknown as DataProvider['getOne'],
    getMany: ((resource: string, params: { ids: string[] }) =>
      Promise.resolve({
        data: resource === 'categories' ? params.ids.map((id) => ({ id, name: 'Alimentation' })) : [],
      })) as unknown as DataProvider['getMany'],
  })

const emptyProvider = providerOf()

const LocationState = () => {
  const { pathname, state } = useLocation()
  return <pre data-testid="location">{JSON.stringify({ pathname, state })}</pre>
}

const timelinePage = (over: Partial<AccountTimelinePage> = {}): AccountTimelinePage => ({
  month: '2026-03',
  previousMonth: null,
  account: {
    id: '01ABC',
    name: 'Compte joint',
    bank: 'Boursorama',
    type: 'checking',
    currency: 'EUR',
    balanceCents: 123456,
    isCushion: false,
    isManual: true,
  },
  days: [],
  ...over,
})

const line = (over: Partial<TimelineTransaction> = {}): TimelineTransaction => ({
  id: '01LINE',
  label: 'Courses',
  counterpartyName: null,
  amountCents: -2000,
  currency: 'EUR',
  status: 'spent',
  categoryId: '01CAT',
  transferKind: 'none',
  counterpartAccount: null,
  recurringOperationId: null,
  recurringIncrease: null,
  ...over,
})

const day = (date: string, transactions: TimelineTransaction[] = [], missedOccurrences: TimelineDay['missedOccurrences'] = []): TimelineDay => ({
  date,
  transactions,
  missedOccurrences,
})

/**
 * Answers the incidents route with `incidents`, the timeline route with
 * `timeline` (a function of the URL when the month matters).
 */
const stubFetch = (
  incidents: unknown[] = [],
  timeline: AccountTimelinePage | ((url: string) => AccountTimelinePage) = timelinePage(),
) => {
  const fetchMock = vi.fn(async (url: string) => {
    const path = String(url)
    if (path.endsWith('/incidents')) return { ok: true, json: async () => ({ incidents }) }
    if (path.endsWith('/incidents?settled=true')) return { ok: true, json: async () => ({ incidents: [] }) }
    if (path.includes('/timeline')) {
      return { ok: true, json: async () => (typeof timeline === 'function' ? timeline(path) : timeline) }
    }

    return { ok: true, json: async () => ({}) }
  })
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const timelineCalls = (fetchMock: ReturnType<typeof stubFetch>) =>
  fetchMock.mock.calls.map(([url]) => String(url)).filter((url) => url.includes('/timeline'))

const renderView = (provider: DataProvider = emptyProvider, entry = '/accounts/01ABC/transactions') =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <AdminContext dataProvider={provider}>
        <h6 id="react-admin-title" data-testid="app-bar-title" />
        <Routes>
          <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
          <Route path="*" element={<LocationState />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

const rowOf = async (text: string | RegExp) => (await screen.findByText(text)).closest('tr')!

afterEach(() => vi.unstubAllGlobals())

describe('AccountTransactionsEmpty', () => {
  test('says the account is empty and offers the button that adds a transaction', () => {
    render(
      <AdminContext dataProvider={emptyProvider}>
        <ResourceContextProvider value="transactions">
          <AccountTransactionsEmpty accountIri={ACCOUNT_IRI} />
        </ResourceContextProvider>
      </AdminContext>,
    )

    expect(screen.getByText('Aucune transaction sur ce compte')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ajouter une transaction' })).toBeInTheDocument()
  })
})

describe('formats', () => {
  test('a day reads as a French calendar day, a month with its year, a due date as « le 5 »', () => {
    expect(dayTitle('2026-03-12')).toBe('Jeudi 12 mars')
    expect(monthTitle('2026-03')).toBe('Mars 2026')
    expect(dueDay('2026-03-05')).toBe('le 5')
    expect(dueDay('2026-03-01')).toBe('le 1er')
  })
})

describe('AccountTransactionsView', () => {
  test('an account with no operation still shows the button, and it preselects the account', async () => {
    stubFetch()
    const user = userEvent.setup()

    renderView()

    // The empty state replaces the toolbar: the one button left is its own.
    const placeholder = (await screen.findByText('Aucune transaction sur ce compte')).parentElement!
    const button = within(placeholder).getByRole('link', { name: 'Ajouter une transaction' })
    expect(screen.getAllByRole('link', { name: 'Ajouter une transaction' })).toHaveLength(1)

    await user.click(button)

    const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
    expect(location.pathname).toBe('/transactions/create')
    expect(location.state.record).toEqual({ account: ACCOUNT_IRI })
  })

  test('with lines, « Ajouter une transaction » opens the form with the account already chosen', async () => {
    stubFetch([], timelinePage({ days: [day('2026-03-12', [line()])] }))
    const user = userEvent.setup()

    renderView()

    await screen.findByText('Courses')
    await user.click(screen.getByRole('link', { name: 'Ajouter une transaction' }))

    const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
    expect(location.pathname).toBe('/transactions/create')
    expect(location.state.record).toEqual({ account: ACCOUNT_IRI })
  })

  test('the app bar carries one title, the account\'s, so a long one can end in an ellipsis (MAG-387)', async () => {
    stubFetch()
    renderView()

    await screen.findByText('Aucune transaction sur ce compte')
    await waitFor(() => expect(screen.getByTestId('app-bar-title')).toHaveTextContent('Transactions — Compte joint'))
    expect(screen.getByTestId('app-bar-title').textContent).toBe('Transactions — Compte joint')
  })

  test('the title tells the bank, the currency and the name of the account (MAG-501)', async () => {
    stubFetch()
    renderView(providerOf({ id: ACCOUNT_IRI, name: 'Compte joint', bank: 'Boursorama', currency: 'EUR' }))

    await screen.findByText('Aucune transaction sur ce compte')
    await waitFor(() =>
      expect(screen.getByTestId('app-bar-title')).toHaveTextContent('Transactions — Boursorama · EUR · Compte joint'),
    )
  })

  test('the account and its balance head the screen, and the selector offers the owner\'s accounts', async () => {
    stubFetch([], timelinePage({ days: [day('2026-03-12', [line()])] }))
    const user = userEvent.setup()

    renderView()

    expect(await screen.findByLabelText('Solde du compte')).toHaveTextContent(/1\s?234,56/)
    await user.click(screen.getByRole('combobox', { name: 'Compte' }))
    expect(await screen.findByRole('option', { name: 'Livret' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Compte joint' })).toBeInTheDocument()
  })

  test('changing the account reads the timeline of the new one', async () => {
    const fetchMock = stubFetch([], timelinePage({ days: [day('2026-03-12', [line()])] }))
    const user = userEvent.setup()

    renderView()

    await screen.findByText('Courses')
    expect(timelineCalls(fetchMock)).toEqual(['/api/finance/accounts/01ABC/timeline'])

    await user.click(screen.getByRole('combobox', { name: 'Compte' }))
    await user.click(await screen.findByRole('option', { name: 'Livret' }))

    await waitFor(() => expect(timelineCalls(fetchMock)).toContain('/api/finance/accounts/02XYZ/timeline'))
  })

  describe('the timeline', () => {
    const march = timelinePage({
      days: [
        day('2026-03-12', [
          line({ id: '01PLAN', label: 'Assurance habitation', amountCents: -4500, status: 'planned' }),
          line({ id: '01SALARY', label: 'Salaire', amountCents: 235000, status: 'spent', categoryId: null }),
        ]),
        day('2026-03-05', [
          line({
            id: '01RENT',
            label: 'Loyer',
            amountCents: -120000,
            recurringOperationId: '01S',
            recurringIncrease: { percent: 33, previousCents: -90000 },
          }),
        ], [{ recurringOperationId: '01GYM', label: 'Salle de sport', expectedOn: '2026-03-05', amountCents: -3000 }]),
      ],
    })

    test('lines are grouped by day, newest first, under the month', async () => {
      stubFetch([], march)
      renderView()

      expect(await screen.findByText('Mars 2026')).toBeInTheDocument()
      const days = screen.getAllByText(/^(Jeudi) \d+ mars$/).map((node) => node.textContent)
      expect(days).toEqual(['Jeudi 12 mars', 'Jeudi 5 mars'])
      // Several lines of one day sit between its title and the next.
      const rows = screen.getAllByRole('row').map((row) => row.textContent ?? '')
      const titleAt = rows.findIndex((text) => text === 'Jeudi 12 mars')
      expect(rows[titleAt + 1]).toContain('Assurance habitation')
      expect(rows[titleAt + 2]).toContain('Salaire')
    })

    test('each line says its real status in words, by the sign of the amount', async () => {
      stubFetch([], march)
      renderView()

      expect(within(await rowOf('Assurance habitation')).getByText('Planifiée')).toBeInTheDocument()
      expect(within(await rowOf('Salaire')).getByText('Reçue')).toBeInTheDocument()
      expect(within(await rowOf('Loyer')).getByText('Dépensée')).toBeInTheDocument()
    })

    test('a rent that rose carries « +33 % », and an ordinary line does not', async () => {
      stubFetch([], march)
      renderView()

      expect(within(await rowOf('Loyer')).getByText('+33 %')).toBeInTheDocument()
      expect(within(await rowOf('Salaire')).queryByText(/%/)).not.toBeInTheDocument()
    })

    test('an operation that never came reads « attendu le 5 — en retard »', async () => {
      stubFetch([], march)
      renderView()

      const missed = await rowOf('Salle de sport attendu le 5 — en retard')
      expect(within(missed).getByText(/30,00/)).toBeInTheDocument()
    })

    test('a line shows its category, or « À trier » when it has none, and a series names itself', async () => {
      stubFetch([], march)
      renderView()

      expect(await within(await rowOf('Loyer')).findByText('Alimentation')).toBeInTheDocument()
      expect(await within(await rowOf('Loyer')).findByText('Série · Loyer mensuel')).toBeInTheDocument()
      expect(within(await rowOf('Salaire')).getByText('À trier')).toBeInTheDocument()
    })

    test('a click on a line opens its sheet', async () => {
      stubFetch([], march)
      const user = userEvent.setup()
      renderView()

      await user.click(await screen.findByText('Assurance habitation'))

      const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
      expect(location.pathname).toBe('/transactions/%2Fapi%2Ftransactions%2F01PLAN')
    })
  })

  describe('internal transfers', () => {
    const transfers = timelinePage({
      days: [
        day('2026-03-12', [
          line({
            id: '01IN',
            label: 'Virement du Courant',
            amountCents: 300000,
            categoryId: null,
            transferKind: 'internal',
            counterpartAccount: { id: '01CHK', name: 'Compte courant' },
          }),
          line({
            id: '01OUT',
            label: 'Virement vers Livret',
            amountCents: -50000,
            categoryId: null,
            transferKind: 'internal',
            counterpartAccount: { id: '01SAV', name: 'Livret' },
          }),
          line({ id: '01ALONE', label: 'Virement orphelin', categoryId: null, transferKind: 'internal' }),
          line({ id: '01SHOP', label: 'Supermarché' }),
        ]),
      ],
    })

    test('a transfer carries the badge and names the account on the other side', async () => {
      stubFetch([], transfers)
      renderView()

      const incoming = await rowOf('Virement du Courant')
      expect(within(incoming).getByText('Virement interne')).toBeInTheDocument()
      expect(within(incoming).getByText('depuis Compte courant')).toBeInTheDocument()
      const outgoing = await rowOf('Virement vers Livret')
      expect(within(outgoing).getByText('vers Livret')).toBeInTheDocument()
      const ordinary = await rowOf('Supermarché')
      expect(within(ordinary).queryByText('Virement interne')).not.toBeInTheDocument()
    })

    test('a transfer paired with nothing says the other account is not followed', async () => {
      stubFetch([], transfers)
      renderView()

      expect(await within(await rowOf('Virement orphelin')).findByText(/Sans contrepartie/)).toBeInTheDocument()
    })
  })

  describe('months', () => {
    const timeline = (url: string) =>
      url.includes('month=2026-02')
        ? timelinePage({ month: '2026-02', previousMonth: null, days: [day('2026-02-27', [line({ id: '01OLD', label: 'Cadeau de février' })])] })
        : timelinePage({ previousMonth: '2026-02', days: [day('2026-03-12', [line({ id: '01NEW', label: 'Courses de mars' })])] })

    test('the current month comes first, « Mois précédent » reads the month before and adds it below', async () => {
      const fetchMock = stubFetch([], timeline)
      const user = userEvent.setup()
      renderView()

      await screen.findByText('Courses de mars')
      expect(screen.queryByText('Cadeau de février')).not.toBeInTheDocument()
      expect(timelineCalls(fetchMock)).toEqual(['/api/finance/accounts/01ABC/timeline'])

      await user.click(screen.getByRole('button', { name: 'Mois précédent' }))

      expect(await screen.findByText('Cadeau de février')).toBeInTheDocument()
      expect(screen.getByText('Février 2026')).toBeInTheDocument()
      expect(screen.getByText('Courses de mars')).toBeInTheDocument()
      expect(timelineCalls(fetchMock)).toContain('/api/finance/accounts/01ABC/timeline?month=2026-02')
      // Nothing earlier: the button goes.
      expect(screen.queryByRole('button', { name: 'Mois précédent' })).not.toBeInTheDocument()
    })

    test('a month with no line but an earlier one invites to go back', async () => {
      stubFetch([], timelinePage({ previousMonth: '2026-02' }))
      renderView()

      expect(await screen.findByText('Aucune transaction ce mois-ci')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Mois précédent' })).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Ajouter une transaction' })).toBeInTheDocument()
    })
  })

  describe('when the API fails', () => {
    test('says so with a retry button that reads the timeline again', async () => {
      let healthy = false
      const fetchMock = vi.fn(async (url: string) => {
        if (String(url).endsWith('/incidents')) return { ok: true, json: async () => ({ incidents: [] }) }

        return healthy
          ? { ok: true, json: async () => timelinePage({ days: [day('2026-03-12', [line({ label: 'Retrouvée' })])] }) }
          : { ok: false, json: async () => ({}) }
      })
      vi.stubGlobal('fetch', fetchMock)
      const user = userEvent.setup()

      renderView()

      expect(await screen.findByText('Impossible de lire les transactions du compte')).toBeInTheDocument()
      expect(screen.queryByText('Aucune transaction sur ce compte')).not.toBeInTheDocument()

      healthy = true
      await user.click(screen.getByRole('button', { name: 'Réessayer' }))

      expect(await screen.findByText('Retrouvée')).toBeInTheDocument()
      expect(screen.queryByText('Impossible de lire les transactions du compte')).not.toBeInTheDocument()
    })

    test('a network failure reads the same', async () => {
      vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline') }))

      renderView()

      expect(await screen.findByText('Impossible de lire les transactions du compte')).toBeInTheDocument()
    })
  })

  test('a transaction written while the screen is open joins the timeline without a reload', async () => {
    let current = timelinePage({ days: [day('2026-03-12', [line({ label: 'Courses' })])] })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => ({
        ok: true,
        json: async () => (String(url).endsWith('/incidents') ? { incidents: [] } : current),
      })),
    )

    renderView()
    await screen.findByText('Courses')

    current = timelinePage({
      days: [day('2026-03-13', [line({ id: '01BAKERY', label: 'Boulangerie' })]), day('2026-03-12', [line({ label: 'Courses' })])],
    })
    await emitMercure()

    expect(await screen.findByText('Boulangerie')).toBeInTheDocument()
    expect(screen.getByText('Courses')).toBeInTheDocument()
  })
})

describe('Incidents tab', () => {
  const incident = {
    debitId: '01DEBIT',
    creditId: '01CREDIT',
    bookedAt: '2026-09-05',
    rejectedAt: '2026-09-06',
    counterpartyName: 'EDF',
    amountCents: 20600,
    kind: 'direct_debit',
  }
  const legs: Record<string, Record<string, unknown>> = {
    '/api/transactions/01DEBIT': {
      id: '/api/transactions/01DEBIT',
      label: 'PRELEVEMENT EDF',
      amountCents: -20600,
      currency: 'EUR',
      bookedAt: '2026-09-05',
    },
    '/api/transactions/01CREDIT': {
      id: '/api/transactions/01CREDIT',
      label: 'REJET PRLV ELECTRICITE DE FRANCE',
      amountCents: 20600,
      currency: 'EUR',
      bookedAt: '2026-09-06',
    },
  }
  const provider = testDataProvider({
    getList: ((resource: string) =>
      Promise.resolve(resource === 'accounts' ? { data: accounts, total: accounts.length } : { data: [], total: 0 })) as unknown as DataProvider['getList'],
    getOne: ((resource: string, params: { id: string }) =>
      Promise.resolve({
        data: resource === 'transactions' ? legs[params.id] : { id: ACCOUNT_IRI, name: 'Compte joint' },
      })) as unknown as DataProvider['getOne'],
  })

  test('the home pill lands on the incidents tab with `?tab=incidents`', async () => {
    stubFetch([incident])

    renderView(provider, '/accounts/01ABC/transactions?tab=incidents')

    expect(await screen.findByRole('tab', { name: 'Incidents (1)', selected: true })).toBeInTheDocument()
    expect(await screen.findByText('EDF')).toBeVisible()
  })

  test('the tab carries the number of rejections, and each one reads as a single line', async () => {
    stubFetch([incident, { ...incident, debitId: '01D2', creditId: '01C2', counterpartyName: 'Mme Martin', kind: 'transfer', amountCents: 5000 }])
    const user = userEvent.setup()

    renderView(provider)

    const tab = await screen.findByRole('tab', { name: 'Incidents (2)' })
    await user.click(tab)

    const edf = (await screen.findByText('EDF')).closest('tr')!
    expect(within(edf).getByText('05/09/2026')).toBeInTheDocument()
    expect(within(edf).getByText('Prélèvement rejeté')).toBeInTheDocument()
    expect(within(edf).getByText(/206,00/)).toBeInTheDocument()
    const martin = screen.getByText('Mme Martin').closest('tr')!
    expect(within(martin).getByText('Virement rejeté')).toBeInTheDocument()
  })

  test('a click on a rejection opens the two original operations', async () => {
    stubFetch([incident])
    const user = userEvent.setup()

    renderView(provider)

    await user.click(await screen.findByRole('tab', { name: 'Incidents (1)' }))
    await user.click((await screen.findByText('EDF')).closest('tr')!)

    const dialog = await screen.findByRole('dialog', { name: /EDF · Prélèvement rejeté/ })
    expect(await within(dialog).findByText(/05\/09\/2026 · PRELEVEMENT EDF/)).toBeInTheDocument()
    expect(await within(dialog).findByText(/06\/09\/2026 · REJET PRLV ELECTRICITE DE FRANCE/)).toBeInTheDocument()
    expect(within(dialog).getAllByRole('link', { name: 'Ouvrir la fiche' })).toHaveLength(2)
  })

  test('an account with no rejection says so', async () => {
    stubFetch([])
    const user = userEvent.setup()

    renderView(provider)

    await user.click(await screen.findByRole('tab', { name: 'Incidents (0)' }))
    expect(await screen.findByText('Aucun incident sur ce compte')).toBeInTheDocument()
  })

  test('an unreadable list says so instead of showing an empty one', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, json: async () => ({}) })))
    const user = userEvent.setup()

    renderView(provider)

    await user.click(await screen.findByRole('tab', { name: 'Incidents' }))
    expect(await screen.findByText('Impossible de lire les incidents')).toBeInTheDocument()
    expect(screen.queryByText('Aucun incident sur ce compte')).not.toBeInTheDocument()
  })

  /** A server that keeps the settled rejections apart, as the API does. */
  const stubSettlement = (all: (typeof incident)[], { failSettle = false, failUnsettle = false } = {}) => {
    const settledIds = new Set<string>()
    const calls: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const path = String(url)
        const method = init?.method ?? 'GET'
        const match = path.match(/\/api\/finance\/transactions\/([^/]+)\/rejection-settlement$/)
        if (match) {
          calls.push(`${method} ${match[1]}`)
          if ((method === 'POST' && failSettle) || (method === 'DELETE' && failUnsettle)) {
            return { ok: false, json: async () => ({ error: 'boom' }) }
          }
          if (method === 'POST') settledIds.add(match[1])
          else settledIds.delete(match[1])
          return { ok: true, json: async () => ({ success: true }) }
        }
        if (path.includes('/timeline')) return { ok: true, json: async () => timelinePage() }
        const wantsSettled = path.endsWith('?settled=true')
        return {
          ok: true,
          json: async () => ({
            incidents: all
              .filter((i) => settledIds.has(i.debitId) === wantsSettled)
              .map((i) => ({ ...i, settledAt: settledIds.has(i.debitId) ? '2026-10-16T10:00:00+00:00' : null })),
          }),
        }
      }),
    )
    return calls
  }

  test('« Régularisé » takes the rejection out of the pile into « Régularisés », « Annuler » puts it back', async () => {
    const second = { ...incident, debitId: '01D2', creditId: '01C2', counterpartyName: 'Mme Martin', kind: 'transfer' }
    const calls = stubSettlement([incident, second])
    const user = userEvent.setup()

    renderView()
    await user.click(await screen.findByRole('tab', { name: 'Incidents (2)' }))
    await user.click(await screen.findByRole('button', { name: 'Régularisé : EDF' }))

    await screen.findByRole('tab', { name: 'Incidents (1)' })
    expect(calls).toEqual(['POST 01DEBIT'])
    expect(screen.queryByRole('button', { name: 'Régularisé : EDF' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Régularisé : Mme Martin' })).toBeInTheDocument()
    expect(screen.getByText('Rejet EDF régularisé')).toBeInTheDocument()
    await user.click(screen.getByText('Régularisés (1)'))
    expect(await screen.findByRole('button', { name: 'Remettre dans la pile : EDF' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Annuler' }))

    await screen.findByRole('tab', { name: 'Incidents (2)' })
    expect(calls).toEqual(['POST 01DEBIT', 'DELETE 01DEBIT'])
    expect(screen.getByRole('button', { name: 'Régularisé : EDF' })).toBeInTheDocument()
    expect(screen.queryByText('Régularisés (1)')).not.toBeInTheDocument()
  })

  test('a settled rejection can be put back from « Régularisés » after « Annuler » is gone', async () => {
    const calls = stubSettlement([incident])
    const user = userEvent.setup()

    renderView()
    await user.click(await screen.findByRole('tab', { name: 'Incidents (1)' }))
    await user.click(await screen.findByRole('button', { name: 'Régularisé : EDF' }))
    await user.click(await screen.findByText('Régularisés (1)'))
    await user.click(await screen.findByRole('button', { name: 'Remettre dans la pile : EDF' }))

    await screen.findByRole('tab', { name: 'Incidents (1)' })
    expect(calls).toEqual(['POST 01DEBIT', 'DELETE 01DEBIT'])
  })

  test('a refused settlement says so and leaves the rejection in the pile', async () => {
    stubSettlement([incident], { failSettle: true })
    const user = userEvent.setup()

    renderView()
    await user.click(await screen.findByRole('tab', { name: 'Incidents (1)' }))
    await user.click(await screen.findByRole('button', { name: 'Régularisé : EDF' }))

    expect(await screen.findByText('Impossible de régulariser ce rejet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Régularisé : EDF' })).toBeInTheDocument()
    expect(screen.queryByText('Rejet EDF régularisé')).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('a refused « Annuler » says so and leaves the rejection settled', async () => {
    stubSettlement([incident], { failUnsettle: true })
    const user = userEvent.setup()

    renderView()
    await user.click(await screen.findByRole('tab', { name: 'Incidents (1)' }))
    await user.click(await screen.findByRole('button', { name: 'Régularisé : EDF' }))
    await user.click(await screen.findByRole('button', { name: 'Annuler' }))

    expect(await screen.findByText('Impossible de remettre ce rejet dans la pile')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Incidents (0)' })).toBeInTheDocument()
    expect(screen.getByText('Aucun rejet à traiter.')).toBeInTheDocument()
    expect(screen.getByText('Régularisés (1)')).toBeInTheDocument()
  })

  test('a rejection detected while the screen is open joins the tab without a reload', async () => {
    let incidents: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => ({
        ok: true,
        json: async () =>
          String(url).includes('/incidents')
            ? { incidents: String(url).endsWith('?settled=true') ? [] : incidents }
            : timelinePage(),
      })),
    )

    renderView(provider)
    await screen.findByRole('tab', { name: 'Incidents (0)' })

    incidents = [incident]
    await emitMercure()

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Incidents (1)' })).toBeInTheDocument())
  })
})

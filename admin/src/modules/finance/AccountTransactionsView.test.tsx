import { describe, test, expect, vi, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { AccountTransactionsEmpty, AccountTransactionsView } from './AccountTransactionsView'

const mercureListeners: Array<(data?: string) => void> = []
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], cb: (data?: string) => void) => {
    mercureListeners.push(cb)
  },
}))
const emitMercure = () => act(() => mercureListeners.forEach((listener) => listener('{}')))

const ACCOUNT_IRI = '/api/accounts/01ABC'

const emptyProvider = testDataProvider({
  getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
  getOne: (() =>
    Promise.resolve({
      data: { id: ACCOUNT_IRI, name: 'Compte joint' },
    })) as unknown as DataProvider['getOne'],
})

const LocationState = () => {
  const { pathname, state } = useLocation()
  return <pre data-testid="location">{JSON.stringify({ pathname, state })}</pre>
}

/** Answers the incidents route with `incidents`, every other route with `transfer`. */
const stubFetch = (transfer: unknown = {}, incidents: unknown[] = []) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => ({
      ok: true,
      json: async () =>
        String(url).endsWith('/incidents')
          ? { incidents }
          : String(url).endsWith('/incidents?settled=true')
            ? { incidents: [] }
            : transfer,
    })),
  )

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

describe('AccountTransactionsView', () => {
  test('an account with no operation still shows the button, and it preselects the account', async () => {
    const user = userEvent.setup()

    render(
      <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
        <AdminContext dataProvider={emptyProvider}>
          <Routes>
            <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
            <Route path="*" element={<LocationState />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

    // Once the empty state replaces the list, the toolbar and its button are gone:
    // the one button left is the empty state's own.
    const placeholder = (await screen.findByText('Aucune transaction sur ce compte')).parentElement!
    const button = within(placeholder).getByRole('link', { name: 'Ajouter une transaction' })
    expect(screen.getAllByRole('link', { name: 'Ajouter une transaction' })).toHaveLength(1)

    await user.click(button)

    const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
    expect(location.pathname).toBe('/transactions/create')
    expect(location.state.record).toEqual({ account: ACCOUNT_IRI })
  })

  test('the app bar carries one title, the account\'s, so a long one can end in an ellipsis (MAG-387)', async () => {
    render(
      <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
        <AdminContext dataProvider={emptyProvider}>
          <h6 id="react-admin-title" data-testid="app-bar-title" />
          <Routes>
            <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

    await screen.findByText('Aucune transaction sur ce compte')
    await waitFor(() => expect(screen.getByTestId('app-bar-title')).toHaveTextContent('Transactions — Compte joint'))
    expect(screen.getByTestId('app-bar-title').textContent).toBe('Transactions — Compte joint')
  })

  test('the title tells the bank, the currency and the name of the account (MAG-501)', async () => {
    const provider = testDataProvider({
      getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
      getOne: (() =>
        Promise.resolve({
          data: { id: ACCOUNT_IRI, name: 'Compte joint', bank: 'Boursorama', currency: 'EUR' },
        })) as unknown as DataProvider['getOne'],
    })
    render(
      <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
        <AdminContext dataProvider={provider}>
          <h6 id="react-admin-title" data-testid="app-bar-title" />
          <Routes>
            <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

    await screen.findByText('Aucune transaction sur ce compte')
    await waitFor(() =>
      expect(screen.getByTestId('app-bar-title')).toHaveTextContent('Transactions — Boursorama · EUR · Compte joint'),
    )
  })

  test('a recette reads Reçue or Attendue in the list, a dépense Dépensée — never the other way round', async () => {
    const rows = [
      { id: '/api/transactions/1', label: 'Salaire', amountCents: 235000, currency: 'EUR', status: 'spent', bookedAt: '2026-07-31' },
      { id: '/api/transactions/2', label: 'Prime', amountCents: 50000, currency: 'EUR', status: 'planned', bookedAt: '2026-08-15' },
      { id: '/api/transactions/3', label: 'Netflix', amountCents: -1399, currency: 'EUR', status: 'spent', bookedAt: '2026-07-03' },
    ]
    const provider = testDataProvider({
      getList: (() => Promise.resolve({ data: rows, total: rows.length })) as unknown as DataProvider['getList'],
      getOne: (() =>
        Promise.resolve({ data: { id: ACCOUNT_IRI, name: 'Compte joint' } })) as unknown as DataProvider['getOne'],
    })

    render(
      <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
        <AdminContext dataProvider={provider}>
          <Routes>
            <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

    const rowOf = async (label: string) => (await screen.findByText(label)).closest('tr')!

    expect(within(await rowOf('Salaire')).getByText('Reçue')).toBeInTheDocument()
    expect(within(await rowOf('Prime')).getByText('Attendue')).toBeInTheDocument()
    expect(within(await rowOf('Netflix')).getByText('Dépensée')).toBeInTheDocument()
    expect(screen.getAllByText('Dépensée')).toHaveLength(1)
  })

  describe('one line of pills, one sheet', () => {
    const rows = [
      { id: '/api/transactions/01A', label: 'Courses', amountCents: -2000, currency: 'EUR', status: 'spent', bookedAt: '2026-09-14', transferKind: 'none', category: null },
      { id: '/api/transactions/01B', label: 'Loyer', amountCents: -50000, currency: 'EUR', status: 'spent', bookedAt: '2026-09-01', transferKind: 'none', category: '/api/categories/01C', recurringOperation: '/api/recurring_operations/01S' },
    ]
    const provider = testDataProvider({
      getList: (() => Promise.resolve({ data: rows, total: rows.length })) as unknown as DataProvider['getList'],
      getOne: ((resource: string) =>
        Promise.resolve({
          data: resource === 'recurring_operations' ? { id: '/api/recurring_operations/01S', label: 'Loyer mensuel' } : { id: ACCOUNT_IRI, name: 'Compte joint' },
        })) as unknown as DataProvider['getOne'],
    })

    afterEach(() => vi.unstubAllGlobals())

    const renderView = () => {
      stubFetch()

      return render(
        <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
          <AdminContext dataProvider={provider}>
            <Routes>
              <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
              <Route path="*" element={<LocationState />} />
            </Routes>
          </AdminContext>
        </MemoryRouter>,
      )
    }

    test('a line without category is « À trier », a line of a series names it, and nothing else sits on the row', async () => {
      renderView()

      const sorted = (await screen.findByText('Courses')).closest('tr')!
      expect(within(sorted).getByText('À trier')).toBeInTheDocument()
      const series = (await screen.findByText('Loyer')).closest('tr')!
      expect(await within(series).findByText('Série · Loyer mensuel')).toBeInTheDocument()
      expect(within(series).queryByText('À trier')).not.toBeInTheDocument()
      // The pencil, the « make recurring » button and the detachable chip are gone: the sheet holds them.
      expect(screen.queryByRole('link', { name: /Modifier|Éditer/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /En faire une opération récurrente/ })).not.toBeInTheDocument()
      expect(screen.queryByLabelText(/Détacher/)).not.toBeInTheDocument()
      expect(within(series).getByRole('button', { name: /delete/i })).toBeInTheDocument()
    })

    test('a click on a pill opens the sheet of the line, like the row', async () => {
      const user = userEvent.setup()
      renderView()

      await user.click(await screen.findByText('À trier'))

      const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
      expect(location.pathname).toBe('/transactions/%2Fapi%2Ftransactions%2F01A')
    })

    test('a click on the row opens the sheet', async () => {
      const user = userEvent.setup()
      renderView()

      await user.click(await screen.findByText('Courses'))

      const location = JSON.parse((await screen.findByTestId('location')).textContent ?? '{}')
      expect(location.pathname).toBe('/transactions/%2Fapi%2Ftransactions%2F01A')
    })
  })

  describe('internal transfers', () => {
    const rows = [
      {
        id: '/api/transactions/01OUT',
        label: 'Virement vers Courant',
        amountCents: -300000,
        currency: 'EUR',
        status: 'spent',
        bookedAt: '2026-09-12',
        transferKind: 'internal',
        transferSource: 'auto',
        counterpart: '/api/transactions/01IN',
      },
      {
        id: '/api/transactions/01SHOP',
        label: 'Supermarché',
        amountCents: -8000,
        currency: 'EUR',
        status: 'spent',
        bookedAt: '2026-09-14',
        transferKind: 'none',
        transferSource: 'auto',
        counterpart: null,
      },
    ]
    const providerOf = (data: typeof rows) =>
      testDataProvider({
        getList: (() => Promise.resolve({ data, total: data.length })) as unknown as DataProvider['getList'],
        getOne: (() =>
          Promise.resolve({ data: { id: ACCOUNT_IRI, name: 'Livret' } })) as unknown as DataProvider['getOne'],
      })

    afterEach(() => {
      vi.unstubAllGlobals()
    })

    const renderView = (data = rows) =>
      render(
        <MemoryRouter initialEntries={['/accounts/01ABC/transactions']}>
          <AdminContext dataProvider={providerOf(data)}>
            <Routes>
              <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
            </Routes>
          </AdminContext>
        </MemoryRouter>,
      )

    test('a marked line carries the badge and names its counterpart; an ordinary one does not', async () => {
      stubFetch({
            transferKind: 'internal',
            transferSource: 'auto',
            counterpart: {
              id: '01IN',
              label: 'Virement du Livret',
              amountCents: 300000,
              currency: 'EUR',
              bookedAt: '2026-09-13',
              accountId: '01CHK',
              accountName: 'Courant',
            },
          })

      renderView()

      const marked = (await screen.findByText('Virement vers Courant')).closest('tr')!
      expect(within(marked).getByText('Virement interne')).toBeInTheDocument()
      expect(await within(marked).findByText(/Courant · 13\/09\/2026 · Virement du Livret/)).toBeInTheDocument()

      const ordinary = (await screen.findByText('Supermarché')).closest('tr')!
      expect(within(ordinary).queryByText('Virement interne')).not.toBeInTheDocument()
      expect(screen.getAllByText('Virement interne')).toHaveLength(1)
    })

    test('a line paired with nothing says the other account is not followed', async () => {
      stubFetch({ transferKind: 'internal', transferSource: 'manual', counterpart: null })

      renderView()

      const marked = (await screen.findByText('Virement vers Courant')).closest('tr')!
      expect(await within(marked).findByText(/Sans contrepartie/)).toBeInTheDocument()
    })

    test('a rejected payment reads « Rejeté » and names the credit that gave it back', async () => {
      stubFetch({
            transferKind: 'rejected',
            transferSource: 'auto',
            counterpart: {
              id: '01BACK',
              label: 'Rejet virement Courant',
              amountCents: 300000,
              currency: 'EUR',
              bookedAt: '2026-09-15',
              accountId: '01ABC',
              accountName: 'Livret',
            },
          })

      renderView([{ ...rows[0], transferKind: 'rejected', counterpart: '/api/transactions/01BACK' }])

      const rejected = (await screen.findByText('Virement vers Courant')).closest('tr')!
      expect(within(rejected).getByText('Rejeté')).toBeInTheDocument()
      expect(await within(rejected).findByText(/Rendu par :/)).toBeInTheDocument()
      expect(within(rejected).queryByText('Virement interne')).not.toBeInTheDocument()
    })
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
    getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
    getOne: ((resource: string, params: { id: string }) =>
      Promise.resolve({
        data: resource === 'transactions' ? legs[params.id] : { id: ACCOUNT_IRI, name: 'Compte joint' },
      })) as unknown as DataProvider['getOne'],
  })

  const renderView = (entry = '/accounts/01ABC/transactions') =>
    render(
      <MemoryRouter initialEntries={[entry]}>
        <AdminContext dataProvider={provider}>
          <Routes>
            <Route path="/accounts/:id/transactions" element={<AccountTransactionsView />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('the home pill lands on the incidents tab with `?tab=incidents`', async () => {
    stubFetch({}, [incident])

    renderView('/accounts/01ABC/transactions?tab=incidents')

    expect(await screen.findByRole('tab', { name: 'Incidents (1)', selected: true })).toBeInTheDocument()
    expect(await screen.findByText('EDF')).toBeVisible()
  })

  test('the tab carries the number of rejections, and each one reads as a single line', async () => {
    stubFetch({}, [incident, { ...incident, debitId: '01D2', creditId: '01C2', counterpartyName: 'Mme Martin', kind: 'transfer', amountCents: 5000 }])
    const user = userEvent.setup()

    renderView()

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
    stubFetch({}, [incident])
    const user = userEvent.setup()

    renderView()

    await user.click(await screen.findByRole('tab', { name: 'Incidents (1)' }))
    await user.click((await screen.findByText('EDF')).closest('tr')!)

    const dialog = await screen.findByRole('dialog', { name: /EDF · Prélèvement rejeté/ })
    expect(await within(dialog).findByText(/05\/09\/2026 · PRELEVEMENT EDF/)).toBeInTheDocument()
    expect(await within(dialog).findByText(/06\/09\/2026 · REJET PRLV ELECTRICITE DE FRANCE/)).toBeInTheDocument()
    expect(within(dialog).getAllByRole('link', { name: 'Ouvrir la fiche' })).toHaveLength(2)
  })

  test('an account with no rejection says so', async () => {
    stubFetch({}, [])
    const user = userEvent.setup()

    renderView()

    await user.click(await screen.findByRole('tab', { name: 'Incidents (0)' }))
    expect(await screen.findByText('Aucun incident sur ce compte')).toBeInTheDocument()
  })

  test('an unreadable list says so instead of showing an empty one', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, json: async () => ({}) })))
    const user = userEvent.setup()

    renderView()

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
        json: async () => ({ incidents: String(url).endsWith('?settled=true') ? [] : incidents }),
      })),
    )

    renderView()
    await screen.findByRole('tab', { name: 'Incidents (0)' })

    incidents = [incident]
    await emitMercure()

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Incidents (1)' })).toBeInTheDocument())
  })
})

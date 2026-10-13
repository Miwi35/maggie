import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import { SavingsAdvancesPage } from './SavingsAdvancesPage'
import type { SavingsAdvancesData } from './useSavingsAdvances'

const mercureListeners: Array<(data?: string) => void> = []
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], cb: (data?: string) => void) => {
    mercureListeners.push(cb)
  },
}))

const OPEN = {
  id: 'adv-1',
  sourceAccountId: 'livret',
  sourceAccountName: 'Livret',
  amountCents: 150000,
  openedOn: '2026-10-11',
  repaidCents: 50000,
  outstandingCents: 100000,
  isClosed: false,
}

const CLOSED = {
  id: 'adv-0',
  sourceAccountId: 'pel',
  sourceAccountName: 'PEL',
  amountCents: 30000,
  openedOn: '2026-09-02',
  repaidCents: 30000,
  outstandingCents: 0,
  isClosed: true,
}

const DELAY = {
  isConfigured: true,
  salaryExpectedDay: 25,
  referenceCents: 495000,
  isLate: true,
  delayDays: 18,
  shortfallCents: 495000,
}

const WITH_ADVANCES: SavingsAdvancesData = {
  advances: [OPEN, CLOSED],
  bySource: [{ sourceAccountId: 'livret', sourceAccountName: 'Livret', outstandingCents: 100000, openCount: 1 }],
  totalOutstandingCents: 100000,
  salaryDelay: DELAY,
  proposals: [
    {
      fromAccountId: 'courant',
      toAccountId: 'livret',
      amountCents: 100000,
      advanceId: 'adv-1',
      label: 'Remise avance Livret',
      currency: 'EUR',
      isPartial: false,
    },
  ],
  proposalsTotalCents: 100000,
  unproposed: [],
  unproposedCents: 0,
}

const EMPTY: SavingsAdvancesData = {
  advances: [],
  bySource: [],
  totalOutstandingCents: 0,
  salaryDelay: { ...DELAY, isLate: false, delayDays: 0, shortfallCents: 0 },
  proposals: [],
  proposalsTotalCents: 0,
  unproposed: [],
  unproposedCents: 0,
}

const json = (body: unknown, ok = true) => Promise.resolve({ ok, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()

const renderPage = () =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <SavingsAdvancesPage />
      <Notification />
    </AdminContext>,
  )

const serve = (data: SavingsAdvancesData) =>
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    if (url === '/api/finance/savings-advances') return json(data)
    if (init?.method) return json({})

    return json({})
  })

describe('SavingsAdvancesPage', () => {
  beforeEach(() => {
    mercureListeners.length = 0
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows what is left to give back, in total and by source, with each advance', async () => {
    serve(WITH_ADVANCES)
    renderPage()

    expect(await screen.findByTestId('savings-total-outstanding')).toHaveTextContent('1 000,00')
    const source = screen.getByTestId('savings-source')
    expect(within(source).getByText(/Livret/)).toBeInTheDocument()
    expect(within(source).getByText(/1 000,00/)).toBeInTheDocument()

    const [open, closed] = screen.getAllByTestId('savings-advance')
    expect(within(open).getByText('11/10/2026')).toBeInTheDocument()
    expect(open).toHaveTextContent('1 500,00')
    expect(open).toHaveTextContent('500,00')
    expect(open).toHaveTextContent('1 000,00')
    expect(screen.getAllByRole('columnheader', { name: 'Avancé' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('columnheader', { name: 'Rendu' }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('columnheader', { name: 'Reste à rendre' }).length).toBeGreaterThan(0)
    expect(screen.getByText('Avances soldées')).toBeInTheDocument()
    expect(within(closed).getByText('PEL')).toBeInTheDocument()
    expect(screen.getByText(/Salaire en retard de 18 jours/)).toBeInTheDocument()
  })

  test('with no advance, says so instead of showing an empty screen', async () => {
    serve(EMPTY)
    renderPage()

    expect(await screen.findByText(/Aucune avance en cours : rien à remettre/)).toBeInTheDocument()
    expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent('0,00')
    expect(screen.getByText('Aucune avance en cours.')).toBeInTheDocument()
    expect(screen.queryByText('Contre-virements proposés')).not.toBeInTheDocument()
    expect(screen.queryByText('Avances soldées')).not.toBeInTheDocument()
    expect(screen.queryByText(/Salaire en retard/)).not.toBeInTheDocument()
  })

  test('without an expected day it asks for it and announces no delay', async () => {
    serve({
      ...WITH_ADVANCES,
      salaryDelay: { ...DELAY, salaryExpectedDay: null, isConfigured: false, isLate: false, delayDays: 0, shortfallCents: 0 },
    })
    renderPage()

    expect(await screen.findByText(/Quel jour du mois attendez-vous votre salaire/)).toBeInTheDocument()
    expect(screen.queryByText(/Salaire en retard/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('accepting a proposal records the transfer and shows the advance settled', async () => {
    const settled: SavingsAdvancesData = {
      ...WITH_ADVANCES,
      advances: [{ ...OPEN, repaidCents: 150000, outstandingCents: 0, isClosed: true }, CLOSED],
      bySource: [],
      totalOutstandingCents: 0,
      proposals: [],
      proposalsTotalCents: 0,
    }
    let transfers = 0
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/transactions') {
        transfers += 1

        return json({})
      }

      return json(transfers === 2 ? settled : WITH_ADVANCES)
    })
    renderPage()

    const proposal = await screen.findByTestId('savings-proposal')
    expect(proposal).toHaveTextContent('1 000,00')
    expect(proposal).toHaveTextContent('Livret')
    await userEvent.click(within(proposal).getByRole('button', { name: /Je l'ai fait/ }))

    await waitFor(() => expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent('0,00'))
    expect(screen.queryByTestId('savings-proposal')).not.toBeInTheDocument()
    expect(transfers).toBe(2)
    const posts = fetchMock.mock.calls.filter(([url]) => url === '/api/transactions').map(([, init]) => JSON.parse(init.body))
    expect(posts.map((post) => post.amountCents)).toEqual([-100000, 100000])
  })

  test('a refused transaction is reported and the proposal stays', async () => {
    fetchMock.mockImplementation((url: string) => (url === '/api/transactions' ? json({}, false) : json(WITH_ADVANCES)))
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /Je l'ai fait/ }))

    expect(await screen.findByText("Impossible d'enregistrer la transaction")).toBeInTheDocument()
    expect(screen.getByTestId('savings-proposal')).toBeInTheDocument()
    expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent('1 000,00')
  })

  test('a reduced proposal and what could not be proposed are said', async () => {
    serve({
      ...WITH_ADVANCES,
      proposals: [{ ...WITH_ADVANCES.proposals[0], amountCents: 60000, isPartial: true }],
      unproposed: [
        { advanceId: 'adv-1', sourceAccountId: 'livret', sourceAccountName: 'Livret', amountCents: 40000, reason: 'insufficient_balance' },
      ],
      unproposedCents: 40000,
    })
    renderPage()

    expect(await screen.findByText(/réduit au solde disponible/)).toBeInTheDocument()
    expect(screen.getByText(/ne peuvent pas être proposés/)).toBeInTheDocument()
    expect(screen.getByText(/Le solde du compte courant ne suffit pas/)).toBeInTheDocument()
  })

  test('saving the expected day patches the settings and reads the screen again', async () => {
    serve({ ...WITH_ADVANCES, salaryDelay: { ...DELAY, salaryExpectedDay: null, isConfigured: false, isLate: false } })
    renderPage()

    const input = await screen.findByLabelText('Jour du mois')
    await userEvent.type(input, '25')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/finance/treasury-settings',
        expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ salaryExpectedDay: 25 }) }),
      ),
    )
    expect(await screen.findByText('Jour de salaire attendu enregistré')).toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/finance/savings-advances').length).toBeGreaterThan(1)
  })

  test('a day outside 1 to 31 cannot be saved', async () => {
    serve(WITH_ADVANCES)
    renderPage()

    const input = await screen.findByLabelText('Jour du mois')
    await userEvent.clear(input)
    await userEvent.type(input, '32')

    expect(screen.getByText('Un jour de 1 à 31.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('a refused setting is reported', async () => {
    fetchMock.mockImplementation((url: string) =>
      url === '/api/finance/treasury-settings' ? json({}, false) : json(WITH_ADVANCES),
    )
    renderPage()

    const input = await screen.findByLabelText('Jour du mois')
    await userEvent.clear(input)
    await userEvent.type(input, '10')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText("Impossible d'enregistrer le jour de salaire attendu")).toBeInTheDocument()
  })

  test('an API error shows a message and a way to retry', async () => {
    fetchMock.mockImplementationOnce(() => json({}, false)).mockImplementation(() => json(WITH_ADVANCES))
    renderPage()

    expect(await screen.findByText("Impossible de lire les avances d'épargne")).toBeInTheDocument()
    expect(screen.queryByTestId('savings-total-outstanding')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('savings-total-outstanding')).toHaveTextContent('1 000,00')
    expect(screen.queryByText("Impossible de lire les avances d'épargne")).not.toBeInTheDocument()
  })

  test('another tab settling an advance updates this one', async () => {
    fetchMock.mockImplementationOnce(() => json(WITH_ADVANCES)).mockImplementation(() => json(EMPTY))
    renderPage()
    expect(await screen.findByTestId('savings-total-outstanding')).toHaveTextContent('1 000,00')

    await act(async () => mercureListeners.at(-1)?.('{}'))

    await waitFor(() => expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent('0,00'))
  })
})

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

const ZERO = /^0,00\s*€$/

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

  test('shows what is left to give back in total, and each advance in one table', async () => {
    serve(WITH_ADVANCES)
    renderPage()

    expect(await screen.findByTestId('savings-total-outstanding')).toHaveTextContent('1 000,00')

    const [open, closed] = screen.getAllByTestId('savings-advance')
    expect(within(open).getByText('11/10/2026')).toBeInTheDocument()
    expect(open).toHaveTextContent('Livret')
    expect(open).toHaveTextContent('1 500,00')
    expect(open).toHaveTextContent('500,00')
    expect(open).toHaveTextContent('1 000,00')
    expect(screen.getByRole('columnheader', { name: 'Avancé' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Rendu' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Reste à rendre' })).toBeInTheDocument()
    expect(within(closed).getByText('PEL')).toBeInTheDocument()
    expect(screen.queryByText('Avances soldées')).not.toBeInTheDocument()
    expect(screen.queryByTestId('savings-source')).not.toBeInTheDocument()
  })

  test('has no salary day card and no teaching paragraph: the day is set in « Régler » of the treasury', async () => {
    serve(WITH_ADVANCES)
    renderPage()

    await screen.findByTestId('savings-total-outstanding')
    expect(screen.queryByLabelText('Jour du mois')).not.toBeInTheDocument()
    expect(screen.queryByText(/Salaire en retard/)).not.toBeInTheDocument()
    expect(screen.queryByText('Contre-virements proposés')).not.toBeInTheDocument()
    expect(screen.queryByText(/Faites le virement dans votre banque/)).not.toBeInTheDocument()
  })

  test('with no advance, says so in a few words', async () => {
    serve(EMPTY)
    renderPage()

    expect(await screen.findByText('Aucune avance en cours.')).toBeInTheDocument()
    expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent(ZERO)
    expect(screen.queryByText(/rien à remettre sur vos comptes/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remettre' })).not.toBeInTheDocument()
  })

  test('only an open advance with a proposal has a « Remettre » button', async () => {
    serve(WITH_ADVANCES)
    renderPage()

    const [open, closed] = await screen.findAllByTestId('savings-advance')

    expect(within(open).getByRole('button', { name: 'Remettre' })).toBeInTheDocument()
    expect(within(closed).queryByRole('button', { name: 'Remettre' })).not.toBeInTheDocument()
  })

  test('« Remettre » records the transfer and shows the advance settled', async () => {
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

    const [open] = await screen.findAllByTestId('savings-advance')
    await userEvent.click(within(open).getByRole('button', { name: 'Remettre' }))

    await waitFor(() => expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent(ZERO))
    expect(screen.queryByRole('button', { name: 'Remettre' })).not.toBeInTheDocument()
    expect(transfers).toBe(2)
    const posts = fetchMock.mock.calls.filter(([url]) => url === '/api/transactions').map(([, init]) => JSON.parse(init.body))
    expect(posts.map((post) => post.amountCents)).toEqual([-100000, 100000])
    expect(await screen.findByText('Transaction enregistrée : l’avance est soldée')).toBeInTheDocument()
  })

  test('a refused transaction is reported and the button stays', async () => {
    fetchMock.mockImplementation((url: string) => (url === '/api/transactions' ? json({}, false) : json(WITH_ADVANCES)))
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Remettre' }))

    expect(await screen.findByText("Impossible d'enregistrer la transaction")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remettre' })).toBeInTheDocument()
    expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent('1 000,00')
  })

  test('a credit refused after the debit says the debit is recorded', async () => {
    let legs = 0
    fetchMock.mockImplementation((url: string) => {
      if (url !== '/api/transactions') return json(WITH_ADVANCES)
      legs += 1

      return json({}, legs === 1)
    })
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Remettre' }))

    expect(await screen.findByText(/Le débit est enregistré, pas le crédit/)).toBeInTheDocument()
  })

  test('a reduced proposal shows the amount it gives back, and what could not be proposed shows its reason', async () => {
    serve({
      ...WITH_ADVANCES,
      advances: [OPEN, { ...OPEN, id: 'adv-2', sourceAccountId: 'pea', sourceAccountName: 'PEA', openedOn: '2026-10-12' }],
      proposals: [{ ...WITH_ADVANCES.proposals[0], amountCents: 60000, isPartial: true }],
      unproposed: [
        { advanceId: 'adv-2', sourceAccountId: 'pea', sourceAccountName: 'PEA', amountCents: 40000, reason: 'insufficient_balance' },
      ],
      unproposedCents: 40000,
    })
    renderPage()

    const [reduced, unproposed] = await screen.findAllByTestId('savings-advance')
    expect(reduced).toHaveTextContent('Partiel : 600,00')
    expect(within(unproposed).queryByRole('button', { name: 'Remettre' })).not.toBeInTheDocument()
    expect(unproposed).toHaveTextContent('Le solde du compte courant ne suffit pas.')
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

    await waitFor(() => expect(screen.getByTestId('savings-total-outstanding')).toHaveTextContent(ZERO))
  })
})

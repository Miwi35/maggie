import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { TreasuryPage } from './TreasuryPage'
import { emitMercure } from '../../test/mercure'
import type { TreasuryOutlook } from './useTreasuryOutlook'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const OUTLOOK: TreasuryOutlook = {
  today: '2026-10-13',
  salary: {
    isConfigured: true,
    expectedOn: '2026-09-13',
    referenceCents: 495000,
    isReceived: false,
    isLate: true,
    delayDays: 30,
    shortfallCents: 495000,
  },
  balanceCents: -12000,
  projection: [
    { date: '2026-10-13', balanceCents: -42000, movements: [{ id: 'm1', label: 'Loyer', amountCents: -30000, status: 'planned', accountId: 'a1', accountName: 'Courant', source: 'transaction' }] },
    { date: '2026-10-14', balanceCents: -42000, movements: [] },
  ],
  balanceBeforeSalary: { onDate: '2026-10-13', balanceCents: -42000 },
  overdraft: { isForecast: true, onDate: '2026-10-13', worstBalanceCents: -42000, limitCents: 0 },
  advice: {
    amountCents: 42000,
    sourceAccountId: 'livret-1',
    sourceAccountName: 'Livret',
    sourceBalanceCents: 600000,
    sourceAmountCents: 42000,
    isCushion: false,
    alternatives: [],
    uncoveredCents: 0,
    touchesCushion: false,
  },
  outstandingAdvancesCents: 0,
}

const json = (body: unknown, ok = true) => ({ ok, json: async () => body }) as Response

const fetchMock = vi.fn()

function serve(outlook: TreasuryOutlook | null, settings = { id: 's1', salaryExpectedDay: 13, overdraftLimitCents: 0 }) {
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === 'PATCH') return Promise.resolve(json({ success: true, ...settings }))
    if (url === '/api/finance/treasury-outlook') {
      return Promise.resolve(outlook ? json(outlook) : json({ error: 'boom' }, false))
    }

    return Promise.resolve(json(settings))
  })
}

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()}>
        <TreasuryPage />
      </AdminContext>
    </MemoryRouter>,
  )

const patches = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'PATCH')

describe('TreasuryPage', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('announces the delay in days, the overdraft with its date and the advice', async () => {
    serve(OUTLOOK)
    renderPage()

    expect(await screen.findByTestId('salary-delay')).toHaveTextContent('Salaire en retard de 30 jours')
    expect(screen.getByTestId('overdraft-forecast')).toHaveTextContent('Découvert prévu le 13 oct.')
    expect(screen.getByTestId('overdraft-forecast')).toHaveTextContent('420,00')
    expect(screen.getByTestId('advice')).toHaveTextContent('Avancer 420,00')
    expect(screen.getByTestId('advice')).toHaveTextContent('Livret')
    expect(screen.getByTestId('treasury-chart')).toBeInTheDocument()
    expect(screen.getByTestId('treasury-overdraft-zone')).toBeInTheDocument()
    expect(screen.getAllByTestId('treasury-day')).toHaveLength(2)
    expect(screen.getAllByTestId('treasury-day')[0]).toHaveAttribute('data-overdraft', 'true')
  })

  test('recurring expenses past the expected salary day are listed and weigh on the balance', async () => {
    serve({
      ...OUTLOOK,
      salary: { ...OUTLOOK.salary, isLate: false, isReceived: true, delayDays: 0, shortfallCents: 0, expectedOn: '2026-10-25' },
      projection: [
        ...OUTLOOK.projection,
        {
          date: '2026-10-26',
          balanceCents: -139700,
          movements: [{ id: 'r1', label: 'Taxe foncière', amountCents: -97700, status: 'expected', accountId: 'a1', accountName: 'Courant', source: 'recurring' }],
        },
        {
          date: '2026-10-28',
          balanceCents: -157700,
          movements: [{ id: 'r2', label: 'Claude', amountCents: -18000, status: 'expected', accountId: 'a1', accountName: 'Courant', source: 'recurring' }],
        },
      ],
    })
    renderPage()

    const days = await screen.findAllByTestId('treasury-day')

    expect(days).toHaveLength(4)
    expect(days[2]).toHaveTextContent('Taxe foncière (récurrent) (-977,00')
    expect(days[2]).toHaveTextContent('-1')
    expect(days[3]).toHaveTextContent('Claude (récurrent)')
    expect(days[0]).not.toHaveTextContent('récurrent')
  })

  test('a one-day delay is written in the singular', async () => {
    serve({ ...OUTLOOK, salary: { ...OUTLOOK.salary, delayDays: 1 } })
    renderPage()

    expect(await screen.findByTestId('salary-delay')).toHaveTextContent('Salaire en retard de 1 jour')
    expect(screen.getByTestId('salary-delay')).not.toHaveTextContent('1 jours')
  })

  test('the advance button opens the new transaction, pre-filled and not saved', async () => {
    serve(OUTLOOK)
    renderPage()

    const button = await screen.findByRole('link', { name: "Préparer l'avance" })
    const href = button.getAttribute('href') ?? ''
    const [path, search] = href.split('?')

    expect(path).toBe('/transactions/create')
    expect(JSON.parse(new URLSearchParams(search).get('source') ?? '{}')).toEqual({
      account: '/api/accounts/livret-1',
      label: 'Avance vers le compte courant',
      amountCents: -42000,
      bookedAt: '2026-10-13',
      status: 'spent',
      currency: 'EUR',
    })
    expect(patches()).toHaveLength(0)
  })

  test('a cushion source is flagged as such', async () => {
    serve({ ...OUTLOOK, advice: { ...OUTLOOK.advice!, isCushion: true, touchesCushion: true } })
    renderPage()

    expect(await screen.findByTestId('advice-cushion')).toHaveTextContent(
      "Entame le matelas, il se reconstituera quand tu rendras l'avance.",
    )
  })

  test('the part a source cannot cover is listed, with what is still missing', async () => {
    serve({
      ...OUTLOOK,
      advice: {
        ...OUTLOOK.advice!,
        amountCents: 100000,
        sourceAmountCents: 60000,
        alternatives: [{ accountId: 'pea', accountName: 'Épargne bis', balanceCents: 20000, amountCents: 20000, isCushion: true }],
        uncoveredCents: 20000,
        touchesCushion: true,
      },
    })
    renderPage()

    const advice = await screen.findByTestId('advice')
    expect(within(advice).getByText(/Épargne bis/)).toBeInTheDocument()
    expect(within(advice).getByText(/Il manque encore 200,00/)).toBeInTheDocument()
  })

  test('without a forecast overdraft, nothing is advised and no overdraft is announced', async () => {
    serve({
      ...OUTLOOK,
      overdraft: { isForecast: false, onDate: null, worstBalanceCents: 10000, limitCents: 0 },
      advice: null,
    })
    renderPage()

    expect(await screen.findByText(/Aucun découvert prévu/)).toBeInTheDocument()
    expect(screen.queryByTestId('overdraft-forecast')).not.toBeInTheDocument()
    expect(screen.queryByTestId('advice')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: "Préparer l'avance" })).not.toBeInTheDocument()
  })

  test('a salary that came announces no delay', async () => {
    serve({
      ...OUTLOOK,
      salary: { ...OUTLOOK.salary, isReceived: true, isLate: false, delayDays: 0, shortfallCents: 0, expectedOn: '2026-11-13' },
      overdraft: { isForecast: false, onDate: null, worstBalanceCents: 10000, limitCents: 0 },
      advice: null,
    })
    renderPage()

    expect(await screen.findByText('Salaire reçu')).toBeInTheDocument()
    expect(screen.queryByTestId('salary-delay')).not.toBeInTheDocument()
    expect(screen.queryByText(/Salaire en retard/)).not.toBeInTheDocument()
  })

  test('an advance already taken says what is left to give back', async () => {
    serve({ ...OUTLOOK, outstandingAdvancesCents: 150000 })
    renderPage()

    expect(await screen.findByText(/1\s500,00.*reste à rendre/)).toBeInTheDocument()
  })

  test('without an expected day, it asks for it and announces nothing', async () => {
    serve(
      {
        ...OUTLOOK,
        salary: { isConfigured: false, expectedOn: null, referenceCents: null, isReceived: false, isLate: false, delayDays: 0, shortfallCents: 0 },
        projection: [],
        balanceBeforeSalary: null,
        overdraft: { isForecast: false, onDate: null, worstBalanceCents: -12000, limitCents: 0 },
        advice: null,
      },
      { id: 's1', salaryExpectedDay: null as unknown as number, overdraftLimitCents: 0 },
    )
    renderPage()

    expect(await screen.findByText('Quel jour le salaire est-il attendu ?')).toBeInTheDocument()
    expect(screen.queryByTestId('salary-delay')).not.toBeInTheDocument()
    expect(screen.queryByTestId('overdraft-forecast')).not.toBeInTheDocument()
    expect(screen.queryByTestId('advice')).not.toBeInTheDocument()

    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Jour du mois'), '25')
    await user.click(screen.getByRole('button', { name: 'Enregistrer le jour' }))

    await waitFor(() => expect(patches()).toHaveLength(1))
    expect(JSON.parse(patches()[0][1].body)).toEqual({ salaryExpectedDay: 25 })
  })

  test('a day outside 1 to 31 is refused before anything is sent', async () => {
    serve({
      ...OUTLOOK,
      salary: { isConfigured: false, expectedOn: null, referenceCents: null, isReceived: false, isLate: false, delayDays: 0, shortfallCents: 0 },
      projection: [],
      advice: null,
    })
    renderPage()

    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Jour du mois'), '40')
    await user.click(screen.getByRole('button', { name: 'Enregistrer le jour' }))

    expect(await screen.findByText('Indiquez un jour du mois, de 1 à 31.')).toBeInTheDocument()
    expect(patches()).toHaveLength(0)
  })

  test('the overdraft limit is saved in cents', async () => {
    serve(OUTLOOK)
    renderPage()

    const user = userEvent.setup()
    const field = await screen.findByLabelText('Découvert autorisé (€)')
    await user.clear(field)
    await user.type(field, '500')
    // The reload after the save reads the new limit, as the API does.
    serve({ ...OUTLOOK, overdraft: { ...OUTLOOK.overdraft, limitCents: 50000 } }, { id: 's1', salaryExpectedDay: 13, overdraftLimitCents: 50000 })
    await user.click(screen.getByRole('button', { name: 'Enregistrer le découvert' }))

    await waitFor(() => expect(patches()).toHaveLength(1))
    expect(JSON.parse(patches()[0][1].body)).toEqual({ overdraftLimitCents: 50000 })
    expect(await screen.findByText('Découvert autorisé enregistré.')).toBeInTheDocument()
    expect(screen.getByLabelText('Découvert autorisé (€)')).toHaveValue(500)
  })

  test('a negative limit is refused in plain words, without a request', async () => {
    serve(OUTLOOK)
    renderPage()

    const user = userEvent.setup()
    const field = await screen.findByLabelText('Découvert autorisé (€)')
    await user.clear(field)
    await user.type(field, '-50')
    await user.click(screen.getByRole('button', { name: 'Enregistrer le découvert' }))

    expect(await screen.findByText(/ne peut pas être négatif/)).toBeInTheDocument()
    expect(patches()).toHaveLength(0)
  })

  test("the API's refusal of a limit is shown as it comes", async () => {
    serve(OUTLOOK)
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        return Promise.resolve(json({ error: 'overdraftLimitCents must be an integer of 0 or more cents' }, false))
      }

      return Promise.resolve(url === '/api/finance/treasury-outlook' ? json(OUTLOOK) : json({ id: 's1', salaryExpectedDay: 13, overdraftLimitCents: 0 }))
    })
    renderPage()

    const user = userEvent.setup()
    await screen.findByLabelText('Découvert autorisé (€)')
    await user.click(screen.getByRole('button', { name: 'Enregistrer le découvert' }))

    expect(await screen.findByText('overdraftLimitCents must be an integer of 0 or more cents')).toBeInTheDocument()
  })

  test('a failed read says so and offers to try again', async () => {
    serve(null)
    renderPage()

    expect(await screen.findByText('La vue trésorerie est indisponible pour le moment.')).toBeInTheDocument()

    serve(OUTLOOK)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('salary-delay')).toBeInTheDocument()
  })

  test('a transaction event reloads the outlook', async () => {
    serve(OUTLOOK)
    renderPage()
    await screen.findByTestId('salary-delay')
    const reads = () => fetchMock.mock.calls.filter(([url]) => url === '/api/finance/treasury-outlook').length
    const before = reads()

    serve({ ...OUTLOOK, salary: { ...OUTLOOK.salary, isLate: false, isReceived: true, delayDays: 0 }, advice: null })
    await emitMercure('/api/transactions/{id}', { '@id': '/api/transactions/01X' })

    await waitFor(() => expect(reads()).toBe(before + 1))
    await waitFor(() => expect(screen.queryByTestId('salary-delay')).not.toBeInTheDocument())
  })
})

import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { TreasuryPage } from './TreasuryPage'
import { emitMercure } from '../../test/mercure'
import type { SavingsAdvancesData } from './useSavingsAdvances'
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
    { date: '2026-10-13', balanceCents: -42000, cumulativeInflowCents: 0, cumulativeOutflowCents: 30000, movements: [{ id: 'm1', label: 'Loyer', amountCents: -30000, status: 'planned', accountId: 'a1', accountName: 'Courant', source: 'transaction' }] },
    { date: '2026-10-14', balanceCents: -42000, cumulativeInflowCents: 0, cumulativeOutflowCents: 30000, movements: [] },
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

const CUSHION = {
  state: 'building',
  targetMonths: 3,
  monthlyNetIncomeCents: 250000,
  targetCents: 750000,
  currentCents: 500000,
  peerClaimsCents: 0,
  peerClaimObjects: [],
  deficitCents: 250000,
  coveragePercent: 66,
  monthsCovered: 2,
  rechargeCapCents: 15000,
  rechargeTargetMonths: 6,
  monthlyRechargeCents: 15000,
  rechargeMonths: 17,
  isCappedByRechargeCap: true,
  blocksGreenScore: true,
  isConfigured: true,
  accounts: [{ id: 'a1', name: 'Livret A', bank: 'Boursorama', balanceCents: 500000, currency: 'EUR' }],
}

const ADVANCES: SavingsAdvancesData = {
  advances: [
    { id: 'adv-1', sourceAccountId: 'livret', sourceAccountName: 'Livret', amountCents: 150000, openedOn: '2026-10-11', repaidCents: 50000, outstandingCents: 100000, isClosed: false },
    { id: 'adv-2', sourceAccountId: 'pel', sourceAccountName: 'PEL', amountCents: 20000, openedOn: '2026-10-12', repaidCents: 0, outstandingCents: 20000, isClosed: false },
  ],
  bySource: [],
  totalOutstandingCents: 120000,
  salaryDelay: { isConfigured: true, salaryExpectedDay: 13, referenceCents: 495000, isLate: true, delayDays: 30, shortfallCents: 495000 },
  proposals: [
    { fromAccountId: 'courant', toAccountId: 'livret', amountCents: 100000, advanceId: 'adv-1', label: 'Remise avance Livret', currency: 'EUR', isPartial: false },
  ],
  proposalsTotalCents: 100000,
  unproposed: [{ advanceId: 'adv-2', sourceAccountId: 'pel', sourceAccountName: 'PEL', amountCents: 20000, reason: 'insufficient_balance' }],
  unproposedCents: 20000,
}

const NO_ADVANCES: SavingsAdvancesData = {
  ...ADVANCES,
  advances: [],
  totalOutstandingCents: 0,
  proposals: [],
  proposalsTotalCents: 0,
  unproposed: [],
  unproposedCents: 0,
}

const PEER_SUMMARY = {
  owedToMeCents: 40000,
  iOweCents: 28000,
  byContact: [
    { contactId: 'TOM', name: 'Tom', netCents: -8000, compensates: true },
    { contactId: 'JULIE', name: 'Julie', netCents: 20000, compensates: false },
  ],
}

const json = (body: unknown, ok = true) => ({ ok, json: async () => body }) as Response

const fetchMock = vi.fn()

interface Others {
  cushion?: object
  advances?: SavingsAdvancesData
  peerSummary?: object
  peerDebts?: unknown[]
}

function serve(outlook: TreasuryOutlook | null, settings = { id: 's1', salaryExpectedDay: 13, overdraftLimitCents: 0 }, others: Others = {}) {
  const { cushion = CUSHION, advances = ADVANCES, peerSummary = PEER_SUMMARY, peerDebts = [] } = others
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === 'PATCH') {
      return Promise.resolve(url === '/api/finance/cushion-config' ? json(cushion) : json({ success: true, ...settings }))
    }
    if (init?.method === 'POST') return Promise.resolve(json({}))
    if (url.startsWith('/api/finance/treasury-outlook')) {
      return Promise.resolve(outlook ? json(outlook) : json({ error: 'boom' }, false))
    }
    if (url === '/api/finance/treasury-settings') return Promise.resolve(json(settings))
    if (url === '/api/finance/cushion-status') return Promise.resolve(json(cushion))
    if (url === '/api/finance/savings-advances') return Promise.resolve(json(advances))
    if (url === '/api/finance/peer-debts/summary') return Promise.resolve(json(peerSummary))
    if (url === '/api/finance/peer-debts?status=open') return Promise.resolve(json({ peerDebts }))

    return Promise.resolve(json({}, false))
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
const posts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')

/** « Régler » opens the settings dialog; resolves with it once the forms are in. */
const openSettings = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByRole('button', { name: 'Régler' }))

  return screen.findByRole('dialog')
}

const SECTIONS = ['chart', 'cushion', 'advances', 'debts', 'movements'] as const

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
    expect(screen.getByTestId('overdraft-forecast')).not.toHaveTextContent('420,00')
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
          cumulativeInflowCents: 0,
          cumulativeOutflowCents: 127700,
          movements: [{ id: 'r1', label: 'Taxe foncière', amountCents: -97700, status: 'expected', accountId: 'a1', accountName: 'Courant', source: 'recurring' }],
        },
        {
          date: '2026-10-28',
          balanceCents: -157700,
          cumulativeInflowCents: 0,
          cumulativeOutflowCents: 145700,
          movements: [{ id: 'r2', label: 'Claude', amountCents: -18000, status: 'expected', accountId: 'a1', accountName: 'Courant', source: 'recurring' }],
        },
      ],
    })
    renderPage()

    const days = await screen.findAllByTestId('treasury-day')

    expect(days).toHaveLength(4)
    expect(days[2]).toHaveTextContent('Taxe foncière (récurrent) (\u2212977,00')
    expect(days[2]).toHaveTextContent('\u22121')
    expect(days[3]).toHaveTextContent('Claude (récurrent)')
    expect(days[0]).not.toHaveTextContent('récurrent')
  })

  test('the cumulative inflow and outflow curves sit under the balance, with no verdict figure', async () => {
    serve(OUTLOOK)
    renderPage()

    expect(await screen.findByTestId('treasury-flows-chart')).toBeInTheDocument()
    expect(screen.getByText('Entrées cumulées')).toBeInTheDocument()
    expect(screen.getByText('Sorties cumulées')).toBeInTheDocument()
  })

  test('the horizon choice asks the API for that many months and keeps the page', async () => {
    serve(OUTLOOK)
    renderPage()
    await screen.findByTestId('salary-delay')

    const user = userEvent.setup()
    expect(screen.getByRole('button', { name: 'Fin du mois suivant' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: '1 an' }))

    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([url]) => url === '/api/finance/treasury-outlook?months=12')).toBe(true),
    )
    expect(screen.getByRole('button', { name: '1 an' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('salary-delay')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Fin du mois suivant' }))
    await waitFor(() => {
      const reads = fetchMock.mock.calls.filter(([url]) => url === '/api/finance/treasury-outlook')
      expect(reads.length).toBeGreaterThan(1)
    })
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

    expect(await screen.findByTestId('advice-cushion')).toHaveTextContent('Entame le matelas')
    expect(screen.queryByText(/il se reconstituera/)).not.toBeInTheDocument()
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

    expect(await screen.findByTestId('treasury-chart')).toBeInTheDocument()
    expect(screen.queryByText(/Aucun découvert/)).not.toBeInTheDocument()
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

  test('without an expected day, it announces nothing and the day is set in « Régler »', async () => {
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

    await screen.findByTestId('treasury-section-movements')
    expect(screen.queryByText('Quel jour le salaire est-il attendu ?')).not.toBeInTheDocument()
    expect(screen.queryByText(/Sans ce jour, Maggie ne sait pas/)).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Jour du mois')).not.toBeInTheDocument()
    expect(screen.queryByTestId('salary-delay')).not.toBeInTheDocument()
    expect(screen.queryByTestId('overdraft-forecast')).not.toBeInTheDocument()
    expect(screen.queryByTestId('advice')).not.toBeInTheDocument()

    const user = userEvent.setup()
    const dialog = await openSettings(user)
    await user.type(within(dialog).getByLabelText('Jour du mois'), '25')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer le jour' }))

    await waitFor(() => expect(patches()).toHaveLength(1))
    expect(JSON.parse(patches()[0][1].body)).toEqual({ salaryExpectedDay: 25 })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
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
    const dialog = await openSettings(user)
    await user.type(within(dialog).getByLabelText('Jour du mois'), '40')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer le jour' }))

    expect(await within(dialog).findByText('Indiquez un jour du mois, de 1 à 31.')).toBeInTheDocument()
    expect(patches()).toHaveLength(0)
  })

  test('the overdraft limit is saved in cents, from « Régler »', async () => {
    serve(OUTLOOK)
    renderPage()

    const user = userEvent.setup()
    const dialog = await openSettings(user)
    const field = within(dialog).getByLabelText('Découvert autorisé (€)')
    await user.clear(field)
    await user.type(field, '500')
    // The reload after the save reads the new limit, as the API does.
    serve({ ...OUTLOOK, overdraft: { ...OUTLOOK.overdraft, limitCents: 50000 } }, { id: 's1', salaryExpectedDay: 13, overdraftLimitCents: 50000 })
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer le découvert' }))

    await waitFor(() => expect(patches()).toHaveLength(1))
    expect(JSON.parse(patches()[0][1].body)).toEqual({ overdraftLimitCents: 50000 })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const reopened = await openSettings(user)
    expect(within(reopened).getByLabelText('Découvert autorisé (€)')).toHaveValue(500)
  })

  test('a negative limit is refused in plain words, without a request', async () => {
    serve(OUTLOOK)
    renderPage()

    const user = userEvent.setup()
    const dialog = await openSettings(user)
    const field = within(dialog).getByLabelText('Découvert autorisé (€)')
    await user.clear(field)
    await user.type(field, '-50')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer le découvert' }))

    expect(await within(dialog).findByText(/ne peut pas être négatif/)).toBeInTheDocument()
    expect(patches()).toHaveLength(0)
  })

  test("the API's refusal of a limit is shown as it comes, and the dialog stays open", async () => {
    serve(OUTLOOK)
    const served = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        return Promise.resolve(json({ error: 'overdraftLimitCents must be an integer of 0 or more cents' }, false))
      }

      return served(url, init)
    })
    renderPage()

    const user = userEvent.setup()
    const dialog = await openSettings(user)
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer le découvert' }))

    expect(await within(dialog).findByText('overdraftLimitCents must be an integer of 0 or more cents')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
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
    const reads = () => fetchMock.mock.calls.filter(([url]) => String(url).startsWith('/api/finance/treasury-outlook')).length
    const before = reads()

    serve({ ...OUTLOOK, salary: { ...OUTLOOK.salary, isLate: false, isReceived: true, delayDays: 0 }, advice: null })
    await emitMercure('/api/transactions/{id}', { '@id': '/api/transactions/01X' })

    await waitFor(() => expect(reads()).toBe(before + 1))
    await waitFor(() => expect(screen.queryByTestId('salary-delay')).not.toBeInTheDocument())
  })

  test('the page has no tabs: one page, the sections one under the other', async () => {
    serve(OUTLOOK)
    renderPage()
    await screen.findByTestId('treasury-section-movements')
    await screen.findByTestId('treasury-section-debts')

    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.queryByRole('tablist')).toBeNull()
  })

  test('the sections come in order: curve, cushion, advances, debts and claims, movements', async () => {
    serve(OUTLOOK, undefined, { peerDebts: [] })
    renderPage()

    const sections = await Promise.all(SECTIONS.map((name) => screen.findByTestId(`treasury-section-${name}`)))
    await screen.findByTestId('tile-owed-to-me')

    sections.slice(1).forEach((section, index) => {
      expect(sections[index].compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    })
    expect(within(sections[0]).getByTestId('treasury-chart')).toBeInTheDocument()
    expect(within(sections[1]).getByText('Matelas de sécurité')).toBeInTheDocument()
    expect(within(sections[2]).getByText("Avances d'épargne")).toBeInTheDocument()
    expect(within(sections[3]).getByText('Créances et dettes')).toBeInTheDocument()
    expect(within(sections[4]).getAllByTestId('treasury-day')).toHaveLength(2)
  })

  test('the settings forms are not on the page: they sit behind « Régler »', async () => {
    serve(OUTLOOK)
    renderPage()
    await screen.findByTestId('treasury-section-movements')

    expect(screen.queryByLabelText('Découvert autorisé (€)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Jour du mois')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Cible (mois de revenu)')).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('« Régler » opens one dialog with the cushion, the expected salary day and the overdraft', async () => {
    serve(OUTLOOK)
    renderPage()

    const user = userEvent.setup()
    const dialog = await openSettings(user)

    expect(within(dialog).getByLabelText('Cible (mois de revenu)')).toHaveValue(3)
    expect(within(dialog).getByLabelText('Revenu net mensuel (€)')).toHaveValue(2500)
    expect(within(dialog).getByLabelText('Plafond de recharge (€/mois)')).toHaveValue(150)
    expect(within(dialog).getByLabelText('Horizon de recharge (mois)')).toHaveValue(6)
    expect(within(dialog).getByLabelText('Jour du mois')).toHaveValue(13)
    expect(within(dialog).getByLabelText('Découvert autorisé (€)')).toHaveValue(0)
    expect(within(dialog).queryByText(/3 mois est le repère courant/)).not.toBeInTheDocument()
    expect(within(dialog).queryByText(/Sert uniquement à calculer la cible/)).not.toBeInTheDocument()
    expect(within(dialog).queryByText(/tout solde négatif est un découvert/)).not.toBeInTheDocument()
    expect(within(dialog).queryByText(/un mois plus court prend son dernier jour/)).not.toBeInTheDocument()
  })

  test('saving the cushion from the dialog sends the cushion config', async () => {
    serve(OUTLOOK)
    renderPage()

    const user = userEvent.setup()
    const dialog = await openSettings(user)
    const target = within(dialog).getByLabelText('Cible (mois de revenu)')
    await user.clear(target)
    await user.type(target, '4')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer le matelas' }))

    await waitFor(() => expect(patches()).toHaveLength(1))
    expect(patches()[0][0]).toBe('/api/finance/cushion-config')
    expect(JSON.parse(patches()[0][1].body)).toEqual({
      targetMonths: 4,
      monthlyNetIncomeCents: 250000,
      rechargeCapCents: 15000,
      rechargeTargetMonths: 6,
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  test('« Remettre » gives the advance back: the two legs of the counter-transfer are recorded', async () => {
    serve(OUTLOOK)
    renderPage()

    const [open] = await screen.findAllByTestId('savings-advance')
    expect(open).toHaveTextContent('Livret')
    const user = userEvent.setup()
    await user.click(within(open).getByRole('button', { name: 'Remettre' }))

    await waitFor(() => expect(posts()).toHaveLength(2))
    expect(posts().map(([url]) => url)).toEqual(['/api/transactions', '/api/transactions'])
    const bodies = posts().map(([, init]) => JSON.parse(init.body))
    expect(bodies.map((body) => body.amountCents)).toEqual([-100000, 100000])
    expect(bodies.map((body) => body.account)).toEqual(['/api/accounts/courant', '/api/accounts/livret'])
  })

  test('an advance without a proposal has no « Remettre » button', async () => {
    serve(OUTLOOK)
    renderPage()

    const rows = await screen.findAllByTestId('savings-advance')

    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByRole('button', { name: 'Remettre' })).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button', { name: 'Remettre' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Remettre' })).toHaveLength(1)
    expect(screen.queryByText('Contre-virements proposés')).not.toBeInTheDocument()
  })

  test('the explanations are gone: no intro paragraph, no teaching alert, no help text', async () => {
    serve(
      { ...OUTLOOK, advice: { ...OUTLOOK.advice!, isCushion: true, touchesCushion: true } },
      undefined,
      { peerDebts: [] },
    )
    renderPage()
    await screen.findByTestId('salary-delay')
    await screen.findByTestId('tile-owed-to-me')

    for (const text of [
      /Sans ce jour, Maggie ne sait pas/,
      /Quel jour le salaire est-il attendu/,
      /Tant que le matelas n'est pas complet/,
      /Aucun compte n'est marqué/,
      /Faites le virement dans votre banque/,
      /Aucun jour à projeter/,
      /Le jour du mois où le salaire est attendu/,
      /La cible est un nombre de mois de revenu net/,
      /le plafond mensuel allonge la durée/,
      /Aucune avance à prévoir/,
      /Le salaire est arrivé/,
      /avancés sur ton matelas/,
      /hors revenus et épargne/,
    ]) {
      expect(screen.queryByText(text)).toBeNull()
    }
  })

  test('each section says plainly when it is empty', async () => {
    serve(
      { ...OUTLOOK, projection: [], advice: null, overdraft: { isForecast: false, onDate: null, worstBalanceCents: 0, limitCents: 0 } },
      undefined,
      {
        cushion: { ...CUSHION, accounts: [] },
        advances: NO_ADVANCES,
        peerSummary: { owedToMeCents: 0, iOweCents: 0, byContact: [] },
        peerDebts: [],
      },
    )
    renderPage()

    expect(await screen.findByText('Aucun compte matelas')).toBeInTheDocument()
    expect(screen.getByText('Aucune avance en cours.')).toBeInTheDocument()
    expect(screen.getByText('Rien en cours')).toBeInTheDocument()
    expect(screen.getByText('Aucun mouvement prévu')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remettre' })).not.toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Solde projeté jour par jour' })).not.toBeInTheDocument()
  })

  test('the debts section lists each person, and a person opens on their lines', async () => {
    serve(OUTLOOK)
    renderPage()

    const people = await screen.findAllByTestId('peer-debt-person')
    expect(people).toHaveLength(2)
    expect(screen.getByTestId('tile-owed-to-me')).toHaveTextContent('On te doit')
    expect(screen.getByTestId('tile-i-owe')).toHaveTextContent('Tu dois')
    expect(people[0]).toHaveTextContent('Tom')
  })

  test('a failed outlook does not hide the other sections', async () => {
    serve(null)
    renderPage()

    expect(await screen.findByText('La vue trésorerie est indisponible pour le moment.')).toBeInTheDocument()
    expect(await screen.findByTestId('treasury-section-cushion')).toBeInTheDocument()
    expect(await screen.findByTestId('treasury-section-advances')).toBeInTheDocument()
    expect(await screen.findByTestId('treasury-section-debts')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Régler' })).toBeInTheDocument()
  })
})

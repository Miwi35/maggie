import { beforeEach, describe, expect, test, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { renderWithTheme } from '../../test/renderWithTheme'
import { TimelinePanel } from './TimelinePanel'
import { COMPARE_REFUSED, COMPARE_UNKNOWN } from './useFinanceTimeline'
import type { Timeline, TimelineEvent, TimelineLine } from './timelineApi'

const CATEGORIES = [
  { key: 'projet', label: 'Projets', color: '#B8DE6F' },
  { key: 'dette', label: 'Dette', color: '#8FB8FF' },
  { key: 'investissement', label: 'Investissement', color: '#F2C46D' },
  { key: 'indépendance', label: 'Indépendance', color: '#7FD1AE' },
  { key: 'fiscal', label: 'Fiscal', color: '#C7B5FF' },
  { key: 'matelas', label: 'Matelas', color: '#ABA6B8' },
]

const ASSUMPTIONS = 'Hypothèses : matelas intact, revenus stables, sans vacance locative ni imprévu'

const event = (id: string, month: string, category: string, label: string, fields: Partial<TimelineEvent> = {}): TimelineEvent => ({
  id,
  month,
  type: 'project_purchase',
  category,
  label,
  sublabel: null,
  amount_eur: null,
  monthly_impact_eur: null,
  n_impact_months: null,
  detail: {},
  ...fields,
})

const TRIP = event('project_purchase:TRIP', '2027-02', 'projet', 'Japon', { sublabel: 'Achat', amount_eur: 3000, n_impact_months: 2 })
const LOAN_END = event('debt_end:loan:L1', '2027-08', 'dette', 'Fin du prêt : Voiture', { type: 'debt_end' })
const FREE = event('independence_reached', '2041-12', 'indépendance', 'Indépendance financière', {
  type: 'independence_reached',
  sublabel: 'Les rentes couvrent le train de vie',
})

const line = (fields: Partial<TimelineLine> = {}): TimelineLine => ({
  scenarioId: null,
  name: 'Planning sans scénario',
  nDate: '2041-12',
  events: [TRIP, LOAN_END, FREE],
  ...fields,
})

const timeline = (fields: Partial<Timeline> = {}): Timeline => ({
  startMonth: '2026-10',
  horizonMonths: 240,
  assumptions: ASSUMPTIONS,
  categories: CATEGORIES,
  current: line(),
  compared: null,
  difference: null,
  ...fields,
})

const SCENARIOS = {
  scenarios: [
    { id: 'MINE', name: 'Mon plan', kind: 'user', current: false },
    { id: 'auto-rapid', name: 'Rapide', kind: 'auto', current: false },
    { id: 'auto-balanced', name: 'Équilibré', kind: 'auto', current: false },
  ],
}

const compared = timeline({
  compared: line({
    scenarioId: 'auto-rapid',
    name: 'Rapide',
    nDate: '2040-03',
    events: [event('independence_reached', '2040-03', 'indépendance', 'Indépendance financière', { type: 'independence_reached' })],
  }),
  difference: { months: -9, sentence: 'N 9 mois plus tôt avec « Rapide » qu\'avec « Planning sans scénario »' },
})

type Answer = { status: number; body: unknown } | Error

/** Answers each path the panel reads; `timeline?compare=…` has its own answer. */
const stubApi = (answers: Record<string, Answer>) => {
  const fetchMock = vi.fn(async (url: string) => {
    const answer = answers[url] ?? { status: 404, body: {} }
    if (answer instanceof Error) throw answer

    return { ok: answer.status < 400, status: answer.status, json: () => Promise.resolve(answer.body) } as Response
  })
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const ok = (body: unknown): Answer => ({ status: 200, body })

const renderPanel = () =>
  renderWithTheme(
    <AdminContext dataProvider={testDataProvider()}>
      <TimelinePanel />
    </AdminContext>,
  )

const current = () => screen.getByTestId('timeline-row-current')

describe('TimelinePanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('shows a loading bar until the frise arrives', async () => {
    stubApi({ '/api/finance/timeline': ok(timeline()), '/api/finance/scenarios': ok(SCENARIOS) })
    renderPanel()

    expect(screen.getByRole('progressbar', { name: 'Chargement de la frise' })).toBeInTheDocument()
    expect(await screen.findByTestId('timeline-row-current')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  test('draws the current scenario with its name, its date N and each event on its month', async () => {
    const fetchMock = stubApi({ '/api/finance/timeline': ok(timeline()), '/api/finance/scenarios': ok(SCENARIOS) })
    renderPanel()

    await screen.findByTestId('timeline-row-current')

    expect(screen.getByTestId('timeline-name-current')).toHaveTextContent('Planning sans scénario')
    expect(screen.getByTestId('timeline-n-date-current')).toHaveTextContent('Date N projetée · décembre 2041')
    expect(screen.queryByTestId('timeline-row-compared')).not.toBeInTheDocument()

    const trip = screen.getByTestId('timeline-event-project_purchase:TRIP')
    expect(trip).toHaveAttribute('data-month', '2027-02')
    expect(trip).toHaveTextContent('février 2027')
    expect(trip).toHaveTextContent('Japon · Achat · 3 000 € · N 2 mois plus tard')
    expect(screen.getByTestId('timeline-event-debt_end:loan:L1')).toHaveAttribute('data-month', '2027-08')

    expect(fetchMock).toHaveBeenCalledWith('/api/finance/timeline', {
      headers: { Accept: 'application/json', Authorization: 'Bearer test-jwt-token' },
    })
  })

  test('the Indépendance event is findable by its text and its test id, on the N date', async () => {
    stubApi({ '/api/finance/timeline': ok(timeline()), '/api/finance/scenarios': ok(SCENARIOS) })
    renderPanel()

    const reached = await screen.findByTestId('timeline-event-independence_reached')

    expect(reached).toHaveAttribute('data-month', '2041-12')
    expect(within(reached).getByText('Indépendance financière')).toBeInTheDocument()
    expect(within(reached).getByText('décembre 2041')).toBeInTheDocument()
    expect(within(current()).getByRole('list', { name: 'Événements de « Planning sans scénario »' })).toContainElement(reached)
  })

  test('colours markers and filters with the categories the API serves', async () => {
    stubApi({ '/api/finance/timeline': ok(timeline()), '/api/finance/scenarios': ok(SCENARIOS) })
    renderPanel()

    await screen.findByTestId('timeline-row-current')

    expect(screen.getByTestId('timeline-marker-project_purchase:TRIP')).toHaveStyle({ backgroundColor: '#B8DE6F' })
    expect(screen.getByTestId('timeline-marker-debt_end:loan:L1')).toHaveStyle({ backgroundColor: '#8FB8FF' })
    expect(screen.getByTestId('timeline-marker-independence_reached')).toHaveStyle({ backgroundColor: '#7FD1AE' })
    // Every served category is a chip, the fiscal one included though no event uses it yet.
    for (const category of CATEGORIES) {
      expect(screen.getByRole('button', { name: category.label })).toHaveAttribute('aria-pressed', 'true')
    }
  })

  test('states the hypotheses verbatim', async () => {
    stubApi({ '/api/finance/timeline': ok(timeline()), '/api/finance/scenarios': ok(SCENARIOS) })
    renderPanel()

    expect(await screen.findByTestId('timeline-assumptions')).toHaveTextContent(`${ASSUMPTIONS}`)
    expect(screen.getByTestId('timeline-assumptions').textContent).toBe(ASSUMPTIONS)
  })

  test('says plainly that N is not reached, and shows no independence event', async () => {
    stubApi({
      '/api/finance/timeline': ok(timeline({ current: line({ nDate: null, events: [TRIP] }) })),
      '/api/finance/scenarios': ok(SCENARIOS),
    })
    renderPanel()

    expect(await screen.findByTestId('timeline-n-date-current')).toHaveTextContent("N non atteinte d'ici 20 ans")
    expect(screen.queryByTestId('timeline-event-independence_reached')).not.toBeInTheDocument()
  })

  test('hides and shows the events of a category, in both rows', async () => {
    const user = userEvent.setup()
    stubApi({
      '/api/finance/timeline': ok(timeline()),
      '/api/finance/timeline?compare=auto-rapid': ok(compared),
      '/api/finance/scenarios': ok(SCENARIOS),
    })
    renderPanel()
    await screen.findByTestId('timeline-row-current')
    await user.selectOptions(screen.getByLabelText('Scénario comparé'), 'auto-rapid')
    await screen.findByTestId('timeline-row-compared')

    const chip = screen.getByRole('button', { name: 'Indépendance' })
    await user.click(chip)

    expect(chip).toHaveAttribute('aria-pressed', 'false')
    expect(screen.queryByTestId('timeline-event-independence_reached')).not.toBeInTheDocument()
    expect(screen.queryByTestId('timeline-compared-event-independence_reached')).not.toBeInTheDocument()
    expect(screen.queryByTestId('timeline-marker-independence_reached')).not.toBeInTheDocument()
    expect(screen.getByTestId('timeline-event-project_purchase:TRIP')).toBeInTheDocument()
    expect(screen.getByTestId('timeline-empty-compared')).toBeInTheDocument()

    await user.click(chip)

    expect(chip).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('timeline-event-independence_reached')).toBeInTheDocument()
    expect(screen.getByTestId('timeline-compared-event-independence_reached')).toBeInTheDocument()
  })

  test('offers every scenario but the current one, the automatic ones included', async () => {
    stubApi({
      '/api/finance/timeline': ok(timeline({ current: line({ scenarioId: 'MINE', name: 'Mon plan' }) })),
      '/api/finance/scenarios': ok(SCENARIOS),
    })
    renderPanel()

    await screen.findByTestId('timeline-row-current')
    const select = screen.getByLabelText('Scénario comparé')
    await waitFor(() => expect(within(select).getAllByRole('option').length).toBeGreaterThan(1))

    expect(within(select).getAllByRole('option').map((option) => option.textContent)).toEqual([
      'Aucune comparaison',
      'Rapide',
      'Équilibré',
    ])
    expect(select).toHaveValue('')
  })

  test('choosing a scenario reads the frise with ?compare= and shows both rows and the sentence', async () => {
    const user = userEvent.setup()
    const fetchMock = stubApi({
      '/api/finance/timeline': ok(timeline()),
      '/api/finance/timeline?compare=auto-rapid': ok(compared),
      '/api/finance/scenarios': ok(SCENARIOS),
    })
    renderPanel()
    await screen.findByTestId('timeline-row-current')
    await waitFor(() => expect(within(screen.getByLabelText('Scénario comparé')).getAllByRole('option').length).toBe(4))

    await user.selectOptions(screen.getByLabelText('Scénario comparé'), 'auto-rapid')

    const row = await screen.findByTestId('timeline-row-compared')
    expect(fetchMock).toHaveBeenCalledWith('/api/finance/timeline?compare=auto-rapid', expect.anything())
    expect(within(row).getByTestId('timeline-name-compared')).toHaveTextContent('Rapide')
    expect(within(row).getByTestId('timeline-n-date-compared')).toHaveTextContent('Date N projetée · mars 2040')
    expect(within(row).getByTestId('timeline-compared-event-independence_reached')).toHaveAttribute('data-month', '2040-03')
    expect(screen.getByTestId('timeline-difference').textContent).toBe(compared.difference?.sentence)
    // The current row is still above, untouched.
    expect(screen.getByTestId('timeline-event-independence_reached')).toHaveAttribute('data-month', '2041-12')

    await user.selectOptions(screen.getByLabelText('Scénario comparé'), '')

    await waitFor(() => expect(screen.queryByTestId('timeline-row-compared')).not.toBeInTheDocument())
    expect(screen.queryByTestId('timeline-difference')).not.toBeInTheDocument()
  })

  test.each([
    [400, COMPARE_REFUSED],
    [404, COMPARE_UNKNOWN],
  ])('a %i on the comparison drops it, says why and keeps the frise', async (status, message) => {
    const user = userEvent.setup()
    stubApi({
      '/api/finance/timeline': ok(timeline()),
      '/api/finance/timeline?compare=auto-rapid': { status, body: { error: 'no' } },
      '/api/finance/scenarios': ok(SCENARIOS),
    })
    renderPanel()
    await screen.findByTestId('timeline-row-current')
    await waitFor(() => expect(within(screen.getByLabelText('Scénario comparé')).getAllByRole('option').length).toBe(4))

    await user.selectOptions(screen.getByLabelText('Scénario comparé'), 'auto-rapid')

    expect(await screen.findByTestId('timeline-notice')).toHaveTextContent(message)
    await waitFor(() => expect(screen.getByLabelText('Scénario comparé')).toHaveValue(''))
    expect(screen.queryByTestId('timeline-row-compared')).not.toBeInTheDocument()
    expect(screen.getByTestId('timeline-row-current')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('shows an error with a retry when the frise cannot be read, then the frise', async () => {
    const user = userEvent.setup()
    const fetchMock = stubApi({ '/api/finance/timeline': { status: 500, body: {} }, '/api/finance/scenarios': ok(SCENARIOS) })
    renderPanel()

    expect(await screen.findByRole('alert')).toHaveTextContent("La frise n'a pas pu être chargée.")
    expect(screen.queryByTestId('timeline-row-current')).not.toBeInTheDocument()

    fetchMock.mockImplementation(async (url: string) => ({
      ok: true,
      status: 200,
      json: () => Promise.resolve(url === '/api/finance/timeline' ? timeline() : SCENARIOS),
    }) as Response)
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('timeline-row-current')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('a network failure is the same error', async () => {
    stubApi({ '/api/finance/timeline': new Error('network down'), '/api/finance/scenarios': ok(SCENARIOS) })
    renderPanel()

    expect(await screen.findByRole('alert')).toHaveTextContent("La frise n'a pas pu être chargée.")
  })

  test('keeps the frise and disables the selector when the scenarios cannot be listed', async () => {
    stubApi({ '/api/finance/timeline': ok(timeline()), '/api/finance/scenarios': { status: 500, body: {} } })
    renderPanel()

    await screen.findByTestId('timeline-row-current')

    expect(await screen.findByText("Les scénarios n'ont pas pu être chargés.")).toBeInTheDocument()
    expect(screen.getByLabelText('Scénario comparé')).toBeDisabled()
  })

  test('never ranks the scenarios: only the API sentence is said', async () => {
    const user = userEvent.setup()
    stubApi({
      '/api/finance/timeline': ok(timeline()),
      '/api/finance/timeline?compare=auto-rapid': ok(compared),
      '/api/finance/scenarios': ok(SCENARIOS),
    })
    renderPanel()
    await screen.findByTestId('timeline-row-current')
    await waitFor(() => expect(within(screen.getByLabelText('Scénario comparé')).getAllByRole('option').length).toBe(4))
    await user.selectOptions(screen.getByLabelText('Scénario comparé'), 'auto-rapid')
    await screen.findByTestId('timeline-row-compared')

    expect(screen.getByTestId('timeline-panel').textContent).not.toMatch(/meilleur|recommand|best/i)
  })
})

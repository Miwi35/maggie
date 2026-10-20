import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { ReviewActivityPage } from './ReviewActivityPage'
import { emptyProjection, upcomingFixture } from './upcomingFixture'
import type { UpcomingProjection } from './useUpcomingProjection'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)
// The steps reused as they are have their own tests; here they only have to be there.
vi.mock('./MonthBalanceTab', () => ({ MonthBalanceTab: () => <div data-testid="month-balance-step">Bilan reçu</div> }))
vi.mock('./EnvelopeAdjustments', () => ({
  EnvelopeAdjustments: ({ month }: { month: string }) => <div data-testid="envelopes-step">Enveloppes de {month}</div>,
}))
vi.mock('./PeriodBalanceStep', () => ({
  PeriodBalanceStep: ({ month }: { month: string }) => <div data-testid="period-balance-step">Bilan de la période de {month}</div>,
}))
vi.mock('./PeriodPlanStep', () => ({
  PeriodPlanStep: ({ period }: { period: { key: string } }) => <div data-testid="period-plan-step">Plan de {period.key}</div>,
}))
vi.mock('../../components/chat/ChatWidget', () => ({
  ChatWidget: ({ open }: { open: boolean }) => (open ? <div data-testid="chat-panel">Conversation</div> : null),
}))

const MONTH = (() => {
  const now = new Date()

  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
})()

const item = (id: string, label: string, extra: Record<string, unknown> = {}) => ({
  recognised: false,
  id,
  label,
  counterpartyName: null,
  amountCents: -1250,
  currency: 'EUR',
  bookedAt: `${MONTH}-03`,
  accountId: 'A1',
  accountName: 'Compte courant',
  judgeable: true,
  retrospect: 'unrated',
  suggestion: null,
  categoryId: null,
  categoryPath: null,
  qualifiedBy: null,
  ...extra,
})

const SUGGESTED = item('T1', 'CARREFOUR MARKET 12', {
  suggestion: { categoryId: 'COURSES', categoryPath: 'Alimentation › Courses', source: 'rule', reason: 'Règle « CARREFOUR »' },
})
const PLAIN = item('T2', 'BOULANGERIE DU COIN')
const RECOGNISED = item('T3', 'NETFLIX', {
  recognised: true,
  categoryId: 'ABOS',
  categoryPath: 'Loisirs › Abonnements',
  qualifiedBy: 'rule',
})

interface Server {
  step: string
  handled: string[]
  items: Record<string, unknown>[]
  envelopes: unknown[]
  upcoming: UpcomingProjection
  /** What the API says of the month: a plain one when absent. */
  closing?: { level: string; period: Record<string, unknown>; nextPeriod: Record<string, unknown> }
  progressFails?: boolean
  queueFails?: boolean
  saveFails?: boolean
  writesFail?: boolean
}

let server: Server
const fetchMock = vi.fn()

const answer = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body }) as Promise<Response>

const progressBody = () => ({
  month: MONTH,
  step: server.step,
  handledTransactionIds: server.handled,
  updatedAt: null,
  ...(server.closing ?? { level: 'month', period: null, nextPeriod: null }),
})

const QUARTER_END = {
  level: 'quarter',
  period: { key: '2027-Q1', level: 'quarter', from: '2027-01', to: '2027-03', months: 3 },
  nextPeriod: { key: '2027-Q2', level: 'quarter', from: '2027-04', to: '2027-06', months: 3 },
}

const handle = (url: string, init?: RequestInit): Promise<Response> => {
  const method = init?.method ?? 'GET'
  if (url.startsWith('/api/finance/review/progress')) {
    if (method === 'PUT') {
      if (server.saveFails) return answer(false, { error: 'Progression refusée' })
      const body = JSON.parse(init?.body as string) as { step?: string; handledTransactionIds?: string[] }
      if (body.step) server.step = body.step
      server.handled = [...server.handled, ...(body.handledTransactionIds ?? []).filter((id) => !server.handled.includes(id))]

      return answer(true, progressBody())
    }

    return server.progressFails ? answer(false, { error: 'Revue indisponible' }) : answer(true, progressBody())
  }
  if (url.startsWith('/api/finance/review/queue')) {
    if (server.queueFails) return answer(false, { error: 'File indisponible' })
    return answer(true, { month: MONTH, unrecognisedCount: 0, unrecognisedCents: 0, recognisedCount: 0, items: server.items })
  }
  if (url.startsWith('/api/finance/envelope-adjustments')) {
    return answer(true, { month: MONTH, targetMonth: MONTH, suggestionCount: 0, envelopes: server.envelopes })
  }
  if (url.startsWith('/api/finance/upcoming')) {
    return answer(true, server.upcoming)
  }
  if (server.writesFail) return answer(false, { error: 'Transaction introuvable.' })

  return answer(true, { success: true })
}

const writes = (method: string, prefix = '') =>
  fetchMock.mock.calls
    .filter(([url, init]) => (init as RequestInit | undefined)?.method === method && (url as string).startsWith(prefix))
    .map(([url, init]) => ({ url: url as string, body: JSON.parse((init as RequestInit).body as string) }))

const progressWrites = () => writes('PUT', '/api/finance/review/progress').map((write) => write.body)

const dataProvider = testDataProvider({
  getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
})

const Where = () => <span data-testid="where">{useLocation().pathname}</span>

const renderActivity = (from: string | null = '/calendar') =>
  render(
    <MemoryRouter initialEntries={[{ pathname: '/finance/review/activity', state: from ? { from } : null }]}>
      <AdminContext dataProvider={dataProvider}>
        <Routes>
          <Route path="/finance/review/activity" element={<ReviewActivityPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

const card = async () => within(await screen.findByRole('region', { name: 'Transaction de la revue' }))
const stepOf = () => screen.getByTestId('review-activity').getAttribute('data-step')

describe('ReviewActivityPage', () => {
  beforeEach(() => {
    server = { step: 'review', handled: [], items: [SUGGESTED, PLAIN, RECOGNISED], envelopes: [], upcoming: upcomingFixture() }
    fetchMock.mockImplementation(handle)
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows the first line, its counter, the five segments and what comes next', async () => {
    renderActivity()

    const first = await card()
    expect(first.getByText('CARREFOUR MARKET 12')).toBeInTheDocument()
    expect(first.getByText('1 / 3')).toBeInTheDocument()
    expect(first.getByText('Alimentation › Courses')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Revue des transactions' })).toBeInTheDocument()
    expect(screen.getByText('Ensuite… Bilan')).toBeInTheDocument()
    expect(screen.getByTestId('review-segment-review')).toHaveAttribute('data-state', 'current')
    expect(screen.getByTestId('review-segment-projects')).toHaveAttribute('data-state', 'todo')
    expect(screen.getByRole('button', { name: 'Valider →' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '← Précédent' })).toBeDisabled()
    expect(fetchMock).toHaveBeenCalledWith(`/api/finance/review/queue?month=${MONTH}`, expect.anything())
  })

  test('resumes on the stored step at the first line not handled yet', async () => {
    server.handled = ['T1']
    renderActivity()

    const resumed = await card()
    expect(resumed.getByText('BOULANGERIE DU COIN')).toBeInTheDocument()
    expect(resumed.getByText('2 / 3')).toBeInTheDocument()
    expect(progressWrites()).toEqual([])
  })

  test('Entrée accepts the suggestion, records the line as handled and shows the next one', async () => {
    renderActivity()
    await card()

    await userEvent.keyboard('{Enter}')

    expect((await card()).getByText('BOULANGERIE DU COIN')).toBeInTheDocument()
    expect(writes('POST', '/api/finance/transactions/T1/sort')[0].body).toEqual({ categoryId: 'COURSES' })
    expect(progressWrites()).toEqual([{ month: MONTH, handledTransactionIds: ['T1'] }])
  })

  test('→ passes a line with nothing to validate, ← goes back to it, without writing it again', async () => {
    server.handled = ['T1']
    renderActivity()
    expect(await screen.findByRole('button', { name: 'Passer →' })).toBeInTheDocument()

    await userEvent.keyboard('{ArrowRight}')
    expect((await card()).getByText('NETFLIX')).toBeInTheDocument()
    expect(progressWrites()).toEqual([{ month: MONTH, handledTransactionIds: ['T2'] }])
    expect(writes('POST')).toEqual([])

    await userEvent.keyboard('{ArrowLeft}')
    expect((await card()).getByText('BOULANGERIE DU COIN')).toBeInTheDocument()
    expect(progressWrites()).toHaveLength(1)
  })

  test('« Valider » checks a recognised line before leaving it', async () => {
    server.handled = ['T1', 'T2']
    renderActivity()
    const recognised = await card()
    expect(recognised.getByText('Loisirs › Abonnements')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Valider →' }))

    await waitFor(() => expect(writes('POST', '/api/finance/review/controls')).toHaveLength(1))
    expect(writes('POST', '/api/finance/review/controls')[0].body).toEqual({ transactionIds: ['T3'] })
    await waitFor(() => expect(stepOf()).toBe('balance'))
    expect(progressWrites()).toEqual([
      { month: MONTH, handledTransactionIds: ['T3'] },
      { month: MONTH, step: 'balance' },
    ])
  })

  test('a refused check keeps the line and says why', async () => {
    server.handled = ['T1', 'T2']
    server.writesFail = true
    renderActivity()
    await card()

    await userEvent.click(screen.getByRole('button', { name: 'Valider →' }))

    expect(await screen.findByText('Transaction introuvable.')).toBeInTheDocument()
    expect((await card()).getByText('NETFLIX')).toBeInTheDocument()
    expect(progressWrites()).toEqual([])
  })

  test('« Superflue » judges the line in place', async () => {
    renderActivity()
    const first = await card()

    await userEvent.click(first.getByRole('button', { name: 'Superflue' }))

    await waitFor(() => expect(writes('PATCH')).toHaveLength(1))
    expect(writes('PATCH')[0]).toEqual({ url: '/api/transactions/T1', body: { retrospect: 'avoidable' } })
    expect(await first.findByRole('button', { name: 'Superflue', pressed: true })).toBeInTheDocument()
  })

  test('a review with nothing left starts at the balance, and the steps with nothing to show pass by themselves', async () => {
    server.handled = ['T1', 'T2', 'T3']
    renderActivity()

    expect(await screen.findByTestId('month-balance-step')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Bilan du mois' })).toBeInTheDocument()
    // No envelope: « Ensuite… » skips them.
    expect(screen.getByText('Ensuite… À venir')).toBeInTheDocument()
    expect(progressWrites()).toEqual([{ month: MONTH, step: 'balance' }])

    await userEvent.keyboard('{ArrowRight}')
    expect(await screen.findByTestId('upcoming-week')).toBeInTheDocument()
    expect(screen.queryByTestId('envelopes-step')).not.toBeInTheDocument()

    await userEvent.keyboard('{ArrowRight}')
    expect(await screen.findByTestId('next-project-card')).toBeInTheDocument()
    expect(screen.getByText('Ensuite… fin de la revue')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Terminer →' }))
    expect(await screen.findByText('Revue terminée')).toBeInTheDocument()
    expect(progressWrites().map((write) => write.step)).toEqual(['balance', 'upcoming', 'projects', 'done'])

    // ← from the end screen is no step: the review stays done.
    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByText('Revue terminée')).toBeInTheDocument()
  })

  test('the envelopes and the upcoming steps show when they have something', async () => {
    server.step = 'balance'
    server.envelopes = [{ categoryId: 'C1' }]
    server.upcoming = emptyProjection()
    renderActivity()
    expect(await screen.findByTestId('month-balance-step')).toBeInTheDocument()

    await userEvent.keyboard('{ArrowRight}')
    expect(await screen.findByText(`Enveloppes de ${MONTH}`)).toBeInTheDocument()

    // Nothing to come: « À venir » passes, the projects stay.
    await userEvent.keyboard('{ArrowRight}')
    expect(await screen.findByTestId('next-project-card')).toBeInTheDocument()

    await userEvent.keyboard('{ArrowLeft}')
    expect(await screen.findByText(`Enveloppes de ${MONTH}`)).toBeInTheDocument()
    expect(progressWrites().map((write) => write.step)).toEqual(['envelopes', 'projects', 'envelopes'])
  })

  test('a month that closes no period has the five steps and ends on the projects', async () => {
    server.step = 'projects'
    renderActivity()

    expect(await screen.findByTestId('next-project-card')).toBeInTheDocument()
    expect(screen.getAllByTestId(/^review-segment-/)).toHaveLength(5)
    expect(screen.getByText('Ensuite… fin de la revue')).toBeInTheDocument()
    expect(screen.queryByTestId('period-balance-step')).not.toBeInTheDocument()
  })

  test('the review of a quarter\'s last month adds the period\'s bilan then the plan of the next one', async () => {
    server.step = 'projects'
    server.closing = QUARTER_END
    renderActivity()

    expect(await screen.findByTestId('next-project-card')).toBeInTheDocument()
    expect(screen.getAllByTestId(/^review-segment-/)).toHaveLength(7)
    expect(screen.getByText('Ensuite… Période')).toBeInTheDocument()
    // « Terminer » is for the last step only.
    expect(screen.getByRole('button', { name: 'Suivant →' })).toBeInTheDocument()

    await userEvent.keyboard('{ArrowRight}')
    expect(await screen.findByTestId('period-balance-step')).toHaveTextContent(`Bilan de la période de ${MONTH}`)
    expect(screen.getByRole('heading', { name: 'Bilan du trimestre' })).toBeInTheDocument()
    expect(screen.getByText('Ensuite… Planification')).toBeInTheDocument()

    await userEvent.keyboard('{ArrowRight}')
    expect(await screen.findByTestId('period-plan-step')).toHaveTextContent('Plan de 2027-Q2')
    expect(screen.getByRole('heading', { name: 'Planification du trimestre suivant' })).toBeInTheDocument()
    expect(screen.getByText('Ensuite… fin de la revue')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Terminer →' }))
    expect(await screen.findByText('Revue terminée')).toBeInTheDocument()
    expect(progressWrites().map((write) => write.step)).toEqual(['period_balance', 'period_plan', 'done'])
  })

  test('a year\'s review names its bilan and its plan after the year', async () => {
    server.step = 'period_balance'
    server.closing = {
      level: 'year',
      period: { key: '2026', level: 'year', from: '2026-01', to: '2026-12', months: 12 },
      nextPeriod: { key: '2027', level: 'year', from: '2027-01', to: '2027-12', months: 12 },
    }
    renderActivity()

    expect(await screen.findByRole('heading', { name: "Bilan de l'année" })).toBeInTheDocument()
    await userEvent.keyboard('{ArrowRight}')
    expect(await screen.findByRole('heading', { name: "Planification de l'année suivante" })).toBeInTheDocument()
  })

  test('resumes on the plan of the next period where it was quit', async () => {
    server.step = 'period_plan'
    server.handled = ['T1', 'T2', 'T3']
    server.closing = QUARTER_END
    renderActivity()

    expect(await screen.findByTestId('period-plan-step')).toBeInTheDocument()
    expect(progressWrites()).toEqual([])
    await userEvent.click(screen.getByRole('button', { name: '← Précédent' }))
    expect(await screen.findByTestId('period-balance-step')).toBeInTheDocument()
  })

  test('← from the balance goes back to the last line of the review', async () => {
    server.step = 'balance'
    server.handled = ['T1', 'T2', 'T3']
    renderActivity()
    expect(await screen.findByTestId('month-balance-step')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '← Précédent' }))

    expect((await card()).getByText('3 / 3')).toBeInTheDocument()
    expect(progressWrites()).toEqual([{ month: MONTH, step: 'review' }])
  })

  test('a done review shows its end screen and starts again from the balance', async () => {
    server.step = 'done'
    renderActivity()

    expect(await screen.findByText('Revue terminée')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Reprendre depuis le Bilan' }))

    expect(await screen.findByTestId('month-balance-step')).toBeInTheDocument()
    expect(progressWrites()).toEqual([{ month: MONTH, step: 'balance' }])
  })

  test('Échap goes back where the review was launched from', async () => {
    renderActivity('/calendar')
    await card()

    await userEvent.keyboard('{Escape}')

    expect(await screen.findByTestId('where')).toHaveTextContent('/calendar')
  })

  test('quitting a review launched from nowhere goes to the finance home', async () => {
    renderActivity(null)
    await card()

    await userEvent.click(screen.getByRole('button', { name: 'Quitter la revue' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/finance/dashboard')
  })

  test('the keys typed in a dialog stay there', async () => {
    renderActivity()
    const first = await card()

    await userEvent.click(first.getByRole('button', { name: 'Changer' }))
    const dialog = await screen.findByRole('dialog', { name: 'Changer la catégorie' })
    await userEvent.type(within(dialog).getByLabelText('Catégorie'), 'Lo{Enter}{ArrowRight}')

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    // The page behind the dialog is aria-hidden: read the counter by its text.
    expect(screen.getByText('1 / 3')).toBeInTheDocument()
    expect(progressWrites()).toEqual([])
  })

  test('the Maggie button opens the chat beside the activity', async () => {
    renderActivity()
    await card()
    expect(screen.queryByTestId('chat-panel')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Maggie' }))

    expect(screen.getByTestId('chat-panel')).toBeInTheDocument()
  })

  test('a progress that cannot be read says so and can be retried', async () => {
    server.progressFails = true
    renderActivity()

    expect(await screen.findByText('Revue indisponible')).toBeInTheDocument()
    server.progressFails = false
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect((await card()).getByText('CARREFOUR MARKET 12')).toBeInTheDocument()
  })

  test('a queue that cannot be read resumes nothing and writes nothing, until it is read again', async () => {
    server.queueFails = true
    renderActivity()

    expect(await screen.findByText('File indisponible')).toBeInTheDocument()
    expect(screen.queryByTestId('month-balance-step')).not.toBeInTheDocument()
    expect(progressWrites()).toEqual([])
    expect(server.step).toBe('review')

    server.queueFails = false
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect((await card()).getByText('CARREFOUR MARKET 12')).toBeInTheDocument()
    expect(progressWrites()).toEqual([])
  })

  test('a handled line the API refused to store keeps the line on screen', async () => {
    server.handled = ['T1']
    server.saveFails = true
    renderActivity()
    await card()

    await userEvent.keyboard('{ArrowRight}')

    expect(await screen.findByText('Progression refusée')).toBeInTheDocument()
    expect((await card()).getByText('BOULANGERIE DU COIN')).toBeInTheDocument()
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
  })
})

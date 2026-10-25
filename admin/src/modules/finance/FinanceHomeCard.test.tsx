import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { TOKENS } from '../../design/tokens'
import { emitMercure } from '../../test/mercure'
import { FinanceHomeCard } from './FinanceHomeCard'
import { dashboardFixture, nextProjectFixture } from './dashboardFixture'
import { period, rhythm, scores } from './scoresFixture'
import type { FinanceDashboard } from './useFinanceDashboard'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const Where = () => {
  const { pathname, search } = useLocation()

  return <output data-testid="where">{pathname + search}</output>
}

const renderCard = (theme = veilleuseLightTheme) =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={theme}>
        <FinanceHomeCard />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

const ring = (extra: Record<string, unknown> = {}) =>
  rhythm({ status: 'on_track', label: 'Dans le rythme', budgetCents: 30000, remainingCents: 21000, expectedRemainingCents: 18000, ...extra })

const dashboard = (overrides: Partial<FinanceDashboard> = {}) =>
  dashboardFixture({
    scores: scores({
      week: period({ rhythm: ring({ budgetCents: 7000, remainingCents: 4500, expectedRemainingCents: 4000 }) }),
      month: period({ rhythm: ring() }),
    }),
    nextProject: nextProjectFixture({ name: 'Vélo', amountCents: 100000, gatheredCents: 74000, purchaseMonth: '2099-10' }),
    ...overrides,
  })

const withTodo = (toSortCount: number) => ({ ...dashboardFixture().todo, toSortCount })
const withMonth = (month: Parameters<typeof period>[0]) => scores({ week: dashboard().scores.week, month: period(month) })

const ok = (body: unknown) => ({ ok: true, status: 200, json: () => Promise.resolve(body) })
const failing = (status: number) => ({ ok: false, status, json: () => Promise.resolve({}) })

const ringOf = (period: 'week' | 'month') => screen.getAllByTestId('home-finance-ring').find((r) => r.dataset.period === period) as HTMLElement

describe('FinanceHomeCard', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  test('reads the Finance dashboard resource alone: one request, never /api/home', async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok(dashboard()))
    vi.stubGlobal('fetch', fetchMock)
    renderCard()

    await screen.findAllByTestId('home-finance-ring')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toMatch(/\/finance\/dashboard$/)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/home'))).toBe(false)
  })

  test('shows the week and the month rings with what is left, in €', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(dashboard())))
    renderCard()

    await screen.findAllByTestId('home-finance-ring')

    expect(within(ringOf('week')).getByTestId('home-finance-remaining').textContent).toMatch(/45,00\s€/)
    expect(within(ringOf('month')).getByTestId('home-finance-remaining').textContent).toMatch(/210,00\s€/)
    expect(ringOf('week')).toHaveAccessibleName(/Semaine : Dans le rythme/)
  })

  test('is not drawn at all with no account and no envelope', async () => {
    const empty = dashboard({
      tiles: {
        ...dashboardFixture().tiles,
        accounts: { totalCents: 0, cushionCents: 0, availableCents: 0, accounts: [] },
        budget: { ...dashboardFixture().tiles.budget, envelopes: [] },
      },
      todo: withTodo(0),
      nextProject: null,
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(empty)))
    renderCard()
    expect(screen.getByTestId('home-finance-loading')).toBeInTheDocument()

    await waitFor(() => expect(screen.queryByTestId('home-card-finance')).toBeNull())
    expect(screen.queryByText('Finance')).toBeNull()
  })

  test('is drawn with an account only, or an envelope only', async () => {
    const base = dashboardFixture().tiles
    const noEnvelope = dashboard({ tiles: { ...base, budget: { ...base.budget, envelopes: [] } } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(noEnvelope)))
    const { unmount } = renderCard()
    expect(await screen.findAllByTestId('home-finance-ring')).toHaveLength(2)
    unmount()

    const noAccount = dashboard({ tiles: { ...base, accounts: { ...base.accounts, accounts: [] } } })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(noAccount)))
    renderCard()
    expect(await screen.findAllByTestId('home-finance-ring')).toHaveLength(2)
  })

  test('stays drawn, with its error, when the read fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(failing(500)))
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    expect(screen.getByTestId('home-card-finance')).toBeInTheDocument()
  })

  test('says « Dans le rythme » on track, in the on-track colour of the charter', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(dashboard())))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    const status = within(ringOf('month')).getByTestId('home-finance-status')

    expect(ringOf('month')).toHaveAttribute('data-status', 'on_track')
    expect(status).toHaveTextContent('Dans le rythme')
    expect(status).toHaveStyle({ color: TOKENS.rhythm.onTrack.light })
  })

  test('says « Au-dessus du rythme » above it, in the above colour, in dark mode too', async () => {
    const above = dashboard({ scores: withMonth({ rhythm: ring({ status: 'above', label: 'Au-dessus du rythme', remainingCents: -5800 }) }) })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(above)))
    renderCard(veilleuseDarkTheme)
    await screen.findAllByTestId('home-finance-ring')

    const status = within(ringOf('month')).getByTestId('home-finance-status')

    expect(ringOf('month')).toHaveAttribute('data-status', 'above')
    expect(status).toHaveTextContent('Au-dessus du rythme')
    expect(status).toHaveStyle({ color: TOKENS.rhythm.above.dark })
    expect(within(ringOf('week')).getByTestId('home-finance-status')).toHaveTextContent('Dans le rythme')
  })

  test('a ring with no envelope says so instead of an amount', async () => {
    const noWeek = dashboard({
      scores: scores({ week: period({ rhythm: ring({ budgetCents: 0, remainingCents: 0, expectedRemainingCents: 0 }) }), month: period({ rhythm: ring() }) }),
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(noWeek)))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    expect(within(ringOf('week')).getByText('Aucune enveloppe')).toBeInTheDocument()
    expect(within(ringOf('week')).queryByTestId('home-finance-status')).not.toBeInTheDocument()
    expect(within(ringOf('week')).queryByTestId('home-finance-remaining')).toBeNull()
  })

  test('tells how many lines are left to qualify and opens the review on them', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(dashboard({ todo: withTodo(3) }))))
    renderCard()

    const pill = await screen.findByTestId('home-finance-to-qualify')
    expect(pill).toHaveTextContent('3 à qualifier')

    await user.click(pill)

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/review?')
    expect(screen.getByTestId('where')).toHaveTextContent('state=unrecognised')
  })

  test('has no « à qualifier » pill when there is nothing to qualify', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(dashboard({ todo: withTodo(0) }))))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    expect(screen.queryByTestId('home-finance-to-qualify')).toBeNull()
  })

  test('shows the next project with what is gathered', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(dashboard())))
    renderCard()

    const project = await screen.findByTestId('home-finance-next-project')

    expect(project).toHaveTextContent('Vélo')
    expect(project.textContent).toMatch(/740,00\s€ sur 1\s000,00\s€/)
    expect(within(project).getByRole('progressbar', { name: 'Vélo : 74 % atteint' })).toBeInTheDocument()
  })

  test.each(['/api/transactions/{id}', '/api/envelopes/{id}', '/api/accounts/{id}', '/api/finance/projects/{id}'])(
    'is read again when %s publishes: the card follows in real time',
    async (topic) => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(ok(dashboard({ todo: withTodo(3) })))
        .mockResolvedValueOnce(ok(dashboard({ todo: withTodo(2), nextProject: null })))
      vi.stubGlobal('fetch', fetchMock)
      renderCard()
      expect(await screen.findByTestId('home-finance-to-qualify')).toHaveTextContent('3 à qualifier')
      expect(screen.getByTestId('home-finance-next-project')).toBeInTheDocument()

      await act(async () => {
        await emitMercure(topic, { '@id': '/api/x/1' })
      })

      await waitFor(() => expect(screen.getByTestId('home-finance-to-qualify')).toHaveTextContent('2 à qualifier'))
      expect(screen.queryByTestId('home-finance-next-project')).toBeNull()
      expect(fetchMock).toHaveBeenCalledTimes(2)
    },
  )

  test('keeps the card on screen when a refresh fails, and says so', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(ok(dashboard())).mockResolvedValueOnce(failing(503)))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    await act(async () => {
      await emitMercure('/api/transactions/{id}', {})
    })

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    expect(screen.getAllByTestId('home-finance-ring')).toHaveLength(2)
  })

  test('opens Finance › Dashboard from its title', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(ok(dashboard())))
    renderCard()
    await screen.findAllByTestId('home-finance-ring')

    await user.click(screen.getByRole('link', { name: 'Finance' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/dashboard')
  })

  test('says it could not load, and tries again', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(failing(500)).mockResolvedValueOnce(ok(dashboard())))
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findAllByTestId('home-finance-ring')).toHaveLength(2)
  })
})

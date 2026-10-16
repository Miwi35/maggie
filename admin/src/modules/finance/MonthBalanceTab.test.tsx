import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { monthBalanceFixture } from './monthBalanceFixture'
import { MonthBalanceTab } from './MonthBalanceTab'
import type { MonthBalance } from './useMonthBalance'

const mercure = vi.hoisted(() => ({ handlers: new Map<string, () => void>() }))
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[], onMessage: () => void) => {
    mercure.handlers.set(topics.join(','), onMessage)
  },
}))
const publishMercure = () => mercure.handlers.forEach((handler) => handler())

const answer = (body: unknown, ok = true) =>
  Promise.resolve({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) })

const stubApi = (payload: MonthBalance | unknown, ok = true) => {
  // The API answers the month it is asked for.
  const fetchMock = vi.fn((url: string) => {
    if (!url.includes('/api/finance/month-balance')) {
      return answer({}, false)
    }
    const asked = /month=(\d{4}-\d{2})/.exec(url)?.[1]

    return answer(asked && ok ? { ...(payload as object), month: asked } : payload, ok)
  })
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const monthRequests = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.map(([url]) => String(url)).filter((url) => url.includes('/api/finance/month-balance'))

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/finance/dashboard/current']}>
      <AdminContext dataProvider={testDataProvider()}>
        <MonthBalanceTab />
      </AdminContext>
    </MemoryRouter>,
  )

describe('MonthBalanceTab', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('draws the month ring, the curve, the envelopes, the satisfaction, the savings and the cushion', async () => {
    stubApi(monthBalanceFixture())
    renderPage()

    expect(await screen.findByTestId('month-balance')).toBeInTheDocument()
    expect(screen.getByTestId('score-ring')).toBeInTheDocument()
    expect(screen.getByTestId('rhythm-curve')).toBeInTheDocument()
    expect(screen.getAllByTestId('envelope-bar')).toHaveLength(3)
    expect(screen.getByTestId('satisfaction-ring')).toBeInTheDocument()
    expect(screen.getByTestId('savings-text')).toHaveTextContent('sur')
    expect(screen.getByTestId('cushion-text')).toHaveTextContent('70 %')
  })

  test('asks for the current month first, with the bearer', async () => {
    const fetchMock = stubApi(monthBalanceFixture())
    renderPage()
    await screen.findByTestId('month-balance')

    const now = new Date()
    const expected = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
    expect(monthRequests(fetchMock)).toEqual([`/api/finance/month-balance?month=${expected}`])
    expect((fetchMock.mock.calls[0] as unknown[])[1]).toMatchObject({ headers: { Authorization: 'Bearer test-jwt-token' } })
  })

  test('marks each envelope held, close or over, in words and not only in colour', async () => {
    stubApi(monthBalanceFixture())
    renderPage()

    const bars = await screen.findAllByTestId('envelope-bar')
    const status = Object.fromEntries(bars.map((bar) => [bar.dataset.name, bar.dataset.status]))

    expect(status).toEqual({ Alimentation: 'held', Loisirs: 'close', Transport: 'over' })
    expect(within(bars[0]).getByText(/Tenue/)).toBeInTheDocument()
    expect(within(bars[1]).getByText(/Proche/)).toBeInTheDocument()
    expect(within(bars[2]).getByText(/Dépassée/)).toBeInTheDocument()
    expect(within(bars[2]).getByText(/150 %/)).toBeInTheDocument()
  })

  test('shows the kept share and the superfluous amount of the satisfaction', async () => {
    stubApi(monthBalanceFixture())
    renderPage()

    expect(await screen.findByTestId('satisfaction-percent')).toHaveTextContent('71 %')
    expect(screen.getByTestId('satisfaction-avoidable')).toHaveTextContent(/50,00\s€/)
    expect(screen.getByTestId('satisfaction-unrated')).toHaveTextContent('3 à juger')
  })

  test('reports the unqualified lines apart, one tap from the page that qualifies them', async () => {
    stubApi(monthBalanceFixture())
    renderPage()

    const pill = await screen.findByTestId('balance-to-qualify')
    expect(pill).toHaveTextContent(/À qualifier · 1 · 40,00\s€/)

    await userEvent.setup().click(pill)
    expect(screen.getByTestId('where')).toHaveTextContent('/finance/to-sort')
  })

  test('a month with nothing to qualify, no envelope and nothing judged shows an empty state, not zeros', async () => {
    stubApi(
      monthBalanceFixture({
        envelopes: [],
        satisfaction: { judgedCents: 0, keptCents: 0, avoidableCents: 0, unratedCount: 0, keptPercent: null },
        savings: { savedCents: 0, plannedCents: null },
        toQualify: { count: 0, cents: 0 },
      }),
    )
    renderPage()

    expect(await screen.findByTestId('envelope-bars-empty')).toBeInTheDocument()
    expect(screen.getByTestId('satisfaction-empty')).toHaveTextContent('Rien de jugé')
    expect(screen.queryByTestId('satisfaction-percent')).not.toBeInTheDocument()
    expect(screen.queryByTestId('satisfaction-unrated')).not.toBeInTheDocument()
    expect(screen.queryByTestId('balance-to-qualify')).not.toBeInTheDocument()
    expect(screen.getByTestId('savings-text')).toHaveTextContent('capacité inconnue')
  })

  test('says when the cushion is not configured', async () => {
    stubApi(
      monthBalanceFixture({
        cushion: { state: 'building', targetCents: 0, currentCents: 0, coveragePercent: 0, monthsCovered: 0, isConfigured: false },
      }),
    )
    renderPage()

    expect(await screen.findByTestId('cushion-text')).toHaveTextContent('Matelas non configuré')
  })

  test('changing the month asks for that month', async () => {
    const fetchMock = stubApi(monthBalanceFixture())
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('month-balance')

    await user.click(screen.getByLabelText('Mois'))
    await user.click(await screen.findByRole('option', { name: 'Janvier' }))

    await waitFor(() => {
      expect(monthRequests(fetchMock).at(-1)).toMatch(/month=\d{4}-01$/)
    })
    expect(monthRequests(fetchMock).length).toBeGreaterThan(1)
    await waitFor(() => {
      expect(screen.getByTestId('month-balance').getAttribute('data-month')).toMatch(/-01$/)
    })
  })

  test('a Mercure update reloads the balance', async () => {
    const fetchMock = stubApi(monthBalanceFixture())
    renderPage()
    await screen.findByTestId('month-balance')
    const before = monthRequests(fetchMock).length

    publishMercure()

    await waitFor(() => {
      expect(monthRequests(fetchMock).length).toBe(before + 1)
    })
  })

  test('a failed read shows an error with a retry, and the retry reads again', async () => {
    const fetchMock = stubApi({}, false)
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByText(/n'a pas pu être chargé/)).toBeInTheDocument()
    expect(screen.queryByTestId('month-balance')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))
    await waitFor(() => {
      expect(monthRequests(fetchMock)).toHaveLength(2)
    })
  })
})

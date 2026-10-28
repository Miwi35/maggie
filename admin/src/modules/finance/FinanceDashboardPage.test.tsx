import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { dashboardFixture, nextProjectFixture } from './dashboardFixture'
import { FinanceDashboardPage } from './FinanceDashboardPage'
import { reviewTitle } from './reviewActivity'
import { upcomingFixture } from './upcomingFixture'
import type { FinanceDashboard } from './useFinanceDashboard'

const mercure = vi.hoisted(() => ({ handlers: new Map<string, () => void>() }))
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[], onMessage: () => void) => {
    mercure.handlers.set(topics.join(','), onMessage)
  },
}))
const publishMercure = () => mercure.handlers.forEach((handler) => handler())

const answer = (body: unknown, ok = true) => Promise.resolve({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) })

const stubApi = (payload: FinanceDashboard | unknown, ok = true) => {
  const fetchMock = vi.fn((url: string) => {
    return url.includes('/api/finance/dashboard') ? answer(payload, ok) : answer({}, false)
  })
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const Where = () => {
  const { pathname, search } = useLocation()

  return <span data-testid="where">{pathname + search}</span>
}

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/finance/dashboard']}>
      <AdminContext dataProvider={testDataProvider()}>
        <FinanceDashboardPage />
        <Where />
      </AdminContext>
    </MemoryRouter>,
  )

describe('FinanceDashboardPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('reads the next project and the delay from the dashboard, not from an example', async () => {
    stubApi(dashboardFixture())
    renderPage()

    const card = await screen.findByTestId('next-project-card')
    expect(card).toHaveTextContent('Vélo')
    expect(screen.getByTestId('next-project-negative')).toHaveTextContent("repoussent Voyage au Japon d'un mois")
  })

  test('hides the next project block without a project', async () => {
    stubApi(dashboardFixture({ nextProject: null }))
    renderPage()

    expect(await screen.findByTestId('tile-budget')).toBeInTheDocument()
    expect(screen.queryByTestId('next-project-card')).not.toBeInTheDocument()
  })

  test('names the first project beyond the horizon when none lands inside it', async () => {
    const beyond = { projectId: 'p9', name: 'Salle de bain', amountCents: 900000, gatheredCents: 0, horizonMonths: 36 }
    stubApi(dashboardFixture({ nextProject: null, projectBeyondHorizon: beyond }))
    renderPage()

    const card = await screen.findByTestId('next-project-card')
    expect(card).toHaveTextContent('Salle de bain')
    expect(screen.getByTestId('next-project-date')).toHaveTextContent('au-delà de 3 ans')
  })

  test('shows no negative return when nothing moves back', async () => {
    stubApi(dashboardFixture({ nextProject: nextProjectFixture({ delay: null }) }))
    renderPage()

    expect(await screen.findByTestId('next-project-card')).toBeInTheDocument()
    expect(screen.queryByTestId('next-project-negative')).not.toBeInTheDocument()
  })

  test('lays out the rings, the month spending, the next project and the three tiles', async () => {
    stubApi(dashboardFixture())
    renderPage()

    expect(await screen.findByTestId('month-spending-card')).toBeInTheDocument()
    expect(screen.getAllByTestId('score-ring')).toHaveLength(2)
    expect(screen.getByTestId('rhythm-curve')).toBeInTheDocument()
    expect(screen.getByTestId('next-project-card')).toBeInTheDocument()
    expect(screen.getByTestId('tile-accounts')).toBeInTheDocument()
    expect(screen.getByTestId('tile-budget')).toBeInTheDocument()
    expect(screen.getByTestId('tile-planning')).toBeInTheDocument()
  })

  test('feeds the rings from the dashboard payload: one read, no scores request', async () => {
    const fetchMock = stubApi(dashboardFixture())
    renderPage()

    await screen.findAllByTestId('score-ring')

    const urls = fetchMock.mock.calls.map(([url]) => String(url))
    expect(urls.filter((url) => url.includes('/api/finance/dashboard'))).toHaveLength(1)
    expect(urls.some((url) => url.includes('/scores'))).toBe(false)
  })

  test('no longer draws the removed blocks', async () => {
    stubApi(dashboardFixture())
    renderPage()
    await screen.findByTestId('tile-budget')

    expect(screen.queryByText('Solde de tous les comptes')).not.toBeInTheDocument()
    expect(screen.queryByText('Principaux postes du mois')).not.toBeInTheDocument()
    expect(screen.queryByText('Recettes et dépenses sur douze mois')).not.toBeInTheDocument()
    expect(screen.queryByTestId('top-post')).not.toBeInTheDocument()
  })

  test.each([
    ['tile-accounts', '/accounts'],
    ['tile-budget', '/envelopes'],
    ['tile-planning', '/finance/treasury'],
  ])('a click on %s opens %s', async (testId, path) => {
    const user = userEvent.setup()
    stubApi(dashboardFixture())
    renderPage()

    const tile = await screen.findByTestId(testId)
    await user.click(within(tile).getAllByRole('link')[0])

    expect(screen.getByTestId('where')).toHaveTextContent(path)
  })

  test('the salary link of the Budget tile opens the recurring operations, not the budget', async () => {
    const user = userEvent.setup()
    const payload = dashboardFixture()
    payload.tiles.budget.expectedSalary = null
    stubApi(payload)
    renderPage()

    await user.click(await screen.findByRole('link', { name: /Ajouter l'opération récurrente/ }))

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/recurring-operations')
  })

  test.each([
    ['pill-to-sort', '/finance/review?state=unrecognised&period=all'],
    ['pill-rejections', '/accounts/a1/transactions'],
    ['pill-advance', '/finance/savings-advances'],
  ])('the pill %s opens %s, not the tile', async (testId, path) => {
    const user = userEvent.setup()
    stubApi(dashboardFixture())
    renderPage()

    await user.click(await screen.findByTestId(testId))

    expect(screen.getByTestId('where')).toHaveTextContent(path)
  })

  test('shows the counts of what is to do in the tiles', async () => {
    stubApi(dashboardFixture())
    renderPage()

    expect(await screen.findByTestId('pill-to-sort')).toHaveTextContent('3 à trier')
    expect(screen.getByTestId('pill-rejections')).toHaveTextContent('1 rejet')
    expect(screen.getByTestId('pill-advance')).toHaveTextContent(/800,00\s€ à remettre/)
    expect(screen.queryByTestId('review-shortcut')).not.toBeInTheDocument()
  })

  test('has no pill and no shortcut when there is nothing to do', async () => {
    const payload = dashboardFixture({ todo: { toSortCount: 0, rejectionsToSettleCount: 0, reviewReady: false, advanceToRepayCents: 0, owedToMeCents: 0, iOweCents: 0 } })
    stubApi(payload)
    renderPage()
    await screen.findByTestId('tile-accounts')

    expect(screen.queryByTestId('pill-to-sort')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pill-rejections')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pill-advance')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pill-owed')).not.toBeInTheDocument()
    expect(screen.queryByTestId('review-shortcut')).not.toBeInTheDocument()
  })

  test('the owed pill opens Créances et dettes', async () => {
    const user = userEvent.setup()
    const payload = dashboardFixture()
    payload.todo.iOweCents = 28000
    payload.todo.owedToMeCents = 20000
    stubApi(payload)
    renderPage()

    const pill = await screen.findByTestId('pill-owed')
    expect(pill).toHaveTextContent(/Tu dois 80,00\s€/)
    await user.click(pill)

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/peer-debts')
  })

  test('the review is an action card, not a pill: its title, its time, one « Lancer » that opens the activity', async () => {
    const user = userEvent.setup()
    const payload = dashboardFixture()
    payload.todo.reviewReady = true
    stubApi(payload)
    renderPage()

    const card = await screen.findByTestId('review-action-card')
    expect(card).toHaveTextContent(reviewTitle(new Date()))
    expect(card).toHaveTextContent('ce soir · 20 h')
    expect(screen.queryByText('Revue ce soir')).not.toBeInTheDocument()
    expect(screen.queryByTestId('review-shortcut')).not.toBeInTheDocument()

    await user.click(within(card).getByRole('link', { name: 'Lancer' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/finance/review/activity')
  })

  test('has no review card while the review is not due', async () => {
    stubApi(dashboardFixture())
    renderPage()
    await screen.findByTestId('tile-budget')

    expect(screen.queryByTestId('review-action-card')).not.toBeInTheDocument()
  })

  test('is headed by the day of the month, without any period picker', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 13, 10, 0))
    try {
      stubApi(dashboardFixture())
      renderPage()

      expect(await screen.findByTestId('dashboard-header')).toHaveTextContent('Octobre, jour 13 sur 31')
      expect(screen.queryByLabelText('Année')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Mois')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  test('says when the banks were last synchronised, from the freshest connection', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-13T10:12:00Z'))
    try {
      const fetchMock = vi.fn((url: string) => {
        if (url.includes('/api/finance/bank-connections')) {
          return answer({
            connections: [
              { id: 'c1', bankName: 'A', lastSyncedAt: '2026-10-13T08:00:00Z' },
              { id: 'c2', bankName: 'B', lastSyncedAt: '2026-10-13T10:00:00Z' },
            ],
          })
        }

        return url.includes('/api/finance/dashboard') ? answer(dashboardFixture()) : answer({}, false)
      })
      vi.stubGlobal('fetch', fetchMock)
      renderPage()

      await waitFor(() => expect(screen.getByTestId('dashboard-header')).toHaveTextContent('Synchronisé il y a 12 min'))
    } finally {
      vi.useRealTimers()
    }
  })

  test('draws the month as the mock-up: ring cards, Prochain projet, the month\'s spending and the three cards that open', async () => {
    stubApi(dashboardFixture())
    renderPage()

    expect(await screen.findAllByTestId('score-ring')).toHaveLength(2)
    expect(screen.getByTestId('next-project-card')).toBeInTheDocument()
    const spending = screen.getByTestId('month-spending-card')
    expect(spending).toHaveTextContent(/^Dépenses d/)
    expect(within(spending).getByTestId('rhythm-curve')).toBeInTheDocument()
    for (const id of ['tile-accounts', 'tile-budget', 'tile-planning']) {
      expect(within(screen.getByTestId(id)).getByText('Ouvrir →')).toBeInTheDocument()
    }
  })

  test('says nothing the mock-up does not say', async () => {
    const payload = dashboardFixture()
    payload.tiles.planning.independence.hasPassiveIncomeCategories = false
    payload.tiles.planning.savingCapacity.isIncomeKnown = false
    stubApi(payload)
    renderPage()
    await screen.findByTestId('tile-planning')

    expect(screen.queryByText(/Aucune catégorie n'est déclarée comme rente/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Renseignez votre revenu net/)).not.toBeInTheDocument()
    expect(screen.queryByText('Bilan du mois')).not.toBeInTheDocument()
    expect(screen.queryByTestId('month-balance')).not.toBeInTheDocument()
  })

  test('the pill follows the next read: settling the last rejection drops it', async () => {
    const fetchMock = stubApi(dashboardFixture())
    renderPage()
    expect(await screen.findByTestId('pill-rejections')).toBeInTheDocument()

    const settled = dashboardFixture()
    settled.todo.rejectionsToSettleCount = 0
    settled.tiles.accounts.accounts[0].rejectionsToSettleCount = 0
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/finance/dashboard') ? answer(settled) : answer({}, false),
    )
    act(() => publishMercure())

    await waitFor(() => expect(screen.queryByTestId('pill-rejections')).not.toBeInTheDocument())
  })

  test('says the dashboard failed, keeps the page usable, and retries on demand', async () => {
    const user = userEvent.setup()
    let dashboardCalls = 0
    const fetchMock = vi.fn((url: string) => {
      if (!url.includes('/api/finance/dashboard')) return answer({}, false)
      dashboardCalls += 1

      return dashboardCalls === 1 ? answer({ error: 'boom' }, false) : answer(dashboardFixture())
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    expect(await screen.findByText("Le tableau de bord n'a pas pu être chargé.")).toBeInTheDocument()
    expect(screen.queryByTestId('tile-budget')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('tile-budget')).toBeInTheDocument()
    expect(screen.queryByText("Le tableau de bord n'a pas pu être chargé.")).not.toBeInTheDocument()
  })

  test('a Mercure message reloads the tiles and the rings with the same single read', async () => {
    const fetchMock = stubApi(dashboardFixture())
    renderPage()
    await screen.findByTestId('tile-accounts')

    const updated = dashboardFixture()
    updated.tiles.accounts.totalCents = 999900
    fetchMock.mockClear()
    fetchMock.mockImplementation((url: string) =>
      url.includes('/api/finance/dashboard') ? answer(updated) : answer({}, false),
    )
    act(() => publishMercure())

    await waitFor(() => expect(screen.getByTestId('tile-accounts')).toHaveTextContent(/9\s999,00\s€/))
    const urls = fetchMock.mock.calls.map(([url]) => String(url))
    expect(urls.filter((url) => url.includes('/api/finance/dashboard'))).toHaveLength(1)
  })

  test("opens on « État courant » and offers « À venir » alone beside it, without reading the projection until asked", async () => {
    const fetchMock = stubApi(dashboardFixture())
    renderPage()

    expect(await screen.findByTestId('tile-budget')).toBeInTheDocument()
    expect(screen.getAllByRole('tab')).toHaveLength(2)
    expect(screen.queryByRole('tab', { name: "Vue d'ensemble" })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'État courant' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'À venir' })).toHaveAttribute('aria-selected', 'false')
    expect(screen.queryByTestId('upcoming-tab')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/api/finance/upcoming'))).toBe(false)
  })

  test("switches to « À venir », which leaves the current state's cards behind", async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/finance/upcoming')) return answer(upcomingFixture())
      if (url.includes('/api/finance/transactions/to-sort')) return answer({ total: 0, totalCents: 0, transactions: [] })

      return url.includes('/api/finance/dashboard') ? answer(dashboardFixture()) : answer({}, false)
    })
    vi.stubGlobal('fetch', fetchMock)
    renderPage()
    await screen.findByTestId('tile-budget')

    await user.click(screen.getByRole('tab', { name: 'À venir' }))

    expect(await screen.findByTestId('upcoming-month')).toBeInTheDocument()
    expect(screen.queryByTestId('tile-budget')).not.toBeInTheDocument()
    expect(screen.queryByTestId('next-project-card')).not.toBeInTheDocument()
  })
})

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { ActivityPage } from './ActivityPage'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const PROFILE = {
  stepGoal: 8000,
  suggestionWindowStart: '08:00',
  suggestionWindowEnd: '20:00',
  stepSource: 'health_connect',
}

const workout = (id: string, type: string, source: string, title: string, hour: number, over: Record<string, unknown> = {}) => ({
  id: `/api/workouts/${id}`,
  type,
  title,
  source,
  startedAt: new Date(2026, 10, 20, hour, 0).toISOString(),
  durationMinutes: 25,
  kcal: 210,
  ...over,
})

interface Stub {
  profile?: Record<string, unknown>
  todaySteps?: number | null
  workouts?: unknown[]
  fail?: boolean
}

function stub({ profile = PROFILE, todaySteps = 2100, workouts = [], fail = false }: Stub = {}) {
  const fetcher = vi.fn(async (url: string) => {
    if (fail) return json({}, 500)
    const { pathname, searchParams } = new URL(url, 'http://localhost')
    if (pathname === '/api/health/profile') return json(profile)
    if (pathname === '/api/workouts') return json({ member: workouts })
    const to = searchParams.get('to') ?? ''

    return json([{ date: to, steps: todaySteps, activeKcal: null, bmrKcal: null, workoutKcal: null, stepsKcal: null, expendedKcal: null }])
  })
  vi.stubGlobal('fetch', fetcher)

  return fetcher
}

const Where = () => <span data-testid="where">{useLocation().pathname}</span>

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/health/activity']}>
      <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme}>
        <Routes>
          <Route path="/health/activity" element={<ActivityPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

describe('ActivityPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 20, 12, 40))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('shows the ring, the projection and the gap', async () => {
    stub()
    renderPage()

    expect(await screen.findByTestId('activity-steps')).toHaveTextContent('2 100')
    expect(screen.getByText(/\/\s*8\s000/, { selector: 'span' })).toBeInTheDocument()
    expect(screen.getByText(/aujourd’hui à 12 h 40/)).toBeInTheDocument()
    expect(screen.getByTestId('activity-projection').textContent).toMatch(/À ce rythme : ~5\s400 \/ 8\s000 à 20 h/)
    expect(screen.getByTestId('activity-gap').textContent).toMatch(/Il manquerait ~2\s600 pas/)
    expect(screen.getAllByTestId('week-bar')).toHaveLength(7)
    expect(screen.getByText('objectif 8 000')).toBeInTheDocument()
  })

  test('lists sessions with Fait and Prévue and cites the planned sport session', async () => {
    stub({
      workouts: [
        workout('1', 'walk', 'watch', 'Marche matinale', 9),
        workout('2', 'bike', 'planned', 'Vélo', 17, { kcal: 90 }),
        workout('3', 'strength', 'planned', 'Haut du corps', 18),
        workout('4', 'run', 'watch', 'Course', 7),
      ],
    })
    renderPage()

    const card = await screen.findByRole('region', { name: 'Sessions actives' })
    expect(within(card).getByText('1 faite · 1 prévue')).toBeInTheDocument()
    const rows = within(card).getAllByTestId('activity-session')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Marche matinale')
    expect(rows[0]).toHaveTextContent('Fait')
    expect(rows[0].textContent).toMatch(/210\skcal/)
    expect(rows[1]).toHaveTextContent('Prévue')
    expect(rows[1]).toHaveTextContent('~90 kcal')
    expect(within(card).getByTestId('activity-sport-session')).toHaveTextContent('Séance prévue · Haut du corps')
    expect(within(card).queryByText('Course')).not.toBeInTheDocument()
  })

  test('Sport › opens Santé › Sport', async () => {
    stub({ workouts: [workout('3', 'strength', 'planned', 'Haut du corps', 18)] })
    renderPage()

    vi.useRealTimers()
    await userEvent.click(await screen.findByRole('button', { name: 'Sport ›' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/workouts')
  })

  test('without a step source shows — and Relier opens Configuration', async () => {
    stub({ profile: { ...PROFILE, stepSource: 'none' }, todaySteps: null })
    renderPage()

    expect(await screen.findByText('Aucune source de pas')).toBeInTheDocument()
    expect(screen.getByTestId('activity-steps')).toHaveTextContent('—')
    vi.useRealTimers()
    await userEvent.click(screen.getByRole('button', { name: 'Relier' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/health/configuration')
  })

  test('steps received show even with no step source chosen', async () => {
    stub({ profile: { ...PROFILE, stepSource: 'none' }, todaySteps: 3000 })
    renderPage()

    expect(await screen.findByTestId('activity-steps')).toHaveTextContent('3 000')
    expect(screen.queryByText('Aucune source de pas')).not.toBeInTheDocument()
  })

  test('loading then error with Réessayer', async () => {
    stub({ fail: true })
    renderPage()

    expect(screen.getByText('Chargement…')).toBeInTheDocument()
    expect(await screen.findByText(/n’a pas pu être chargée/)).toBeInTheDocument()

    stub()
    vi.useRealTimers()
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('activity-steps')).toHaveTextContent('2 100')
  })

  test('Aucune session aujourd’hui', async () => {
    stub()
    renderPage()

    expect(await screen.findByText('Aucune session aujourd’hui')).toBeInTheDocument()
  })

  test('Pas du jour has no title and puts the ring beside « Pas · aujourd’hui à 12 h 40 »', async () => {
    stub()
    renderPage()

    const card = await screen.findByRole('region', { name: 'Pas du jour' })
    expect(within(card).queryByText('Pas du jour')).not.toBeInTheDocument()
    expect(within(card).getByText(/Pas · aujourd’hui à 12 h 40/)).toHaveStyle({ fontSize: '14px' })
    expect(within(card).getByTestId('activity-projection')).toHaveStyle({ fontSize: '15px' })
    expect(card).toHaveStyle({ flexDirection: 'row' })
  })

  test('every card is rounded to 22 px', async () => {
    stub()
    renderPage()

    for (const name of ['Pas du jour', '7 jours', 'Sessions actives']) {
      expect(await screen.findByRole('region', { name })).toHaveStyle({ borderRadius: '22px' })
    }
  })

  test('card paddings are written in px: 20 px, then 14 × 16 px, with 24 px between ring and texts', async () => {
    stub()
    renderPage()

    const steps = await screen.findByRole('region', { name: 'Pas du jour' })
    expect(steps).toHaveStyle({ padding: '20px', gap: '24px' })
    expect(await screen.findByRole('region', { name: '7 jours' })).toHaveStyle({ padding: '14px 16px' })
    expect(await screen.findByRole('region', { name: 'Sessions actives' })).toHaveStyle({ paddingTop: '14px', paddingLeft: '16px' })
  })

  test('Relier is a tinted coral pill of 36 px', async () => {
    stub({ profile: { ...PROFILE, stepSource: 'none' }, todaySteps: null })
    renderPage()

    const relier = await screen.findByRole('button', { name: 'Relier' })
    expect(relier).toHaveStyle({ height: '36px', borderRadius: '999px' })
    expect(relier).not.toHaveClass('MuiButton-outlined')
  })

  test('week bars are coral: today full, at goal 55 %, under 28 %, auj. not bold', async () => {
    stub({ todaySteps: 9000 })
    renderPage()

    const bars = await screen.findAllByTestId('week-bar')
    const fill = (bar: HTMLElement) => getComputedStyle(bar.firstElementChild as Element).backgroundColor
    const today = fill(bars[bars.length - 1])
    expect(today).not.toMatch(/rgba/)
    expect(fill(bars[0])).toMatch(/rgba\(.*,\s*0\.28\)/)
    expect(screen.getByText('auj.')).toHaveStyle({ fontWeight: '400', fontSize: '11px' })
  })

  test('a session row shows its duration under the title and a Fait or Prévue pill', async () => {
    stub({ workouts: [workout('1', 'walk', 'watch', 'Marche matinale', 9), workout('2', 'bike', 'planned', 'Vélo', 17)] })
    renderPage()

    const rows = await screen.findAllByTestId('activity-session')
    expect(within(rows[0]).getByTestId('activity-session-state')).toHaveTextContent('Fait')
    expect(within(rows[0]).getByText('25 min')).toBeInTheDocument()
    expect(within(rows[1]).getByTestId('activity-session-state')).toHaveTextContent('Prévue')
    expect(rows[0]).toHaveStyle({ opacity: '1' })
    expect(rows[1]).toHaveStyle({ opacity: '0.8' })
  })

  test('the sessions column keeps 76 px under it', async () => {
    stub()
    renderPage()

    expect(await screen.findByTestId('activity-sessions-column')).toHaveStyle({ paddingBottom: '76px' })
  })
})

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../../theme'
import { emitMercure } from '../../../test/mercure'
import { cardRegistry } from '../cardRegistry'
import homeConfig from '../home.config'
import { HealthCard } from './HealthCard'
import type { HomeDay } from '../homeDay'

vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

interface Reads {
  workouts: unknown[]
  steps: number | null
  eatenKcal: number
  plannedKcal: number
  fastingProtocol: string | null
}

const base: Reads = { workouts: [], steps: 6240, eatenKcal: 1240, plannedKcal: 1800, fastingProtocol: '16:8' }

const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: () => Promise.resolve(body) })

const stubApi = (reads: () => Reads, status = 200) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (status !== 200) return json({ detail: 'SQLSTATE secret' }, status)
      const now = reads()
      if (url.includes('/workouts')) return json({ member: now.workouts })
      if (url.includes('/health/daily-energy')) return json([{ date: 'd', steps: now.steps }])
      if (url.includes('/health/daily-balance')) return json({ date: 'd', eatenKcal: now.eatenKcal, plannedKcal: now.plannedKcal })
      if (url.includes('/health/profile'))
        return json({ stepSource: 'health_connect', stepGoal: 8000, needKcal: 1980, fastingProtocol: now.fastingProtocol })
      if (url.includes('/health/fasting'))
        return json(
          now.fastingProtocol ? { state: 'open', windowStart: '12:00', windowEnd: '20:00', openedAt: null } : { state: null, windowStart: '12:00', windowEnd: '20:00', openedAt: null },
        )

      return json({}, 404)
    }),
  )

const renderCard = (day: HomeDay = 'today') =>
  render(
    <MemoryRouter>
      <ThemeProvider theme={veilleuseLightTheme}>
        <HealthCard day={day} />
      </ThemeProvider>
    </MemoryRouter>,
  )

const tonight = new Date()
tonight.setHours(19, 0, 0, 0)
const session = { id: 'W1', title: 'Course à pied', startedAt: tonight.toISOString(), durationMinutes: 45, source: 'planned' }

describe('HealthCard', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  test('is declared on the Accueil and registered', () => {
    expect(homeConfig.card?.some((card) => card.key === 'health')).toBe(true)
    expect(cardRegistry.health).toBe(HealthCard)
  })

  test('today: the session, the steps, the balance and the window, titled to open Santé', async () => {
    stubApi(() => ({ ...base, workouts: [session] }))
    renderCard()

    expect(screen.getByTestId('home-health-loading')).toBeInTheDocument()
    expect(await screen.findByTestId('home-health-workout')).toHaveTextContent('Course à pied19:00 · 45 min')
    expect(screen.getByTestId('home-health-steps')).toHaveTextContent('Pas6 240 · objectif 8 000')
    expect(screen.getByTestId('home-health-balance')).toHaveTextContent('Bilan du jour1 240 mangées · besoin 1 980 kcal')
    expect(screen.getByTestId('home-health-fast')).toHaveTextContent('Fenêtre ouverteferme à 20:00')
    expect(screen.getByRole('link', { name: 'Santé' })).toHaveAttribute('href', '/health/suivi')
  })

  test('tomorrow: the planned window and the projected calories, no steps', async () => {
    stubApi(() => ({ ...base, workouts: [session] }))
    renderCard('tomorrow')

    expect(await screen.findByTestId('home-health-workout')).toBeInTheDocument()
    expect(screen.queryByTestId('home-health-steps')).not.toBeInTheDocument()
    expect(screen.getByTestId('home-health-fast')).toHaveTextContent('Fenêtre prévue12:00 – 20:00')
    expect(screen.getByTestId('home-health-balance')).toHaveTextContent('Calories projetées')
  })

  test('without a protocol there is no window line', async () => {
    stubApi(() => ({ ...base, workouts: [session], fastingProtocol: null }))
    renderCard()

    await screen.findByTestId('home-health-workout')
    expect(screen.queryByTestId('home-health-fast')).not.toBeInTheDocument()
  })

  test('a day with nothing is not drawn', async () => {
    stubApi(() => ({ ...base, steps: null, eatenKcal: 0, fastingProtocol: null }))
    renderCard()

    await vi.waitFor(() => expect(screen.queryByTestId('home-health-loading')).not.toBeInTheDocument())
    expect(screen.queryByTestId('home-card-health')).not.toBeInTheDocument()
  })

  test('a failed read says so and retries', async () => {
    stubApi(() => base, 500)
    renderCard()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Impossible de charger cette carte.')
    expect(alert).not.toHaveTextContent('SQLSTATE')

    stubApi(() => ({ ...base, workouts: [session] }))
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))
    expect(await screen.findByTestId('home-health-workout')).toBeInTheDocument()
  })

  test('reads again when a meal is eaten', async () => {
    let eaten = 1240
    stubApi(() => ({ ...base, eatenKcal: eaten }))
    renderCard()
    expect(await screen.findByText(/1 240 mangées/)).toBeInTheDocument()

    eaten = 1500
    await emitMercure('/api/health/food-intakes/{id}', { '@id': '/api/health/food-intakes/I1' })
    expect(await screen.findByText(/1 500 mangées/)).toBeInTheDocument()
  })
})

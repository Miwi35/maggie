import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { emitMercure } from '../../test/mercure'
import { veilleuseLightTheme } from '../../theme'
import { ActivityPage } from './ActivityPage'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

const WALK = {
  id: '/api/workouts/01WALK',
  type: 'walk',
  title: 'Marche du soir',
  source: 'watch',
  startedAt: new Date(2026, 10, 20, 12, 0).toISOString(),
  durationMinutes: 30,
  kcal: 120,
}

describe('ActivityPage — live updates from Mercure', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 20, 12, 40))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('a workout pushed by Mercure appears without reload', async () => {
    let pushed = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const { pathname, searchParams } = new URL(url, 'http://localhost')
        if (pathname === '/api/health/profile') {
          return json({ stepGoal: 8000, suggestionWindowStart: '08:00', suggestionWindowEnd: '20:00', stepSource: 'health_connect' })
        }
        if (pathname === '/api/workouts') return json({ member: pushed ? [WALK] : [] })

        return json([{ date: searchParams.get('to'), steps: 2100 }])
      }),
    )
    render(
      <MemoryRouter>
        <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme}>
          <ActivityPage />
        </AdminContext>
      </MemoryRouter>,
    )
    expect(await screen.findByText('Aucune session aujourd’hui')).toBeInTheDocument()

    pushed = true
    await emitMercure('/api/workouts/{id}', { '@id': '/api/workouts/01WALK' })

    expect(await screen.findByText('Marche du soir')).toBeInTheDocument()
  })
})

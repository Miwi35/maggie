import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { emitMercure } from '../../test/mercure'
import { veilleuseLightTheme } from '../../theme'
import { WeeklyMinutesCard } from './WeeklyMinutesCard'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const at = (month: number, day: number) => new Date(2026, month - 1, day, 12, 0).toISOString()

const WORKOUTS = [
  { id: '/api/workouts/1', startedAt: at(10, 20), durationMinutes: 32, source: 'watch' },
  { id: '/api/workouts/2', startedAt: at(10, 13), durationMinutes: 60, source: 'manual' },
  { id: '/api/workouts/3', startedAt: at(10, 14), durationMinutes: 185, source: 'activity' },
  { id: '/api/workouts/4', startedAt: at(10, 21), durationMinutes: 45, source: 'planned' },
]

function renderCard(listed: unknown[] = WORKOUTS) {
  const getList = vi.fn(() => Promise.resolve({ data: listed, total: listed.length }))
  const dataProvider = testDataProvider({ getList: getList as unknown as DataProvider['getList'] })
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider} theme={veilleuseLightTheme}>
        <WeeklyMinutesCard />
      </AdminContext>
    </MemoryRouter>,
  )

  return { getList }
}

describe('WeeklyMinutesCard', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 21, 15, 0))
  })
  afterEach(() => vi.useRealTimers())

  test('draws the 13 weeks, the minutes of each above its bar and its Monday under it', async () => {
    renderCard()

    const bars = await screen.findAllByRole('img')
    expect(bars).toHaveLength(13)
    expect(screen.getByRole('region', { name: 'Minutes par semaine' })).toBeInTheDocument()
    expect(screen.getByText('13 semaines')).toBeInTheDocument()
    await waitFor(() => expect(bars[11]).toHaveAccessibleName('Semaine du 12 oct. : 245 min'))
    expect(bars[0]).toHaveAccessibleName('Semaine du 27 juil. : 0 min')
    expect(bars[12]).toHaveAccessibleName('Semaine du 19 oct. : 32 min')
    expect(within(bars[11]).getByText('245')).toBeInTheDocument()
    expect(screen.getByText('27 juil.')).toBeInTheDocument()
  })

  test('the current week is the lighter bar and a planned workout counts for nothing', async () => {
    renderCard()

    const current = await screen.findByTestId('weekly-minutes-current')
    await waitFor(() => expect(current).toHaveAccessibleName('Semaine du 19 oct. : 32 min'))
    expect(screen.getAllByTestId('weekly-minutes-bar')).toHaveLength(12)
  })

  test('reads from the Monday twelve weeks back, whatever the table filters', async () => {
    const { getList } = renderCard()

    await screen.findAllByRole('img')
    const [, params] = getList.mock.calls[0] as unknown as [string, { filter: Record<string, string>; pagination: { perPage: number } }]
    expect(params.filter).toEqual({ 'startedAt[after]': new Date(2026, 6, 27).toISOString() })
    expect(params.pagination.perPage).toBe(500)
  })

  test('reads again when a workout changes', async () => {
    const { getList } = renderCard([])
    await screen.findAllByRole('img')
    expect(getList).toHaveBeenCalledTimes(1)

    await emitMercure('/api/workouts/{id}', { '@id': '/api/workouts/9', durationMinutes: 40 })

    await waitFor(() => expect(getList).toHaveBeenCalledTimes(2))
  })
})

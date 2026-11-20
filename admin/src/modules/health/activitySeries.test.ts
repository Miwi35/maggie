import { describe, expect, test } from 'vitest'
import { activeSessions, gapToGoal, hasNoStepSource, isActiveSession, plannedSportSessions, stepProjection, weekBars } from './activitySeries'
import type { Workout } from './workoutFormat'

const at = (hour: number, minute = 0) => new Date(2026, 10, 20, hour, minute)
const window = { windowStart: '08:00', windowEnd: '20:00' }

const workout = (type: Workout['type'], source: Workout['source'], startedAt = '2026-11-20T09:00:00+01:00'): Workout => ({
  id: `/api/workouts/${type}-${source}`,
  type,
  title: type,
  startedAt,
  durationMinutes: 25,
  source,
})

describe('stepProjection', () => {
  test('projects 2 100 steps at 12:40 to ~5 400 by 20 h', () => {
    const result = stepProjection({ steps: 2100, now: at(12, 40), ...window })

    expect(result).toEqual({ projected: 5400, endLabel: '20 h' })
    expect(gapToGoal(result.projected, 8000)).toBe(2600)
  })

  test('no projection before an hour into the window', () => {
    expect(stepProjection({ steps: 300, now: at(8, 59), ...window }).projected).toBeNull()
    expect(stepProjection({ steps: 0, now: at(7, 0), ...window }).projected).toBeNull()
  })

  test('after the window the projection is the day\'s steps', () => {
    expect(stepProjection({ steps: 6234, now: at(21, 0), ...window }).projected).toBe(6234)
  })

  test('no gap line once the projection reaches the goal', () => {
    expect(gapToGoal(8000, 8000)).toBeNull()
    expect(gapToGoal(9100, 8000)).toBeNull()
    expect(gapToGoal(null, 8000)).toBeNull()
    expect(gapToGoal(6200, 8000)).toBe(1800)
  })
})

describe('sessions', () => {
  test('walk and bike are active sessions, a planned strength session is cited, a done run is left out', () => {
    const all = [
      workout('walk', 'watch', '2026-11-20T10:00:00+01:00'),
      workout('bike', 'planned', '2026-11-20T08:00:00+01:00'),
      workout('strength', 'planned'),
      workout('run', 'watch'),
    ]

    expect(isActiveSession(all[3])).toBe(false)
    expect(activeSessions(all).map((w) => w.type)).toEqual(['bike', 'walk'])
    expect(plannedSportSessions(all).map((w) => w.type)).toEqual(['strength'])
  })
})

describe('hasNoStepSource', () => {
  test('no step source only without steps today', () => {
    expect(hasNoStepSource({ stepSource: 'none' }, null)).toBe(true)
    expect(hasNoStepSource({ stepSource: 'none' }, 1200)).toBe(false)
    expect(hasNoStepSource({ stepSource: 'health_connect' }, null)).toBe(false)
  })
})

describe('weekBars', () => {
  test('seven bars from J-6 to auj. with the goal line', () => {
    const rows = [
      { date: '2026-11-20', steps: 4380 },
      { date: '2026-11-19', steps: 9000 },
    ].map((row) => ({ ...row, activeKcal: null, bmrKcal: null, workoutKcal: null, stepsKcal: null, expendedKcal: null }))
    const { bars, goalHeight } = weekBars(rows, 8000, '2026-11-20')

    expect(bars).toHaveLength(7)
    expect(bars[0].date).toBe('2026-11-14')
    expect(bars[6]).toMatchObject({ label: 'auj.', today: true, atGoal: false })
    expect(bars[5]).toMatchObject({ atGoal: true, steps: 9000 })
    expect(bars[0].steps).toBeNull()
    // The scale is 1.5 × the goal (12 000), or the tallest day beyond it.
    expect(goalHeight).toBeCloseTo((8000 / 12000) * 100)
    expect(weekBars(rows.map((r) => ({ ...r, steps: 24000 })), 8000, '2026-11-20').goalHeight).toBeCloseTo((8000 / 24000) * 100)
  })
})

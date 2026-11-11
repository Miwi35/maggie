import { describe, expect, test } from 'vitest'
import { composeHealthState, isHealthEmpty } from './healthCardState'
import type { HealthReads } from './healthCardState'
import type { HealthProfile } from '../../health/healthProfileApi'
import type { FastingDay } from '../../health/fasting'
import type { Workout } from '../../health/workoutFormat'

const profile = { stepSource: 'health_connect', stepGoal: 8000, needKcal: 1980, fastingProtocol: '16:8' } as HealthProfile
const fasting = { state: 'open', windowStart: '12:00', windowEnd: '20:00', protocol: '16:8' } as FastingDay
const planned = { id: 'W1', title: 'Course', startedAt: '2026-11-11T18:30:00+01:00', durationMinutes: 45, source: 'planned' } as Workout

const reads = (extra: Partial<HealthReads> = {}): HealthReads => ({
  workouts: [planned],
  energy: { date: '2026-11-11', steps: 6240 } as HealthReads['energy'],
  balance: { eatenKcal: 1240, plannedKcal: 1800 } as HealthReads['balance'],
  profile,
  fasting,
  ...extra,
})

const at = (h: number, m: number) => new Date(2026, 10, 11, h, m)

describe('composeHealthState', () => {
  test('today: session, steps, balance and the window with its countdown', () => {
    const state = composeHealthState('today', reads(), at(16, 42))

    expect(state.workout).toMatchObject({ id: 'W1', title: 'Course', durationMinutes: 45 })
    expect(state.steps).toEqual({ count: 6240, goal: 8000 })
    expect(state.balance).toEqual({ kcal: 1240, needKcal: 1980 })
    expect(state.fast).toEqual({ state: 'open', windowStart: '12:00', windowEnd: '20:00', minutesToEdge: 198 })
  })

  test('tomorrow: no steps, the planned calories, the planned window without a countdown', () => {
    const state = composeHealthState('tomorrow', reads({ fasting: { ...fasting, state: null } }), at(16, 42))

    expect(state.steps).toBeNull()
    expect(state.fast).toMatchObject({ windowStart: '12:00', windowEnd: '20:00' })
    expect(state.balance).toEqual({ kcal: 1800, needKcal: 1980 })
    expect(state.fast?.minutesToEdge).toBeNull()
  })

  test('without a fasting protocol there is no window line', () => {
    const state = composeHealthState('today', reads({ profile: { ...profile, fastingProtocol: null }, fasting: { ...fasting, state: null } }))

    expect(state.fast).toBeNull()
  })

  test('without a step source, or a measure, there is no steps line', () => {
    expect(composeHealthState('today', reads({ profile: { ...profile, stepSource: 'none' } })).steps).toBeNull()
    expect(composeHealthState('today', reads({ energy: { date: 'd', steps: null } as HealthReads['energy'] })).steps).toBeNull()
  })

  test('only a planned session counts, the earliest first', () => {
    const watch = { ...planned, id: 'W0', source: 'watch' } as Workout
    const later = { ...planned, id: 'W2', startedAt: '2026-11-11T20:00:00+01:00' } as Workout

    expect(composeHealthState('today', reads({ workouts: [watch, later, planned] })).workout?.id).toBe('W1')
  })

  test('a day with nothing is empty', () => {
    const state = composeHealthState('today', { workouts: [], energy: null, balance: null, profile: null, fasting: null })

    expect(isHealthEmpty(state)).toBe(true)
  })

  test('the window opens in the evening: counts to the opening', () => {
    const state = composeHealthState('today', reads({ fasting: { ...fasting, state: 'fasting' } }), at(9, 30))

    expect(state.fast?.minutesToEdge).toBe(150)
  })
})

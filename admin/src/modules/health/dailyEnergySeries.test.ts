import { describe, expect, test } from 'vitest'
import { averageSteps, energyDayLabel, energyParts, energyRange, hasSteps, maxStack, parisDay } from './dailyEnergySeries'
import type { DailyEnergy } from './dailyEnergySeries'

const day = (date: string, over: Partial<DailyEnergy> = {}): DailyEnergy => ({
  date,
  steps: null,
  activeKcal: null,
  bmrKcal: null,
  workoutKcal: 0,
  stepsKcal: null,
  expendedKcal: null,
  ...over,
})

describe('dailyEnergySeries', () => {
  test('parisDay is the Paris day, not the UTC one', () => {
    expect(parisDay(new Date('2026-10-21T22:30:00Z'))).toBe('2026-10-22')
    expect(parisDay(new Date('2026-10-21T10:00:00Z'))).toBe('2026-10-21')
  })

  test.each([
    [7, '2026-10-15'],
    [30, '2026-09-22'],
    [90, '2026-07-24'],
    [365, '2025-10-22'],
  ] as const)('energyRange of %i days ends today and starts n-1 days before', (period, from) => {
    expect(energyRange(period, new Date('2026-10-21T10:00:00Z'))).toEqual({ from, to: '2026-10-21' })
  })

  test('hasSteps and averageSteps ignore the days without data', () => {
    const rows = [day('2026-10-19'), day('2026-10-20', { steps: 8000 }), day('2026-10-21', { steps: 5000 })]
    expect(hasSteps(rows)).toBe(true)
    expect(averageSteps(rows)).toBe(6500)
    expect(hasSteps([day('2026-10-19')])).toBe(false)
    expect(averageSteps([day('2026-10-19')])).toBeNull()
  })

  test('energyParts counts a missing part as nothing; maxStack is the tallest stack', () => {
    expect(energyParts(day('2026-10-19', { stepsKcal: 120, workoutKcal: 300 }))).toEqual({ base: 0, steps: 120, workouts: 300 })
    const rows = [day('a', { bmrKcal: 1600, stepsKcal: 100 }), day('b', { bmrKcal: 1600, stepsKcal: 200, workoutKcal: 300 }), day('c')]
    expect(maxStack(rows)).toBe(2100)
    expect(maxStack([])).toBe(0)
  })

  test('energyDayLabel says Aujourd’hui for today, the weekday otherwise', () => {
    expect(energyDayLabel(day('2026-10-21'), '2026-10-21')).toBe('Aujourd’hui')
    expect(energyDayLabel(day('2026-10-20'), '2026-10-21')).toBe('mar. 20 oct.')
  })
})

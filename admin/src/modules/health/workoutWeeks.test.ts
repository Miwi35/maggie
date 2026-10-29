import { describe, expect, test } from 'vitest'
import { mondayOf, weeklyMinutes, weeksStart } from './workoutWeeks'

const at = (month: number, day: number, hour = 12, minute = 0) => new Date(2026, month - 1, day, hour, minute).toISOString()
const run = (startedAt: string, durationMinutes: number, source = 'manual') => ({ startedAt, durationMinutes, source })

// Wednesday 21 October 2026.
const NOW = new Date(2026, 9, 21, 15, 0)

describe('weeklyMinutes', () => {
  test('a week runs from Monday 00:00 to Sunday 23:59, local time', () => {
    expect(mondayOf(new Date(2026, 9, 25, 23, 59)).getTime()).toBe(new Date(2026, 9, 19).getTime())
    expect(mondayOf(new Date(2026, 9, 26, 0, 0)).getTime()).toBe(new Date(2026, 9, 26).getTime())

    const weeks = weeklyMinutes(
      [run(at(10, 19, 0, 0), 10), run(at(10, 25, 23, 59), 20), run(at(10, 12, 23, 59), 40), run(at(10, 26, 0, 0), 80)],
      new Date(2026, 9, 21),
    )

    const current = weeks[12]
    expect(current.minutes).toBe(30)
    expect(weeks[11].minutes).toBe(40)
  })

  test('draws 13 weeks ending with the current one, the empty ones at 0', () => {
    const weeks = weeklyMinutes([run(at(10, 20), 32)], NOW)

    expect(weeks).toHaveLength(13)
    expect(weeks.map((week) => week.label).slice(0, 2)).toEqual(['27 juil.', '3 août'])
    expect(weeks[12].label).toBe('19 oct.')
    expect(weeks.filter((week) => week.minutes > 0)).toHaveLength(1)
    expect(weeks.filter((week) => week.current)).toHaveLength(1)
    expect(weeks[12].current).toBe(true)
    expect(weeks[0].minutes).toBe(0)
  })

  test('starts on the Monday twelve weeks before the current one', () => {
    expect(weeksStart(NOW).getTime()).toBe(new Date(2026, 6, 27).getTime())
  })

  test('a planned workout is not done: it counts for nothing', () => {
    const weeks = weeklyMinutes([run(at(10, 21), 45, 'planned'), run(at(10, 20), 30, 'watch')], NOW)

    expect(weeks[12].minutes).toBe(30)
  })

  test('sums the workouts of a week and ignores what is before the first week', () => {
    const weeks = weeklyMinutes([run(at(10, 13), 60), run(at(10, 18), 65), run(at(7, 20), 99)], NOW)

    expect(weeks[11].minutes).toBe(125)
    expect(weeks.reduce((sum, week) => sum + week.minutes, 0)).toBe(125)
  })

  test('the first of the month is « 1er »', () => {
    const weeks = weeklyMinutes([], new Date(2026, 5, 3))

    expect(weeks.map((week) => week.label)).toContain('1er juin')
  })
})

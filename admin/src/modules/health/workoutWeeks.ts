import { formatShortDate } from './workoutFormat'

/** How many weeks the chart draws, the current one included. */
export const WEEKS_SHOWN = 13

export interface WeekBar {
  /** Monday 00:00, local time. */
  start: Date
  /** « 28 juil. », « 1er sept. ». */
  label: string
  minutes: number
  current: boolean
}

interface Counted {
  startedAt: string
  durationMinutes: number
  source: string
}

/** Monday 00:00 of the ISO week `date` falls in, in local time. */
export function mondayOf(date: Date): Date {
  const monday = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7))

  return monday
}

/** Where the chart's read starts: the Monday of the week 12 weeks before the current one. */
export function weeksStart(now: Date): Date {
  const start = mondayOf(now)
  start.setDate(start.getDate() - 7 * (WEEKS_SHOWN - 1))

  return start
}

/**
 * Minutes done per ISO week (Monday to Sunday, local time), the 13 weeks ending
 * with the current one, empty weeks as 0. A planned workout is not done: it
 * counts for nothing.
 */
export function weeklyMinutes(workouts: Counted[], now: Date): WeekBar[] {
  const first = weeksStart(now)
  const weeks: WeekBar[] = Array.from({ length: WEEKS_SHOWN }, (_, index) => {
    const start = new Date(first)
    start.setDate(start.getDate() + 7 * index)

    return { start, label: formatShortDate(start), minutes: 0, current: index === WEEKS_SHOWN - 1 }
  })

  const end = new Date(weeks[WEEKS_SHOWN - 1].start)
  end.setDate(end.getDate() + 7)

  for (const workout of workouts) {
    if (workout.source === 'planned') continue
    const at = new Date(workout.startedAt).getTime()
    if (at < first.getTime() || at >= end.getTime()) continue
    // Weeks start at local midnight, whose offset changes with daylight saving: compare instants, not day counts.
    const week = weeks.findLast((candidate) => at >= candidate.start.getTime())
    if (week) week.minutes += workout.durationMinutes
  }

  return weeks
}

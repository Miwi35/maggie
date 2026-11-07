import { addDays, dayKey, formatDay, NONE, startOfDay, TODAY_LABEL } from './suiviSeries'
import type { Period } from './suiviSeries'

/**
 * The cards « Pas » and « Énergie » of Santé › Suivi (MAG-746), read from
 * `GET /api/health/daily-energy`: one row per local day, the figures as the
 * API gives them — the total is never recomputed here, it must read the same
 * as on the phone.
 */

export interface DailyEnergy {
  /** `2026-10-21`, the Europe/Paris day. */
  date: string
  steps: number | null
  activeKcal: number | null
  bmrKcal: number | null
  workoutKcal: number | null
  stepsKcal: number | null
  expendedKcal: number | null
}

export const DAILY_ENERGY_PATH = '/api/health/daily-energy'

const PARIS_DAY = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Paris', year: 'numeric', month: '2-digit', day: '2-digit' })

/** `2026-10-21`: the Europe/Paris day `now` falls on. */
export const parisDay = (now: Date): string => PARIS_DAY.format(now)

const dateOf = (key: string): Date => {
  const [year, month, day] = key.split('-').map(Number)

  return new Date(year, month - 1, day)
}

/** The days to read: `n` days ending today, in Paris. */
export function energyRange(period: Period, now: Date): { from: string; to: string } {
  const to = parisDay(now)

  return { from: dayKey(addDays(startOfDay(dateOf(to)), -(period - 1))), to }
}

/** « mer. 21 oct. », « Aujourd’hui » for `today`. */
export const energyDayLabel = (row: DailyEnergy, today: string): string => (row.date === today ? TODAY_LABEL : formatDay(dateOf(row.date)))

/** Whether any day carries steps: without, there is no source and the card stays empty. */
export const hasSteps = (rows: DailyEnergy[]): boolean => rows.some((row) => row.steps !== null)

/** The mean of the days that have steps, rounded; null when none does. */
export function averageSteps(rows: DailyEnergy[]): number | null {
  const counted = rows.filter((row) => row.steps !== null)
  if (counted.length === 0) return null

  return Math.round(counted.reduce((sum, row) => sum + (row.steps ?? 0), 0) / counted.length)
}

/** The three parts of a day's bar; a part that does not exist counts for nothing. */
export const energyParts = (row: DailyEnergy): { base: number; steps: number; workouts: number } => ({
  base: row.bmrKcal ?? 0,
  steps: row.stepsKcal ?? 0,
  workouts: row.workoutKcal ?? 0,
})

/** The height of the tallest stack, the scale of the bars. */
export const maxStack = (rows: DailyEnergy[]): number =>
  Math.max(0, ...rows.map((row) => Object.values(energyParts(row)).reduce((sum, part) => sum + part, 0)))

/** « — » for a missing figure, the integer otherwise. */
export const orNone = (value: number | null, format: (n: number) => string): string => (value === null ? NONE : format(value))

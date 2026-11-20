import { dayKey } from './suiviSeries'
import type { DailyEnergy } from './dailyEnergySeries'
import { parisDay } from './dailyEnergySeries'
import type { HealthProfile } from './healthProfileApi'
import { parisClock } from './nutritionBalance'
import type { Workout } from './workoutFormat'

/**
 * Santé › Activité (MAG-897): the steps of the day against the goal with their
 * projection, the day's active sessions and the last seven days — pure
 * functions, the same rules as on Android.
 */

const ACTIVE_TYPES: readonly Workout['type'][] = ['walk', 'bike']

/** A walk or a bike ride is an active session; every other type belongs to Sport. */
export const isActiveSession = (workout: Workout): boolean => ACTIVE_TYPES.includes(workout.type)

const byStart = (a: Workout, b: Workout): number => a.startedAt.localeCompare(b.startedAt)

/** The day's walks and rides, done or planned, in the day's order. */
export const activeSessions = (workouts: Workout[]): Workout[] => workouts.filter(isActiveSession).sort(byStart)

/** The planned sessions of Sport (run, strength…), only cited on this page. */
export const plannedSportSessions = (workouts: Workout[]): Workout[] =>
  workouts.filter((workout) => workout.source === 'planned' && !isActiveSession(workout)).sort(byStart)

export const roundHundred = (value: number): number => Math.round(value / 100) * 100

const minutesOf = (clock: string): number => {
  const [hours, minutes] = clock.split(':').map(Number)

  return hours * 60 + minutes
}

/** « 20 h », « 20 h 30 »: a `HH:MM` clock as the card spells it. */
export const spokenClock = (clock: string): string => {
  const [hours, minutes] = clock.split(':').map(Number)

  return minutes === 0 ? `${hours} h` : `${hours} h ${String(minutes).padStart(2, '0')}`
}

interface ProjectionInput {
  steps: number
  now: Date
  /** `08:00`. */
  windowStart: string
  /** `20:00`. */
  windowEnd: string
}

/**
 * The day's steps carried to the window's end at the pace so far, to the
 * hundred. Null in the first hour of the window (too early to tell); after the
 * window's end, the day's steps.
 */
export function stepProjection({ steps, now, windowStart, windowEnd }: ProjectionInput): { projected: number | null; endLabel: string } {
  const endLabel = spokenClock(windowEnd)
  const start = minutesOf(windowStart)
  const end = minutesOf(windowEnd)
  const current = minutesOf(parisClock(now))

  if (current >= end) return { projected: steps, endLabel }
  if (current - start < 60) return { projected: null, endLabel }

  return { projected: roundHundred((steps / (current - start)) * (end - start)), endLabel }
}

/** What is missing to the goal at that pace, to the hundred; null when the projection reaches it (or is unknown). */
export const gapToGoal = (projected: number | null, goal: number): number | null =>
  projected !== null && projected < goal ? roundHundred(goal - projected) : null

/** No source: none chosen and no step received today. Steps received count whatever the setting says. */
export const hasNoStepSource = (profile: Pick<HealthProfile, 'stepSource'>, todaySteps: number | null): boolean =>
  profile.stepSource === 'none' && todaySteps === null

export interface WeekBar {
  date: string
  /** « ven. », « auj. » for today. */
  label: string
  steps: number | null
  /** 0–100, of the scale. */
  height: number
  atGoal: boolean
  today: boolean
}

const WEEKDAY = new Intl.DateTimeFormat('fr-FR', { weekday: 'short' })

const dateOf = (key: string): Date => {
  const [year, month, day] = key.split('-').map(Number)

  return new Date(year, month - 1, day)
}

/** The seven bars, J-6 to today; the scale is 1.5 × the goal, or the tallest day if it is higher. */
export function weekBars(rows: DailyEnergy[], goal: number, today: string): { bars: WeekBar[]; goalHeight: number } {
  const first = dateOf(today)
  const keys = Array.from({ length: 7 }, (_, index) => {
    const day = new Date(first.getFullYear(), first.getMonth(), first.getDate() - (6 - index))

    return dayKey(day)
  })
  const steps = (date: string): number | null => rows.find((row) => row.date === date)?.steps ?? null
  const scale = Math.max(goal * 1.5, ...keys.map((date) => steps(date) ?? 0))
  const height = (value: number): number => (scale > 0 ? (value / scale) * 100 : 0)

  return {
    bars: keys.map((date) => ({
      date,
      label: date === today ? 'auj.' : WEEKDAY.format(dateOf(date)),
      steps: steps(date),
      height: height(steps(date) ?? 0),
      atGoal: (steps(date) ?? 0) >= goal && goal > 0,
      today: date === today,
    })),
    goalHeight: height(goal),
  }
}

/** The 7 days to read, ending today in Paris. */
export const weekRange = (now: Date): { from: string; to: string } => {
  const to = parisDay(now)
  const first = dateOf(to)

  return { from: dayKey(new Date(first.getFullYear(), first.getMonth(), first.getDate() - 6)), to }
}

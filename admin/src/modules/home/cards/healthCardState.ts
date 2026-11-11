import type { FastingDay } from '../../health/fasting'
import type { DailyBalance } from '../../health/nutritionBalance'
import type { DailyEnergy } from '../../health/dailyEnergySeries'
import type { HealthProfile } from '../../health/healthProfileApi'
import type { Workout } from '../../health/workoutFormat'
import type { HomeDay } from '../homeDay'

export interface HealthWorkout {
  id: string
  title: string
  /** `18:30`, the local time the session starts. */
  time: string
  durationMinutes: number
}

export interface HealthSteps {
  count: number
  goal: number
}

export interface HealthBalance {
  /** What was eaten today, or what is planned for tomorrow. */
  kcal: number
  needKcal: number | null
}

export interface HealthFast {
  /** Null for tomorrow: the API reads no state for a day to come. */
  state: 'fasting' | 'open' | 'closed' | null
  windowStart: string
  windowEnd: string
  /** Minutes to the window's next edge, today only; null for another day or a closed window. */
  minutesToEdge: number | null
}

export interface HealthHomeState {
  day: HomeDay
  workout: HealthWorkout | null
  steps: HealthSteps | null
  balance: HealthBalance | null
  fast: HealthFast | null
}

export interface HealthReads {
  workouts: Workout[]
  energy: DailyEnergy | null
  balance: DailyBalance | null
  profile: HealthProfile | null
  fasting: FastingDay | null
}

const minutesOf = (time: string): number => {
  const [hours, minutes] = time.split(':').map(Number)

  return hours * 60 + minutes
}

const hhmm = (instant: Date): string =>
  `${String(instant.getHours()).padStart(2, '0')}:${String(instant.getMinutes()).padStart(2, '0')}`

/** Minutes to the edge the window faces now; null when it is closed. `now` is `14:30`. */
function minutesToEdge(fasting: FastingDay, now: string): number | null {
  if (fasting.state === 'closed' || fasting.state === null) return null
  const start = minutesOf(fasting.windowStart)
  let end = minutesOf(fasting.windowEnd)
  if (end <= start) end += 24 * 60
  let at = minutesOf(now)
  if (at < start && at + 24 * 60 < end) at += 24 * 60
  const edge = fasting.state === 'open' ? end : start

  return Math.max(0, (edge < at ? edge + 24 * 60 : edge) - at)
}

/**
 * The card's state from the module's resources. Today: the session, the steps (only with a source and a
 * measure), what was eaten against the need, the window. Tomorrow: the session, the window planned, the
 * calories planned — no steps. A protocol-less profile has no window line.
 */
export function composeHealthState(day: HomeDay, reads: HealthReads, now: Date = new Date()): HealthHomeState {
  const today = day === 'today'
  const planned = [...reads.workouts]
    .filter((workout) => workout.source === 'planned')
    .sort((a, b) => a.startedAt.localeCompare(b.startedAt))[0]
  const profile = reads.profile
  const fasting = reads.fasting

  const steps =
    today && profile && profile.stepSource !== 'none' && reads.energy?.steps != null && reads.energy.steps > 0
      ? { count: reads.energy.steps, goal: profile.stepGoal }
      : null

  const kcal = reads.balance ? (today ? reads.balance.eatenKcal : reads.balance.plannedKcal) : 0

  return {
    day,
    workout: planned
      ? { id: planned.id, title: planned.title, time: hhmm(new Date(planned.startedAt)), durationMinutes: planned.durationMinutes }
      : null,
    steps,
    balance: kcal > 0 ? { kcal, needKcal: profile?.needKcal ?? null } : null,
    fast:
      profile?.fastingProtocol && fasting && fasting.windowStart && fasting.windowEnd && (!today || fasting.state)
        ? {
            state: today ? fasting.state : null,
            windowStart: fasting.windowStart,
            windowEnd: fasting.windowEnd,
            minutesToEdge: today ? minutesToEdge(fasting, hhmm(now)) : null,
          }
        : null,
  }
}

/** Nothing to show for the day: the card is not drawn (MAG-594). */
export const isHealthEmpty = (state: HealthHomeState): boolean =>
  !state.workout && !state.steps && !state.balance && !state.fast

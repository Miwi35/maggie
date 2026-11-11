import { useRead } from './nutritionApi'
import type { FastingProtocol } from './healthProfileApi'
import type { HealthDay } from './healthDaysApi'
import { parisClock, shiftDay } from './nutritionBalance'
import { formatDuration } from './workoutFormat'

/**
 * Santé › Nutrition, the fast (MAG-770): the day's eating window as the API
 * gives it (`GET /api/health/fasting?date=`), and the night fasts of the last
 * 14 days (`nightFastMinutes` of `GET /api/health/days`). The window, its
 * state and the night fast are the API's; the page only counts down to the
 * window's edge.
 */

export const FASTING_PATH = '/api/health/fasting'

export type FastingState = 'fasting' | 'open' | 'closed'

export interface FastingDay {
  date: string
  protocol: FastingProtocol | null
  /** `12:00`. */
  windowStart: string
  /** `20:00`: start + the window's hours, may be past midnight. */
  windowEnd: string
  /** ISO-8601 of the day's first intake; null before it. */
  openedAt: string | null
  lastIntakeAt: string | null
  /** Null when the protocol is. */
  state: FastingState | null
  nightFastMinutes: number | null
}

/** How many days « Jeûnes · 14 jours » shows: the day and the 13 before it. */
export const FAST_DAYS = 14

/** The value of the « Protocole » select that means no fasting. */
export const NO_FASTING = 'none'
export const NO_FASTING_LABEL = 'Aucun'

const asFasting = (body: unknown): FastingDay => {
  if (typeof body !== 'object' || body === null || !('windowEnd' in body)) throw new Error('not a fasting day')

  return body as FastingDay
}

/** The fast of one day. */
export const useFasting = (day: string) => useRead(`${FASTING_PATH}?date=${day}`, asFasting)

/** The hours of fasting of a protocol: 16 for « 16:8 ». */
export const fastHours = (protocol: FastingProtocol): number => Number(protocol.split(':')[0])

/** « 12 h 10 » for `12:10`. */
export const clockLabel = (time: string): string => {
  const [hours, minutes] = time.split(':')

  return `${Number(hours)} h ${minutes}`
}

const minutesOf = (time: string): number => {
  const [hours, minutes] = time.split(':').map(Number)

  return hours * 60 + minutes
}

/**
 * The header of the « Jeûne » card: « fenêtre ouverte · 2 h 20 restantes »,
 * « jeûne en cours · 1 h 10 avant l’ouverture », « fenêtre fermée ». `now` is
 * the Paris time (`14:30`) when the day is today, null for another day: no
 * countdown then.
 */
export function fastingStatus(fasting: FastingDay, now: string | null): string {
  if (fasting.state === null) return ''
  if (fasting.state === 'closed') return 'fenêtre fermée'
  if (now === null) return fasting.state === 'open' ? 'fenêtre ouverte' : 'jeûne en cours'

  const start = minutesOf(fasting.windowStart)
  let end = minutesOf(fasting.windowEnd)
  if (end <= start) end += 24 * 60
  let at = minutesOf(now)
  // After midnight, inside a window opened the evening before.
  if (at < start && at + 24 * 60 < end) at += 24 * 60

  if (at < start) return `jeûne en cours · ${formatDuration(start - at)} avant l’ouverture`
  if (at < end) return `fenêtre ouverte · ${formatDuration(end - at)} restantes`

  return 'fenêtre fermée'
}

/** « 12 h 10 → 20 h 00 »: from the first intake (or the window's start) to the window's end. */
export const windowLabel = (fasting: FastingDay): string =>
  `${clockLabel(fasting.openedAt === null ? fasting.windowStart : parisClock(new Date(fasting.openedAt)))} → ${clockLabel(fasting.windowEnd)}`

/** « 16 h 05 », « — » without a night fast. */
export const nightFastLabel = (minutes: number | null): string => (minutes === null ? '—' : formatDuration(minutes))

export interface FastBar {
  date: string
  minutes: number | null
}

/** One bar per day of the 14 ending on `day`, oldest first; a day the journal does not hold has none. */
export function fastBars(days: HealthDay[], day: string): FastBar[] {
  const byDate = new Map(days.map((row) => [row.date, row.nightFastMinutes ?? null]))

  return Array.from({ length: FAST_DAYS }, (_, index) => {
    const date = shiftDay(day, index - (FAST_DAYS - 1))

    return { date, minutes: byDate.get(date) ?? null }
  })
}

/** The top of the chart, in hours: 20, or more for a longer fast. */
export const fastScale = (bars: FastBar[], protocol: FastingProtocol): number =>
  Math.max(20, fastHours(protocol), ...bars.map((bar) => Math.ceil((bar.minutes ?? 0) / 60)))

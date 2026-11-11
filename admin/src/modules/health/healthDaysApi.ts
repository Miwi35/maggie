import { apiFetch } from './healthProfileApi'
import { useRead } from './nutritionApi'

/**
 * Santé › Journal (MAG-750): one entry per day, read from `GET /api/health/days`
 * (only the days the journal holds, oldest first) and `GET /api/health/days/{date}`
 * (an unknown day answers an empty entry, `id` null). The figures are the API's —
 * the first weighing of the day, the steps, the balance (today's live, planned
 * meals included), the minutes of sport —; mood, fatigue and the note are typed
 * here through `PATCH /api/health/days/{date}`.
 */

export const HEALTH_DAYS_PATH = '/api/health/days'

/** The day is its own Mercure key: one array, so the page opens one stream (MAG-473). */
export const HEALTH_DAY_TOPICS = ['/api/health/days/{date}']

export const MIN_SCORE = 1
export const MAX_SCORE = 10

export type NotedBy = 'user' | 'maggie'

export interface HealthDay {
  id: string | null
  /** `2026-11-07`, the Europe/Paris day. */
  date: string
  weightKg: number | null
  bodyFatPercent: number | null
  /** When the day's first weighing was taken. */
  weighedAt: string | null
  steps: number | null
  eatenKcal: number | null
  expendedKcal: number | null
  balanceKcal: number | null
  sportMinutes: number | null
  /** The night's fast (MAG-770): the day before's last intake to the day's first, in minutes; null when one is missing. */
  nightFastMinutes?: number | null
  mood: number | null
  fatigue: number | null
  note: string | null
  notedBy: NotedBy | null
  notedAt: string | null
}

/** What a PATCH may carry: a score or the note; null clears it. */
export type HealthDayPatch = Partial<Pick<HealthDay, 'mood' | 'fatigue' | 'note'>>

export type HealthDayWrite = { ok: true; day: HealthDay } | { ok: false }

const asDay = (body: unknown): HealthDay => {
  if (typeof body !== 'object' || body === null || !('date' in body)) throw new Error('not a day')

  return body as HealthDay
}

const asDays = (body: unknown): HealthDay[] => {
  const days = (body as { days?: unknown } | null)?.days
  if (!Array.isArray(days)) throw new Error('not a range')

  return days as HealthDay[]
}

/** The days of `from`..`to` the journal holds, oldest first. */
export const useHealthDays = (from: string, to: string) => useRead(`${HEALTH_DAYS_PATH}?from=${from}&to=${to}`, asDays)

/** One day, empty when the journal does not hold it yet. */
export const useHealthDay = (date: string) => useRead(`${HEALTH_DAYS_PATH}/${date}`, asDay)

/** Writes mood, fatigue or the note of `date`; the answer is the whole entry. A refusal (400) carries an English `error`, never shown. */
export async function patchHealthDay(date: string, patch: HealthDayPatch): Promise<HealthDayWrite> {
  try {
    const res = await apiFetch(`${HEALTH_DAYS_PATH}/${date}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    })
    if (!res.ok) return { ok: false }

    return { ok: true, day: asDay(await res.json()) }
  } catch {
    return { ok: false }
  }
}

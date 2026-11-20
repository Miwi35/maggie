import { formatInteger } from './healthProfileApi'
import type { HealthDay } from './healthDaysApi'
import { formatSignedKcal, parisClock, shiftDay } from './nutritionBalance'
import { formatOne, NONE, TODAY_LABEL } from './suiviSeries'

/**
 * How Santé › Journal (MAG-750) and the « Moral · fatigue » card of Santé ›
 * Suivi write a day: every figure as the API gives it, « — » when absent.
 */

const LONG_DAY = new Intl.DateTimeFormat('fr-FR', { timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'short' })

/** « samedi 7 nov. ». */
export const longDay = (key: string): string => LONG_DAY.format(new Date(`${key}T12:00:00Z`))

/** « Aujourd’hui · samedi 7 nov. », « vendredi 6 nov. ». */
export const journalDayLabel = (key: string, today: string): string => (key === today ? `${TODAY_LABEL} · ${longDay(key)}` : longDay(key))

const SHORT_DAY = new Intl.DateTimeFormat('fr-FR', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short' })

/** « sam. 7 nov. ». */
export const journalShortDay = (key: string): string => SHORT_DAY.format(new Date(`${key}T12:00:00Z`))

const or = <T>(value: T | null, format: (value: T) => string): string => (value === null ? NONE : format(value))

export const formatWeight = (weightKg: number | null): string => or(weightKg, (kg) => `${formatOne(kg)} kg`)
export const formatSteps = (steps: number | null): string => or(steps, formatInteger)
export const formatBalance = (balanceKcal: number | null): string => or(balanceKcal, formatSignedKcal)
/** The balance without its unit, « −312 », for the table's column. */
export const formatBalanceFigure = (balanceKcal: number | null): string => or(balanceKcal, (kcal) => formatSignedKcal(kcal).replace(/\s*kcal$/, ''))
export const formatMinutes = (minutes: number | null): string => or(minutes, (value) => `${formatInteger(value)} min`)
export const formatScore = (score: number | null): string => or(score, String)
/** « 07:12 », the Paris time of an instant. */
export const formatClock = (instant: string | null): string => or(instant, (at) => parisClock(new Date(at)))

/** « première pesée · 07:12 », « pas de pesée » without a weight. */
export function journalWeighing(day: HealthDay): string {
  if (day.weightKg === null) return 'pas de pesée'

  return day.weighedAt === null ? 'première pesée' : `première pesée · ${formatClock(day.weighedAt)}`
}

/** « 1 580 · 1 900 » (eaten · expended), nothing unless both are known. */
export const journalIntake = (day: HealthDay): string | undefined =>
  day.eatenKcal === null || day.expendedKcal === null ? undefined : `${formatInteger(day.eatenKcal)} · ${formatInteger(day.expendedKcal)}`

/** How much of a note the table shows. */
export const NOTE_PREVIEW = 40

/** The note cut to its first characters, « … » when longer. */
export function notePreview(note: string | null): string {
  if (note === null || note.trim() === '') return NONE
  const flat = note.replace(/\s+/g, ' ').trim()

  return flat.length > NOTE_PREVIEW ? `${flat.slice(0, NOTE_PREVIEW).trimEnd()}…` : flat
}

/** Every day of `from`..`to`, oldest first. */
export function daysBetween(from: string, to: string): string[] {
  const days: string[] = []
  for (let day = from; day <= to; day = shiftDay(day, 1)) days.push(day)

  return days
}

/** The two lines of the « Moral · fatigue » card, on a 0–10 scale; a day without a score breaks its line. */
export function scorePaths(days: string[], byDate: Map<string, HealthDay>, score: 'mood' | 'fatigue'): { line: string; dots: string } {
  const x = (index: number) => (((index + 0.5) * 1000) / days.length).toFixed(1)
  const y = (value: number) => (100 - value * 10).toFixed(1)
  let line = ''
  let dots = ''
  let previous = false
  days.forEach((day, index) => {
    const value = byDate.get(day)?.[score] ?? null
    if (value === null) {
      previous = false

      return
    }
    line += `${line ? ' ' : ''}${previous ? 'L' : 'M'}${x(index)} ${y(value)}`
    dots += `${dots ? ' ' : ''}M${x(index)} ${y(value)} l0 0`
    previous = true
  })

  return { line, dots }
}

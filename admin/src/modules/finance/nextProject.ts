import { ganttMonthLabel } from './ProjectGantt'
import { formatDuration } from './independenceDates'

/** Whole months from `now`'s month to `purchaseMonth` (`YYYY-MM`), never below 0. */
export const monthsUntil = (purchaseMonth: string, now = new Date()): number => {
  const [year, month] = purchaseMonth.split('-').map(Number)

  return Math.max(0, (year ?? 0) * 12 + (month ?? 1) - (now.getFullYear() * 12 + now.getMonth() + 1))
}

/** « 3 ans », « 1 an et 4 mois », « 8 mois » — a duration is said in years and months, never « 36 mois ». */
export const durationLabel = (months: number): string => formatDuration(months) || '0 mois'

/** « dans 4 mois · oct. 2026 », « ce mois-ci · oct. 2026 ». */
export const purchaseLabel = (purchaseMonth: string, now = new Date()): string => {
  const months = monthsUntil(purchaseMonth, now)
  const delay = months === 0 ? 'ce mois-ci' : `dans ${durationLabel(months)}`

  return `${delay} · ${ganttMonthLabel(purchaseMonth)}`
}

/** « Restaurants et Obsydian », « A, B et C ». */
export const joinNames = (names: string[]): string =>
  names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} et ${names[names.length - 1]}`

/** « d'un mois », « de 3 mois », « d'un an », « de 1 an et 2 mois ». */
export const delayLabel = (months: number): string => {
  if (months === 1) return "d'un mois"
  if (months === 12) return "d'un an"

  return `de ${durationLabel(months)}`
}

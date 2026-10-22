import { ganttMonthLabel } from './ProjectGantt'

/** Whole months from `now`'s month to `purchaseMonth` (`YYYY-MM`), never below 0. */
export const monthsUntil = (purchaseMonth: string, now = new Date()): number => {
  const [year, month] = purchaseMonth.split('-').map(Number)

  return Math.max(0, (year ?? 0) * 12 + (month ?? 1) - (now.getFullYear() * 12 + now.getMonth() + 1))
}

/** « dans 4 mois · oct. 2026 », « ce mois-ci · oct. 2026 ». */
export const purchaseLabel = (purchaseMonth: string, now = new Date()): string => {
  const months = monthsUntil(purchaseMonth, now)
  const delay = months === 0 ? 'ce mois-ci' : `dans ${months} mois`

  return `${delay} · ${ganttMonthLabel(purchaseMonth)}`
}

/** « Restaurants et Obsydian », « A, B et C ». */
export const joinNames = (names: string[]): string =>
  names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} et ${names[names.length - 1]}`

/** « d'un mois », « de 3 mois ». */
export const delayLabel = (months: number): string => (months === 1 ? "d'un mois" : `de ${months} mois`)

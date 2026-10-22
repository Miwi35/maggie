import type { IndependenceCounter, IndependenceProjection } from './useFinanceDashboard'

/** The months of the independence projection are spelled `YYYY-MM` by the API. */

const parse = (month: string): { year: number; month: number } => {
  const [year, m] = month.split('-').map(Number)

  return { year, month: m }
}

const formatter = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' })

/** « septembre 2045 ». Built from the parts, never from an ISO string: no timezone can move the month. */
export const formatMonth = (month: string): string => {
  const { year, month: m } = parse(month)

  return formatter.format(new Date(year, m - 1, 1))
}

/** Whole months from `from` to `to` (both `YYYY-MM`). */
export const monthsBetween = (from: string, to: string): number => {
  const a = parse(from)
  const b = parse(to)

  return (b.year - a.year) * 12 + (b.month - a.month)
}

/** « 19 ans et 4 mois », « 1 an », « 5 mois ». */
export const formatDuration = (months: number): string => {
  const years = Math.floor(months / 12)
  const rest = months % 12
  const y = years > 0 ? `${years} an${years > 1 ? 's' : ''}` : ''
  const m = rest > 0 ? `${rest} mois` : ''

  return [y, m].filter((part) => part !== '').join(' et ')
}

/** True when there is a projection to show: nothing measured, nothing projected. */
export const hasProjection = (counter: IndependenceCounter): boolean =>
  counter.hasPassiveIncomeCategories && counter.isMeasurable

/** The date N as a sentence, or null when it is not reached or already behind. */
export const nDateLabel = (projection: IndependenceProjection): string | null =>
  null === projection.nDate || projection.nDate === projection.startMonth
    ? null
    : `Date N projetée · ${formatMonth(projection.nDate)}`

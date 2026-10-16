import type { UpcomingSource, UpcomingWindow } from './useUpcomingProjection'

export const SOURCE_LABELS: Record<UpcomingSource, string> = {
  recurring: 'récurrente',
  planned: 'prévue',
  committed: 'engagée',
}

const parse = (isoDate: string) => {
  const [year, month, day] = isoDate.split('-').map(Number)

  return new Date(year, month - 1, day)
}

/** `2026-10-24` → « sam. 24 », read as a calendar day (no timezone shift). */
export const weekdayLabel = (isoDate: string) =>
  parse(isoDate).toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric' })

/** `2026-10-19` → « lundi 19 octobre ». */
export const longDayLabel = (isoDate: string) =>
  parse(isoDate).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })

/** `2026-11` → « novembre 2026 ». */
export const monthLabel = (yearMonth: string) =>
  parse(`${yearMonth}-01`).toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })

const dayBefore = (isoDate: string) => {
  const date = parse(isoDate)
  date.setDate(date.getDate() - 1)

  return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-')
}

/** The window as the owner reads it: `until` is exclusive, so the last day is the day before. */
export const windowRange = (window: UpcomingWindow) =>
  `du ${longDayLabel(window.from)} au ${longDayLabel(dayBefore(window.until))}`

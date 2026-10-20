/** The value of `Event.activity` on the monthly review appointment (MAG-456). */
export const REVIEW_ACTIVITY = 'finance-review'

/** The review, full screen and out of the layout. */
export const REVIEW_ACTIVITY_PATH = '/finance/review/activity'

/** Where quitting goes when the activity was not launched from a page of the admin. */
export const REVIEW_FALLBACK_PATH = '/finance/dashboard'

/** What a launcher hands the activity, for « quitter » to go back where it was. */
export interface ReviewLaunchState {
  from?: string
}

const MONTHS = [
  'janvier',
  'février',
  'mars',
  'avril',
  'mai',
  'juin',
  'juillet',
  'août',
  'septembre',
  'octobre',
  'novembre',
  'décembre',
]

const monthIndexOf = (when: Date | string): number => {
  // A bare day (`YYYY-MM-DD`) is read as written: a time zone must not move it to the month before.
  if (typeof when === 'string' && /^\d{4}-\d{2}(-\d{2})?$/.test(when)) {
    return Number(when.slice(5, 7)) - 1
  }

  return (typeof when === 'string' ? new Date(when) : when).getMonth()
}

/** The reviews that close a quarter, a semester or a year (MAG-462), by month index. */
const PERIOD_ENDS: Record<number, string> = {
  2: 'Revue de fin de trimestre',
  5: 'Revue de fin de semestre',
  8: 'Revue de fin de trimestre',
  11: "Revue de fin d'année",
}

/** « Revue d'octobre », « Revue de fin de trimestre »: the review's title for the month it falls in (élision before a vowel). */
export const reviewTitle = (when: Date | string): string => {
  const index = monthIndexOf(when)
  if (PERIOD_ENDS[index]) return PERIOD_ENDS[index]

  const month = MONTHS[index] ?? ''

  return /^[aeiouyéèâàô]/i.test(month) ? `Revue d'${month}` : `Revue de ${month}`
}

/** An event's title as shown: the review appointment is named after its month, whatever its stored summary. */
export const eventTitle = (summary: string, activity: string | null | undefined, when: Date | string): string =>
  activity === REVIEW_ACTIVITY ? reviewTitle(when) : summary

/** The month the activity reviews: the current one, `YYYY-MM`. */
export const currentMonthKey = (now: Date = new Date()): string =>
  `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`

/** The wording of each kind of period: « du trimestre », « le trimestre suivant »… */
export const PERIOD_WORDS = {
  quarter: { name: 'trimestre', of: 'du trimestre', next: 'du trimestre suivant' },
  semester: { name: 'semestre', of: 'du semestre', next: 'du semestre suivant' },
  year: { name: 'année', of: "de l'année", next: "de l'année suivante" },
} as const

/** « avril à juin 2027 », « 2028 »: a period as the owner reads it. */
export const periodLabel = (period: { level: keyof typeof PERIOD_WORDS; from: string; to: string }): string => {
  if (period.level === 'year') return period.from.slice(0, 4)
  const from = MONTHS[Number(period.from.slice(5, 7)) - 1]
  const to = MONTHS[Number(period.to.slice(5, 7)) - 1]

  return `${from} à ${to} ${period.to.slice(0, 4)}`
}

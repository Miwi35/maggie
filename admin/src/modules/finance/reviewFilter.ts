import { currentMonthKey } from './reviewActivity'
import type { ReviewItem, ReviewPeriod, ReviewState } from './useReviewQueue'

/** Where the review's single list lives (MAG-539); its filters are the query. */
export const REVIEW_PATH = '/finance/review'

export interface ReviewFilter {
  state: ReviewState
  period: ReviewPeriod
  /** `YYYY-MM`; read only for the `month` period. */
  month: string
  q: string
}

const STATES: ReviewState[] = ['all', 'unrecognised', 'recognised']
const PERIODS: ReviewPeriod[] = ['month', 'since_review', 'all']

const MONTHS = [
  'Janvier',
  'Février',
  'Mars',
  'Avril',
  'Mai',
  'Juin',
  'Juillet',
  'Août',
  'Septembre',
  'Octobre',
  'Novembre',
  'Décembre',
]

const isMonthKey = (value: string | null): value is string => value !== null && /^\d{4}-(0[1-9]|1[0-2])$/.test(value)

/** The filter the URL says; anything missing or unknown reads as the default (all states, this month, no search). */
export const filterFromParams = (params: URLSearchParams, now: Date = new Date()): ReviewFilter => {
  const state = params.get('state')
  const period = params.get('period')
  const month = params.get('month')

  return {
    state: STATES.find((candidate) => candidate === state) ?? 'all',
    period: PERIODS.find((candidate) => candidate === period) ?? 'month',
    month: isMonthKey(month) ? month : currentMonthKey(now),
    q: params.get('q') ?? '',
  }
}

/** The query of a filter: the defaults are left out, so a bare `/finance/review` is the default list. */
export const paramsFromFilter = (filter: ReviewFilter, now: Date = new Date()): URLSearchParams => {
  const params = new URLSearchParams()
  if (filter.state !== 'all') params.set('state', filter.state)
  if (filter.period !== 'month') params.set('period', filter.period)
  if (filter.period === 'month' && filter.month !== currentMonthKey(now)) params.set('month', filter.month)
  if (filter.q !== '') params.set('q', filter.q)

  return params
}

/** The link to the review with these filters: `reviewLink({ state: 'unrecognised', period: 'all' })`. */
export const reviewLink = (filter: Partial<ReviewFilter> = {}): string => {
  const query = new URLSearchParams()
  if (filter.state) query.set('state', filter.state)
  if (filter.period) query.set('period', filter.period)
  if (filter.month) query.set('month', filter.month)
  const text = query.toString()

  return text === '' ? REVIEW_PATH : `${REVIEW_PATH}?${text}`
}

/** The addresses of the three Revue pages of before: each opens the list filtered as that page was. */
export const LEGACY_REVIEW_REDIRECTS = {
  '/finance/to-sort': reviewLink({ state: 'unrecognised', period: 'all' }),
  '/finance/to-control': reviewLink({ state: 'recognised', period: 'since_review' }),
  '/finance/monthly-review': reviewLink({ state: 'all', period: 'month' }),
} as const

/** « Octobre 2026 » for `2026-10`. */
export const monthLabel = (month: string): string => `${MONTHS[Number(month.slice(5, 7)) - 1] ?? ''} ${month.slice(0, 4)}`

/** The month before or after `month` (`YYYY-MM`). */
export const shiftMonth = (month: string, delta: number): string => {
  const index = Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7)) - 1 + delta

  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`
}

/** Whether the line is shown as recognised: an operation's, or one the owner qualified or handled during this visit. */
export const isRecognisedNow = (item: ReviewItem, resolved: Record<string, unknown>): boolean =>
  item.recognised || item.id in resolved

/** Whether a line answers the search: its label or its counterparty contains the text, whatever the case. */
export const matchesSearch = (item: ReviewItem, q: string): boolean => {
  const needle = q.trim().toLowerCase()
  if (needle === '') return true

  return [item.label, item.counterpartyName ?? ''].some((text) => text.toLowerCase().includes(needle))
}

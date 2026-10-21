import { describe, test, expect } from 'vitest'
import {
  LEGACY_REVIEW_REDIRECTS,
  filterFromParams,
  isRecognisedNow,
  matchesSearch,
  monthLabel,
  paramsFromFilter,
  reviewLink,
  shiftMonth,
} from './reviewFilter'
import type { ReviewItem } from './useReviewQueue'

const NOW = new Date('2026-10-21T10:00:00')

const item = (label: string, counterpartyName: string | null = null, recognised = false) =>
  ({ id: label, label, counterpartyName, recognised }) as unknown as ReviewItem

describe('reviewFilter', () => {
  test('a bare URL is every state, this month, no search', () => {
    expect(filterFromParams(new URLSearchParams(), NOW)).toEqual({ state: 'all', period: 'month', month: '2026-10', q: '' })
  })

  test('the URL names the state, the period, the month and the search', () => {
    const params = new URLSearchParams('state=recognised&period=month&month=2026-03&q=net')

    expect(filterFromParams(params, NOW)).toEqual({ state: 'recognised', period: 'month', month: '2026-03', q: 'net' })
  })

  test.each([
    ['state=nope', { state: 'all', period: 'month', month: '2026-10', q: '' }],
    ['period=nope', { state: 'all', period: 'month', month: '2026-10', q: '' }],
    ['month=2026-13', { state: 'all', period: 'month', month: '2026-10', q: '' }],
    ['month=octobre', { state: 'all', period: 'month', month: '2026-10', q: '' }],
  ])('an unknown value (%s) reads as the default', (query, expected) => {
    expect(filterFromParams(new URLSearchParams(query), NOW)).toEqual(expected)
  })

  test('the defaults are left out of the query, and the query reads back as the same filter', () => {
    expect(paramsFromFilter({ state: 'all', period: 'month', month: '2026-10', q: '' }, NOW).toString()).toBe('')

    for (const filter of [
      { state: 'unrecognised', period: 'all', month: '2026-10', q: '' },
      { state: 'recognised', period: 'since_review', month: '2026-10', q: 'net flix' },
      { state: 'all', period: 'month', month: '2026-03', q: 'x' },
    ] as const) {
      expect(filterFromParams(paramsFromFilter(filter, NOW), NOW)).toEqual(filter)
    }
  })

  test('the month is written only for the month period', () => {
    expect(paramsFromFilter({ state: 'all', period: 'all', month: '2026-03', q: '' }, NOW).toString()).toBe('period=all')
  })

  test('reviewLink builds the address of a filtered list', () => {
    expect(reviewLink()).toBe('/finance/review')
    expect(reviewLink({ state: 'unrecognised', period: 'all' })).toBe('/finance/review?state=unrecognised&period=all')
  })

  test('the three old Revue pages lead to the list filtered as they were', () => {
    expect(LEGACY_REVIEW_REDIRECTS['/finance/to-sort']).toBe('/finance/review?state=unrecognised&period=all')
    expect(LEGACY_REVIEW_REDIRECTS['/finance/to-control']).toBe('/finance/review?state=recognised&period=since_review')
    expect(LEGACY_REVIEW_REDIRECTS['/finance/monthly-review']).toBe('/finance/review?state=all&period=month')
  })

  test('a month is named in French and shifts across years', () => {
    expect(monthLabel('2026-10')).toBe('Octobre 2026')
    expect(monthLabel('2026-02')).toBe('Février 2026')
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftMonth('2026-10', 0)).toBe('2026-10')
  })

  test('a line is recognised when it is one, or when the owner handled it during this visit', () => {
    expect(isRecognisedNow(item('a'), {})).toBe(false)
    expect(isRecognisedNow(item('a'), { a: 'sorted' })).toBe(true)
    expect(isRecognisedNow(item('b', null, true), {})).toBe(true)
  })

  test('the search reads the label and the counterparty, whatever the case, and blank keeps everything', () => {
    expect(matchesSearch(item('CARREFOUR MARKET'), 'carre')).toBe(true)
    expect(matchesSearch(item('PRLV 123', 'Netflix'), ' NETFLIX ')).toBe(true)
    expect(matchesSearch(item('PRLV 123', 'Netflix'), 'spotify')).toBe(false)
    expect(matchesSearch(item('PRLV 123'), '   ')).toBe(true)
  })
})

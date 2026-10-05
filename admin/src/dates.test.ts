import { describe, test, expect } from 'vitest'
import { localDay } from './dates'

/**
 * The whole admin-side half of MAG-251 is this function, so it is pinned here
 * rather than only through the screens that call it.
 *
 * The time zone is Europe/Paris for the suite (`vite.config.ts`), which is what
 * makes the first case meaningful: Wednesday at midnight local is Tuesday at
 * 22:00 UTC, and `toISOString().split('T')[0]` — what the week view used —
 * returns the Tuesday.
 */
describe('localDay', () => {
  test('is the local day, not the UTC one', () => {
    // Summer time: midnight in Paris is 22:00 the day before in UTC.
    expect(localDay(new Date(2026, 9, 7))).toBe('2026-10-07')
    // Winter time: 23:00 the day before.
    expect(localDay(new Date(2026, 11, 9))).toBe('2026-12-09')
    // The day the clocks go back, which opens at +02:00 and closes at +01:00.
    expect(localDay(new Date(2026, 9, 25))).toBe('2026-10-25')
  })

  test('pads the month and the day to two digits', () => {
    expect(localDay(new Date(2026, 0, 5))).toBe('2026-01-05')
  })

  test('keeps the local day of an instant late in the evening', () => {
    // 23:30 in Paris is already the next day in UTC under winter time.
    expect(localDay(new Date(2026, 11, 9, 23, 30))).toBe('2026-12-09')
  })

  test('keeps the local day of an instant just past midnight', () => {
    // 00:30 in Paris is still the day before in UTC.
    expect(localDay(new Date(2026, 9, 7, 0, 30))).toBe('2026-10-07')
  })
})

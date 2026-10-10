import { describe, test, expect } from 'vitest'
import { addDays, daysBetween, endDateOf, lastDayOf, localDay, parseDay } from './dates'

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

/** An all-day event is a pair of dates (MAG-382): their arithmetic must not see a time zone. */
describe('day arithmetic', () => {
  test('adds days across a month, a year and the clocks going back', () => {
    expect(addDays('2037-01-28', 1)).toBe('2037-01-29')
    expect(addDays('2036-12-31', 1)).toBe('2037-01-01')
    expect(addDays('2037-01-01', -1)).toBe('2036-12-31')
    // 25 hours long in Paris: a day of 24 h in local time would land on the 25th again.
    expect(addDays('2026-10-25', 1)).toBe('2026-10-26')
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29')
  })

  test('counts the days between two dates', () => {
    expect(daysBetween('2037-01-26', '2037-01-28')).toBe(2)
    expect(daysBetween('2037-01-01', '2037-01-01')).toBe(0)
    expect(daysBetween('2026-10-24', '2026-10-26')).toBe(2)
    expect(daysBetween('2037-01-28', '2037-01-26')).toBe(-2)
  })

  test('turns the exclusive end of the contract into the last day shown, and back', () => {
    // A day of the 1st is stored 1st → 2nd: the owner reads « le 1er ».
    expect(lastDayOf('2037-01-02')).toBe('2037-01-01')
    // « du 26 au 28 » is stored 26 → 29.
    expect(endDateOf('2037-01-28')).toBe('2037-01-29')
    expect(lastDayOf('2037-01-01')).toBe('2036-12-31')
    expect(endDateOf('2036-12-31')).toBe('2037-01-01')
    expect(lastDayOf(endDateOf('2028-02-28'))).toBe('2028-02-28')
  })

  test('reads a date as its local midnight', () => {
    expect(localDay(parseDay('2037-01-01'))).toBe('2037-01-01')
    expect(parseDay('2037-01-01').getHours()).toBe(0)
  })
})

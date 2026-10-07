import { parisDay } from '../fixtures/index.js'

/**
 * A day of the week the week view is showing, Monday being 0.
 *
 * Read off the Paris day, which is the day the browser is on
 * (`playwright.config.ts` pins the time zone) and so the day `getMonday(new
 * Date())` lands on inside the view.
 */
export function dayOfThisWeek(index: number): string {
  const midnightUtc = new Date(`${parisDay()}T00:00:00Z`)
  const weekday = midnightUtc.getUTCDay()

  midnightUtc.setUTCDate(midnightUtc.getUTCDate() - (0 === weekday ? 6 : weekday - 1) + index)

  return midnightUtc.toISOString().slice(0, 10)
}

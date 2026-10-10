/**
 * The day a `Date` falls on *here*, as `YYYY-MM-DD`.
 *
 * Never `toISOString().split('T')[0]`: that is the UTC day, and Wednesday at
 * midnight in Paris is Tuesday at 22:00 there. The week view used it to label
 * its cells, so the cell the owner clicked offered him the previous day and the
 * meal was written to it — MAG-251.
 *
 * Shared because the mistake was one file's and the fix has to hold in every
 * screen that turns a `Date` into a day a day-typed field can be compared to.
 */
export function localDay(d: Date): string {
  const month = `${d.getMonth() + 1}`.padStart(2, '0')
  const day = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

/*
 * An all-day event is a pair of dates, `YYYY-MM-DD`, the end EXCLUDED — exactly
 * Google's `start.date`/`end.date` (MAG-382): a day of the 1st is 1st → 2nd.
 * The helpers below do arithmetic on the date itself — through `Date.UTC`, which
 * has no offset and no daylight saving — so no time zone can move a day.
 */

const DAY_MS = 86_400_000

const utcOf = (day: string): number => {
  const [y, m, d] = day.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

/** `day` moved by `n` days (negative goes back). */
export function addDays(day: string, n: number): string {
  return new Date(utcOf(day) + n * DAY_MS).toISOString().slice(0, 10)
}

/** How many days from `from` to `to` (`to` − `from`). */
export function daysBetween(from: string, to: string): number {
  return Math.round((utcOf(to) - utcOf(from)) / DAY_MS)
}

/**
 * The last day an all-day event covers, from its exclusive `endDate` — what the
 * owner reads and types (« du 26 au 28 »). With {@link endDateOf}, the only place
 * the admin turns the contract's exclusive end into an included one and back:
 * the dialogs and the card use them, everything else keeps the exclusive end.
 */
export function lastDayOf(endDate: string): string {
  return addDays(endDate, -1)
}

/** The exclusive `endDate` of an all-day event whose last day is `lastDay`. */
export function endDateOf(lastDay: string): string {
  return addDays(lastDay, 1)
}

/** Local midnight of `day` — for a widget that only takes a `Date`, never for storage. */
export function parseDay(day: string): Date {
  const [y, m, d] = day.split('-').map(Number)
  return new Date(y, m - 1, d)
}

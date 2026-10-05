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

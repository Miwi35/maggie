import type { BrowserContext } from '@playwright/test'

/**
 * The e2e clock (MAG-234).
 *
 * `E2E_NOW` fixes "now" for the whole stack — the API, the worker and the agent
 * through libfaketime, the emulator through `adb`, and the browser here. Unset,
 * nothing is pinned and every tab keeps the wall clock.
 *
 * Read the clock through {@link e2eNow}, never through `Date.now()`: a journey
 * that builds a token or a date from the real clock disagrees with a stack
 * that believes it is Sunday 23:50, and passes or fails depending on the hour
 * it was started at — which is how MAG-177 and MAG-212 happened.
 */

/** The instant the stack runs at — `E2E_NOW`, or the real clock without it. */
export function e2eNow(): Date {
  const pinned = process.env.E2E_NOW

  if (!pinned) {
    return new Date()
  }

  const instant = new Date(pinned)

  if (Number.isNaN(instant.getTime())) {
    throw new Error(`E2E_NOW is not a date: ${pinned}`)
  }

  return instant
}

/**
 * Pins `Date` in every page of the context to {@link e2eNow}.
 *
 * `setFixedTime`, not `install`: the date stands still but timers, animation
 * frames and the Mercure stream keep running, so waits and polling behave as
 * they always did.
 */
export async function pinClock(context: BrowserContext): Promise<void> {
  if (process.env.E2E_NOW) {
    await context.clock.setFixedTime(e2eNow())
  }
}

/**
 * The day an instant falls on in Paris, as `YYYY-MM-DD`.
 *
 * The browser runs on `Europe/Paris` (see `playwright.config.ts`), so this is
 * the day every screen that turns "now" into a day arrives at — the week view's
 * Monday, the dashboard's "today". Never build it from a UTC date: between
 * midnight and 02:00 in Paris, UTC is still the day before, which is the whole
 * subject of MAG-251.
 */
export function parisDay(instant: Date = e2eNow()): string {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Europe/Paris',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(instant)
  const part = (type: string) => parts.find((candidate) => candidate.type === type)?.value ?? ''

  return `${part('year')}-${part('month')}-${part('day')}`
}

/** Paris's offset from UTC at an instant, in minutes — 120 in summer, 60 in winter. */
function parisOffsetMinutes(instant: number): number {
  const name =
    new Intl.DateTimeFormat('en', { timeZone: 'Europe/Paris', timeZoneName: 'longOffset' })
      .formatToParts(new Date(instant))
      .find((part) => 'timeZoneName' === part.type)?.value ?? 'GMT'
  const match = /GMT([+-])(\d{2}):(\d{2})/.exec(name)

  return match ? (match[1] === '-' ? -1 : 1) * (Number(match[2]) * 60 + Number(match[3])) : 0
}

/**
 * A Paris wall-clock time as an ISO-8601 string with the right offset:
 * `parisTime('2026-10-26', '00:00:00')` is `2026-10-26T00:00:00+01:00`.
 *
 * Never write `+02:00` by hand. Paris is on +01:00 half the year, and a journey
 * run on the other side of the last Sunday of October would put its event an
 * hour away from the day it meant.
 */
export function parisTime(day: string, time: string): string {
  const local = Date.parse(`${day}T${time}Z`)
  const offset = parisOffsetMinutes(local - parisOffsetMinutes(local) * 60_000)
  const sign = offset < 0 ? '-' : '+'
  const hours = String(Math.floor(Math.abs(offset) / 60)).padStart(2, '0')
  const minutes = String(Math.abs(offset) % 60).padStart(2, '0')

  return `${day}T${time}${sign}${hours}:${minutes}`
}

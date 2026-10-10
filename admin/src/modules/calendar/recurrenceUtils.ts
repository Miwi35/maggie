import { RRule, rrulestr } from 'rrule'

const DAY_MS = 24 * 3600_000

/** Milliseconds to add to an instant to read its wall clock in `timeZone`. */
const wallClockOffsetMs = (instant: Date, timeZone: string): number => {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
  }).formatToParts(instant)
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  const wall = Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second'))
  return wall - Math.floor(instant.getTime() / 1000) * 1000
}

/** The instant at which the wall clock of `timeZone` reads `wall` (read as UTC fields). */
const instantFromWallClock = (wall: Date, timeZone: string): Date => {
  const guess = new Date(wall.getTime() - wallClockOffsetMs(wall, timeZone))
  return new Date(wall.getTime() - wallClockOffsetMs(guess, timeZone))
}

/**
 * Expand an RRULE string into occurrence dates within a given range.
 *
 * With a `timeZone`, the series is expanded on that zone's wall clock — a weekly
 * 18:00 lesson stays at 18:00 when the clocks change — and each occurrence is
 * returned as the real instant it falls on. Without one, it is expanded in UTC.
 */
export const expandRrule = (
  rruleString: string,
  dtstart: Date,
  rangeStart: Date,
  rangeEnd: Date,
  timeZone?: string,
): Date[] => {
  if (!timeZone) {
    return rrulestr(rruleString, { dtstart }).between(rangeStart, rangeEnd, true)
  }

  // rrule.js only knows UTC: feed it the wall clock as if it were UTC, then map back.
  const floating = (instant: Date) => new Date(instant.getTime() + wallClockOffsetMs(instant, timeZone))
  const rule = rrulestr(rruleString, { dtstart: floating(dtstart) })
  // A day of slack on each side: the wall clock and the instant can sit on either side of the bounds.
  const walls = rule.between(
    new Date(floating(rangeStart).getTime() - DAY_MS),
    new Date(floating(rangeEnd).getTime() + DAY_MS),
    true,
  )

  return walls
    .map((wall) => instantFromWallClock(wall, timeZone))
    .filter((instant) => instant >= rangeStart && instant <= rangeEnd)
}

/**
 * Expand the RRULE of an all-day series on dates, never on instants (MAG-382).
 *
 * Each date is fed to rrule.js as its UTC midnight — a label, not an instant — so
 * an annual birthday stays on its day whatever the zone and the clocks. Returns the
 * first date of every occurrence that covers part of `[firstDay, endDay)`, given
 * that an occurrence covers `lengthDays` days (`endDate − startDate`, 1 for one day).
 */
export const expandRruleDays = (
  rruleString: string,
  startDate: string,
  lengthDays: number,
  firstDay: string,
  endDay: string,
): string[] => {
  const asUtc = (day: string) => new Date(`${day}T00:00:00Z`)
  const from = new Date(asUtc(firstDay).getTime() - (Math.max(lengthDays, 1) - 1) * DAY_MS)

  return rrulestr(rruleString, { dtstart: asUtc(startDate) })
    .between(from, asUtc(endDay), true)
    .map((occurrence) => occurrence.toISOString().slice(0, 10))
    .filter((day) => day < endDay)
}

const FREQ_LABELS: Record<number, { singular: string; plural: string }> = {
  [RRule.DAILY]: { singular: 'jour', plural: 'jours' },
  [RRule.WEEKLY]: { singular: 'semaine', plural: 'semaines' },
  [RRule.MONTHLY]: { singular: 'mois', plural: 'mois' },
  [RRule.YEARLY]: { singular: 'an', plural: 'ans' },
}

const FRENCH_DAYS = ['lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.', 'dim.']

// rrule.js weekday constants: MO=0 … SU=6
const RRULE_DAY_TO_FRENCH: Record<number, string> = {
  0: 'lun.',
  1: 'mar.',
  2: 'mer.',
  3: 'jeu.',
  4: 'ven.',
  5: 'sam.',
  6: 'dim.',
}

/**
 * Convert an RRULE string to a human-readable French description.
 */
export const rruleToFrenchText = (rruleString: string): string => {
  let rule: RRule
  try {
    rule = rrulestr(rruleString) as RRule
  } catch {
    return rruleString
  }

  const options = rule.options
  const freq = options.freq
  const interval = options.interval || 1
  const labels = FREQ_LABELS[freq]
  if (!labels) return rruleString

  let text: string
  if (interval === 1) {
    if (freq === RRule.DAILY) text = 'Tous les jours'
    else if (freq === RRule.WEEKLY) text = 'Toutes les semaines'
    else if (freq === RRule.MONTHLY) text = 'Tous les mois'
    else text = 'Tous les ans'
  } else {
    if (freq === RRule.WEEKLY) text = `Toutes les ${interval} ${labels.plural}`
    else text = `Tous les ${interval} ${labels.plural}`
  }

  // Weekly day list
  if (freq === RRule.WEEKLY && options.byweekday && options.byweekday.length > 0) {
    const dayNames = options.byweekday
      .map((d: number) => RRULE_DAY_TO_FRENCH[d] || FRENCH_DAYS[d])
      .filter(Boolean)
    if (dayNames.length > 0) {
      text += ` le ${dayNames.join(', ')}`
    }
  }

  // End condition
  if (options.count) {
    text += `, ${options.count} fois`
  } else if (options.until) {
    const until = new Date(options.until)
    text += `, jusqu'au ${until.toLocaleDateString('fr-FR', { day: 'numeric', month: 'long', year: 'numeric' })}`
  }

  return text
}

/**
 * Build an RRULE string from picker options.
 */
export const buildRruleString = (opts: {
  freq: number
  interval: number
  byweekday?: number[]
  count?: number
  until?: Date
}): string => {
  const parts: string[] = []

  const freqMap: Record<number, string> = {
    [RRule.DAILY]: 'DAILY',
    [RRule.WEEKLY]: 'WEEKLY',
    [RRule.MONTHLY]: 'MONTHLY',
    [RRule.YEARLY]: 'YEARLY',
  }
  parts.push(`FREQ=${freqMap[opts.freq]}`)

  if (opts.interval > 1) {
    parts.push(`INTERVAL=${opts.interval}`)
  }

  if (opts.freq === RRule.WEEKLY && opts.byweekday && opts.byweekday.length > 0) {
    const dayMap: Record<number, string> = { 0: 'MO', 1: 'TU', 2: 'WE', 3: 'TH', 4: 'FR', 5: 'SA', 6: 'SU' }
    parts.push(`BYDAY=${opts.byweekday.map((d) => dayMap[d]).join(',')}`)
  }

  if (opts.count) {
    parts.push(`COUNT=${opts.count}`)
  } else if (opts.until) {
    const pad = (n: number) => String(n).padStart(2, '0')
    const d = opts.until
    parts.push(`UNTIL=${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}T235959Z`)
  }

  return parts.join(';')
}

/**
 * Truncate an RRULE at a given date by adding an UNTIL clause.
 * Removes any existing UNTIL or COUNT to avoid conflicts.
 * The UNTIL is set to 23:59:59 UTC on the day before `beforeDate`.
 */
export const addUntilToRrule = (rruleString: string, beforeDate: Date): string => {
  const parts = rruleString
    .split(';')
    .filter((p) => !p.startsWith('UNTIL=') && !p.startsWith('COUNT='))

  const day = new Date(beforeDate)
  day.setDate(day.getDate() - 1)
  const pad = (n: number) => String(n).padStart(2, '0')
  const until = `${day.getFullYear()}${pad(day.getMonth() + 1)}${pad(day.getDate())}T235959Z`

  parts.push(`UNTIL=${until}`)
  return parts.join(';')
}

export { RRule }

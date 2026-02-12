import { RRule, rrulestr } from 'rrule'

/**
 * Expand an RRULE string into occurrence dates within a given range.
 */
export const expandRrule = (
  rruleString: string,
  dtstart: Date,
  rangeStart: Date,
  rangeEnd: Date,
): Date[] => {
  const rule = rrulestr(rruleString, { dtstart })
  return rule.between(rangeStart, rangeEnd, true)
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

export { RRule }

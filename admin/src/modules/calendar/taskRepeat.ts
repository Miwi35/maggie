import { rruleToFrenchText } from './recurrenceUtils'

/** « Répéter » and « Jusqu’au » of the task form (MAG-888): the contract of Android's `TaskRepeat.kt` (MAG-887). */

export type RepeatChoice = 'never' | 'daily' | 'weekly' | 'biweekly' | 'monthly' | 'custom'

/** The six choices of the « Répéter » menu, in the menu's order. */
export const REPEAT_CHOICES: { key: RepeatChoice; label: string }[] = [
  { key: 'never', label: 'Jamais' },
  { key: 'daily', label: 'Chaque jour' },
  { key: 'weekly', label: 'Chaque semaine' },
  { key: 'biweekly', label: 'Toutes les deux semaines' },
  { key: 'monthly', label: 'Chaque mois' },
  { key: 'custom', label: 'Personnalisé…' },
]

export type Weekday = 'MO' | 'TU' | 'WE' | 'TH' | 'FR' | 'SA' | 'SU'

/** Week order, with the day's full name. */
export const WEEKDAYS: { code: Weekday; name: string }[] = [
  { code: 'MO', name: 'lundi' },
  { code: 'TU', name: 'mardi' },
  { code: 'WE', name: 'mercredi' },
  { code: 'TH', name: 'jeudi' },
  { code: 'FR', name: 'vendredi' },
  { code: 'SA', name: 'samedi' },
  { code: 'SU', name: 'dimanche' },
]

/** A rule the form can write; `days` for weeks, `monthDay` for months (empty / null: the anchor's). */
export interface RepeatRule {
  unit: 'day' | 'week' | 'month'
  interval: number
  days: Weekday[]
  monthDay?: number | null
}

const SHORT_DAYS = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.']
const SHORT_MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.']
const KNOWN_KEYS = ['FREQ', 'INTERVAL', 'BYDAY', 'BYMONTHDAY', 'UNTIL']

/** A `YYYY-MM-DD` read at noon, so no zone can move it (MAG-404). */
const dayAt = (day: string): Date => new Date(`${day.slice(0, 10)}T12:00:00`)

const weekdayOf = (anchor: string): Weekday => {
  const jsDay = dayAt(anchor).getDay()

  return WEEKDAYS[jsDay === 0 ? 6 : jsDay - 1].code
}

const monthDayOf = (anchor: string): number => dayAt(anchor).getDate()

export const defaultRule = (anchor: string): RepeatRule => ({ unit: 'week', interval: 1, days: [weekdayOf(anchor)] })

export const presetRule = (choice: RepeatChoice, anchor: string): RepeatRule => {
  switch (choice) {
    case 'daily':
      return { unit: 'day', interval: 1, days: [] }
    case 'biweekly':
      return { unit: 'week', interval: 2, days: [weekdayOf(anchor)] }
    case 'monthly':
      return { unit: 'month', interval: 1, days: [], monthDay: monthDayOf(anchor) }
    default:
      return defaultRule(anchor)
  }
}

const compact = (day: string): string => day.slice(0, 10).replace(/-/g, '')

/** `FREQ;INTERVAL;BYDAY|BYMONTHDAY;UNTIL`, INTERVAL left out at 1, no `COUNT`. */
const writeRule = (rule: RepeatRule, anchor: string, until: string | null): string => {
  const parts = [{ day: 'FREQ=DAILY', week: 'FREQ=WEEKLY', month: 'FREQ=MONTHLY' }[rule.unit]]
  if (rule.interval > 1) parts.push(`INTERVAL=${rule.interval}`)
  if (rule.unit === 'week') {
    const days = rule.days.length > 0 ? rule.days : [weekdayOf(anchor)]
    parts.push(`BYDAY=${WEEKDAYS.filter((d) => days.includes(d.code)).map((d) => d.code).join(',')}`)
  } else if (rule.unit === 'month') {
    parts.push(`BYMONTHDAY=${rule.monthDay ?? monthDayOf(anchor)}`)
  }
  if (until) parts.push(`UNTIL=${compact(until)}`)

  return parts.join(';')
}

/** `rrule` with its end day replaced by `until` (`YYYY-MM-DD`), or removed. */
export const withUntil = (rrule: string, until: string | null): string =>
  [...rrule.split(';').filter((p) => !p.startsWith('UNTIL=')), ...(until ? [`UNTIL=${compact(until)}`] : [])].join(';')

const partsOf = (rrule: string): Record<string, string> =>
  Object.fromEntries(
    rrule
      .split(';')
      .filter((p) => p.includes('='))
      .map((p) => [p.slice(0, p.indexOf('=')), p.slice(p.indexOf('=') + 1)]),
  )

/** The rule as the form can edit it; null when it falls outside the contract (the text falls back). */
export const parseRule = (rrule: string | null | undefined): RepeatRule | null => {
  if (!rrule) return null
  const p = partsOf(rrule)
  if (!Object.keys(p).every((k) => KNOWN_KEYS.includes(k))) return null
  let interval = 1
  if ('INTERVAL' in p) {
    interval = /^\d+$/.test(p.INTERVAL) ? Number(p.INTERVAL) : 0
    if (interval < 1) return null
  }
  switch (p.FREQ) {
    case 'DAILY':
      return 'BYDAY' in p || 'BYMONTHDAY' in p ? null : { unit: 'day', interval, days: [] }
    case 'WEEKLY': {
      if ('BYMONTHDAY' in p) return null
      const codes = 'BYDAY' in p ? p.BYDAY.split(',') : []
      if (!codes.every((c) => WEEKDAYS.some((d) => d.code === c))) return null

      return { unit: 'week', interval, days: codes as Weekday[] }
    }
    case 'MONTHLY': {
      if ('BYDAY' in p) return null
      let monthDay: number | null = null
      if ('BYMONTHDAY' in p) {
        monthDay = /^\d+$/.test(p.BYMONTHDAY) ? Number(p.BYMONTHDAY) : 0
        if (monthDay < 1 || monthDay > 31) return null
      }

      return { unit: 'month', interval, days: [], monthDay }
    }
    default:
      return null
  }
}

/** The end day of a rule, `YYYY-MM-DD`; null without one. */
export const untilOf = (rrule: string | null | undefined): string | null => {
  const raw = rrule ? partsOf(rrule).UNTIL?.split('T')[0] : undefined

  return raw && /^\d{8}$/.test(raw) ? `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6)}` : null
}

/** The preset writing exactly `rrule` for `anchor`, `UNTIL` aside; otherwise « Personnalisé… ». */
export const presetOf = (rrule: string | null | undefined, anchor: string | null): RepeatChoice => {
  if (!rrule) return 'never'
  if (!anchor) return 'custom'
  const bare = withUntil(rrule, null)
  const presets: RepeatChoice[] = ['daily', 'weekly', 'biweekly', 'monthly']

  return presets.find((c) => writeRule(presetRule(c, anchor), anchor, null) === bare) ?? 'custom'
}

/** A rule in plain words; null when it has nothing to say (no days and no anchor). */
export const plainRule = (rule: RepeatRule, anchor: string | null): string | null => {
  const n = rule.interval
  if (rule.unit === 'day') return n === 1 ? 'Chaque jour' : `Tous les ${n} jours`
  if (rule.unit === 'week') {
    const codes = rule.days.length > 0 ? rule.days : anchor ? [weekdayOf(anchor)] : []
    if (codes.length === 0) return null
    const names = WEEKDAYS.filter((d) => codes.includes(d.code)).map((d) => d.name)
    const list = names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} et ${names[names.length - 1]}`
    if (n === 1) return `Chaque ${list}`
    if (n === 2 && names.length === 1) return `Un ${list} sur deux`

    return `Le ${list}, toutes les ${n} semaines`
  }
  const day = rule.monthDay ?? (anchor ? monthDayOf(anchor) : null)
  if (day == null) return null
  const said = day === 1 ? '1er' : String(day)

  return n === 1 ? `Le ${said} de chaque mois` : `Le ${said}, tous les ${n} mois`
}

/** The rule in plain words, `UNTIL` left out; `anchor` stands in for a missing `BYDAY` / `BYMONTHDAY`. */
export const ruleText = (rrule: string | null | undefined, anchor: string | null): string => {
  if (!rrule) return 'Jamais'
  const rule = parseRule(rrule)

  return (rule && plainRule(rule, anchor)) ?? rruleToFrenchText(withUntil(rrule, null))
}

const shortDay = (day: string): string => {
  const date = dayAt(day)

  return `${SHORT_DAYS[date.getDay()]} ${date.getDate()} ${SHORT_MONTHS[date.getMonth()]}`
}

/** « , jusqu’au jeu. 31 déc. » on the sheet; null without an end day. */
export const untilSuffix = (rrule: string | null | undefined): string | null => {
  const day = untilOf(rrule)

  return day ? `, jusqu’au ${shortDay(day)}` : null
}

/** The « Jusqu’au » field: « Aucune fin » or « Jeu. 31 déc. ». */
export const untilLabel = (day: string | null): string => {
  if (!day) return 'Aucune fin'
  const text = shortDay(day)

  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** What the form holds about the repetition. */
export interface RepeatState {
  stored: string | null
  choice: RepeatChoice
  custom: RepeatRule | null
  /** `YYYY-MM-DD`. */
  until: string | null
  touched: boolean
}

export const repeatOf = (stored: string | null | undefined, anchor: string | null): RepeatState => ({
  stored: stored ?? null,
  choice: presetOf(stored, anchor),
  custom: parseRule(stored),
  until: untilOf(stored),
  touched: false,
})

/** The rule to send for this anchor day: null for « Jamais » and without an anchor. An untouched rule is sent as stored. */
export const buildRule = (state: RepeatState, anchor: string | null): string | null => {
  if (!anchor) return null
  if (!state.touched) return state.stored
  if (state.choice === 'never') return null
  if (state.choice === 'custom') {
    if (!state.custom && state.stored) return withUntil(state.stored, state.until)

    return writeRule(state.custom ?? defaultRule(anchor), anchor, state.until)
  }

  return writeRule(presetRule(state.choice, anchor), anchor, state.until)
}

/** The menu's checked choice: the preset writing exactly the current rule, else « Personnalisé… ». */
export const checkedChoice = (state: RepeatState, anchor: string | null): RepeatChoice =>
  presetOf(buildRule(state, anchor), anchor)

/** A preset other than « Personnalisé… »: « Jamais » clears the end day, the others keep it. */
export const pickChoice = (state: RepeatState, choice: RepeatChoice): RepeatState => ({
  ...state,
  choice,
  until: choice === 'never' ? null : state.until,
  touched: true,
})

export const withCustom = (state: RepeatState, rule: RepeatRule): RepeatState => ({
  ...state,
  choice: 'custom',
  custom: rule,
  touched: true,
})

export const withEnd = (state: RepeatState, until: string | null): RepeatState => ({
  ...state,
  until,
  touched: true,
})

/** Writes a custom rule for an anchor day, with an end day. */
export const writeCustom = (rule: RepeatRule, anchor: string, until: string | null): string =>
  writeRule(rule, anchor, until)

/** A row carries ↻ when its task is open and has a rule. */
export const repeats = (task: { rrule?: string | null; completedAt?: string | null }): boolean =>
  !!task.rrule && !task.completedAt

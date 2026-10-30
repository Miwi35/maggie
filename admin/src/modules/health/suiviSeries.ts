/**
 * The Poids card of Santé › Suivi (MAG-706), computed on the client: the
 * value of a day, the 7-day rolling trend, the columns of each period and
 * their labels. The same rules as the Android card (plan « Carte Poids »).
 */

export const PERIODS = [7, 30, 90, 365] as const
export type Period = (typeof PERIODS)[number]
export const DEFAULT_PERIOD: Period = 30
export const PERIOD_LABELS: Record<Period, string> = { 7: '7 j', 30: '30 j', 90: '90 j', 365: '1 an' }

/** How many days the trend averages: the day and the six before it. */
export const TREND_DAYS = 7

/** What the card reads of a body measurement. */
export interface Measured {
  measuredAt: string
  weightKg?: number | null
  bodyFatPercent?: number | null
  excluded?: boolean
}

export interface DayValue {
  weightKg: number
  bodyFatPercent: number | null
}

export interface Column {
  /** Local midnight of the column's first day. */
  start: Date
  days: Date[]
  /** « Aujourd’hui », « mer. 21 oct. », « Sem. du 14 oct. », « Octobre 2026 ». */
  label: string
  /** The axis: « 21 sept. », « nov. 25 ». */
  short: string
  /** The mean of the weighed days of the column; null when none is. */
  weightKg: number | null
  bodyFatPercent: number | null
  trendKg: number | null
}

const MONTHS = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.']
const MONTH_NAMES = ['Janvier', 'Février', 'Mars', 'Avril', 'Mai', 'Juin', 'Juillet', 'Août', 'Septembre', 'Octobre', 'Novembre', 'Décembre']
const WEEKDAYS = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.']

export const TODAY_LABEL = 'Aujourd’hui'
export const NONE = '—'

/** Local midnight of `date`'s day. */
export const startOfDay = (date: Date): Date => new Date(date.getFullYear(), date.getMonth(), date.getDate())

/** Local midnight `days` days after `date` (before when negative): by calendar, not by 24 h, so daylight saving cannot shift it. */
export const addDays = (date: Date, days: number): Date => new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)

/** `2026-10-21`: the local day an instant falls on. */
export const dayKey = (date: Date): string =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`

/** « 72,4 »: one decimal, a comma. */
export const formatOne = (value: number): string => value.toFixed(1).replace('.', ',')

/** « 72,4 kg », or « — ». */
export const formatKg = (value: number | null): string => (value === null ? NONE : `${formatOne(value)} kg`)

/** « 21,3 % », or « — ». */
export const formatPercent = (value: number | null): string => (value === null ? NONE : `${formatOne(value)} %`)

/** « mer. 21 oct. ». */
export const formatDay = (date: Date): string => `${WEEKDAYS[date.getDay()]} ${date.getDate()} ${MONTHS[date.getMonth()]}`

/** The first day the period's columns show, at local midnight. */
export function periodStart(period: Period, now: Date): Date {
  const today = startOfDay(now)
  if (period === 365) return new Date(today.getFullYear(), today.getMonth() - 11, 1)
  // 90 j: 13 weeks of 7 days, the 91 last days.
  return addDays(today, -(period === 90 ? 90 : period - 1))
}

/** Where the read starts: the period, and the six days the trend of its first day averages. */
export const readStart = (period: Period, now: Date): Date => addDays(periodStart(period, now), -(TREND_DAYS - 1))

/**
 * The value of each local day: its first weighing that is not excluded and
 * holds a weight (the API's `firstOfDay`), and the body fat of that weighing.
 */
export function dailyValues(measurements: Measured[]): Map<string, DayValue> {
  const days = new Map<string, DayValue>()
  const ordered = measurements
    .filter((measurement) => !measurement.excluded && measurement.weightKg != null)
    .map((measurement) => ({ measurement, at: new Date(measurement.measuredAt) }))
    .sort((a, b) => a.at.getTime() - b.at.getTime())

  for (const { measurement, at } of ordered) {
    const key = dayKey(at)
    if (days.has(key)) continue
    days.set(key, { weightKg: measurement.weightKg as number, bodyFatPercent: measurement.bodyFatPercent ?? null })
  }

  return days
}

const mean = (values: (number | null)[]): number | null => {
  const present = values.filter((value): value is number => value !== null)

  return present.length === 0 ? null : present.reduce((sum, value) => sum + value, 0) / present.length
}

/** The mean of the day values of `day` and the six days before; null when none of them is weighed. */
export function trendOf(daily: Map<string, DayValue>, day: Date): number | null {
  return mean(Array.from({ length: TREND_DAYS }, (_, back) => daily.get(dayKey(addDays(day, -back)))?.weightKg ?? null))
}

const daysBetween = (first: Date, last: Date): Date[] => {
  const days: Date[] = []
  for (let day = first; day.getTime() <= last.getTime(); day = addDays(day, 1)) days.push(day)

  return days
}

/** The days of each column: one per day (7 j, 30 j), seven (90 j) or a calendar month (1 an). */
function groups(period: Period, now: Date): Date[][] {
  const days = daysBetween(periodStart(period, now), startOfDay(now))
  if (period <= 30) return days.map((day) => [day])
  if (period === 90) return Array.from({ length: 13 }, (_, week) => days.slice(week * 7, week * 7 + 7))

  const months: Date[][] = []
  for (const day of days) {
    const current = months.at(-1)
    if (current && current[0].getMonth() === day.getMonth()) current.push(day)
    else months.push([day])
  }

  return months
}

function labelOf(period: Period, first: Date, isToday: boolean): string {
  if (period <= 30) return isToday ? TODAY_LABEL : formatDay(first)
  if (period === 90) return `Sem. du ${first.getDate()} ${MONTHS[first.getMonth()]}`

  return `${MONTH_NAMES[first.getMonth()]} ${first.getFullYear()}`
}

const shortOf = (period: Period, first: Date): string =>
  period === 365 ? `${MONTHS[first.getMonth()]} ${String(first.getFullYear()).slice(2)}` : `${first.getDate()} ${MONTHS[first.getMonth()]}`

/** The columns of the period, oldest first, the last one holding today. */
export function columns(measurements: Measured[], period: Period, now: Date): Column[] {
  const daily = dailyValues(measurements)
  const today = dayKey(now)

  return groups(period, now).map((days) => {
    const values = days.map((day) => daily.get(dayKey(day)) ?? null)

    return {
      start: days[0],
      days,
      label: labelOf(period, days[0], days.some((day) => dayKey(day) === today)),
      short: shortOf(period, days[0]),
      weightKg: mean(values.map((value) => value?.weightKg ?? null)),
      bodyFatPercent: mean(values.map((value) => value?.bodyFatPercent ?? null)),
      trendKg: mean(days.map((day) => trendOf(daily, day))),
    }
  })
}

/** « −1,2 kg »: how the trend moved from the first column to the last; « — » when either has none. */
export function trendDelta(cols: Column[]): string {
  const first = cols[0]?.trendKg ?? null
  const last = cols.at(-1)?.trendKg ?? null
  if (first === null || last === null) return NONE
  const delta = Math.round((last - first) * 10) / 10

  return `${delta < 0 ? '−' : '+'}${formatOne(Math.abs(delta))} kg`
}

/** « mer. 21 oct. : 72,4 kg, tendance 72,1 kg », « … : pas de pesée, tendance — ». */
export const columnLabel = (column: Column): string =>
  `${column.label} : ${column.weightKg === null ? 'pas de pesée' : formatKg(column.weightKg)}, tendance ${formatKg(column.trendKg)}`

/**
 * The SVG paths of the card, in a 1000 × 100 box: the trend line through the
 * columns that have one, and a dot per weighed column (7 j and 30 j only).
 */
export function chartPaths(cols: Column[], withDots: boolean): { line: string; dots: string } {
  const values = cols.flatMap((column) => [column.weightKg, column.trendKg]).filter((value): value is number => value !== null)
  if (values.length === 0) return { line: '', dots: '' }

  const low = Math.min(...values) - 0.4
  const high = Math.max(...values) + 0.4
  const x = (index: number) => (((index + 0.5) * 1000) / cols.length).toFixed(1)
  const y = (value: number) => (100 - ((value - low) / (high - low)) * 100).toFixed(1)

  let line = ''
  let dots = ''
  cols.forEach((column, index) => {
    if (column.trendKg !== null) line += `${line ? ' L' : 'M'}${x(index)} ${y(column.trendKg)}`
    if (withDots && column.weightKg !== null) dots += `M${x(index)} ${y(column.weightKg)} l0 0 `
  })

  return { line, dots: dots.trim() }
}

/** The weighings of the period, newest first; the excluded ones stay. */
export function weighingsOf<T extends Measured>(measurements: T[], period: Period, now: Date): T[] {
  const start = periodStart(period, now).getTime()

  return measurements
    .filter((measurement) => new Date(measurement.measuredAt).getTime() >= start)
    .sort((a, b) => new Date(b.measuredAt).getTime() - new Date(a.measuredAt).getTime())
}

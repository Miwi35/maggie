import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import { formatInteger } from './healthProfileApi'
import { addDays, dayKey, formatDay, TODAY_LABEL } from './suiviSeries'

/**
 * Santé › Nutrition (MAG-748): the day's balance as the API gives it —
 * `balanceKcal = eatenKcal + plannedKcal − expendedKcal`, never recomputed
 * here — and how the screen reads it. A balance is a figure, not a verdict:
 * the zones only choose a soft tone, never an alarm colour.
 */

/** Within ± 150 kcal of zero the day is « Équilibre ». */
export const BALANCE_ZONE_KCAL = 150

export type BalanceZone = 'balanced' | 'surplus' | 'deficit'

/** The zone of a balance; null when there is none (the profile gives no expenditure). */
export function balanceZone(balanceKcal: number | null): BalanceZone | null {
  if (balanceKcal === null) return null
  if (Math.abs(balanceKcal) <= BALANCE_ZONE_KCAL) return 'balanced'

  return balanceKcal > 0 ? 'surplus' : 'deficit'
}

/** The tone of a zone: a neutral ink for the balance, the accent for a surplus, its lighter tone for a deficit. */
export function zoneColor(theme: Theme, zone: BalanceZone | null): string {
  switch (zone) {
    case 'surplus':
      return theme.palette.primary.main
    case 'deficit':
      return alpha(theme.palette.primary.main, 0.5)
    default:
      return theme.palette.text.secondary
  }
}

/** « +320 kcal », « −480 kcal » (a real minus sign), « 0 kcal ». */
export function formatSignedKcal(value: number): string {
  const sign = value > 0 ? '+' : value < 0 ? '−' : ''

  return `${sign}${formatInteger(Math.abs(value))} kcal`
}

/** The big figure's word under it: « Équilibre » only, the other zones say nothing. */
export const ZONE_WORD = 'Équilibre'

export interface PlannedMeal {
  mealId: string
  slot: 'lunch' | 'dinner'
  label: string
  kcal: number | null
  proteinG: number
  carbsG: number
  fatG: number
}

/** `GET /api/health/daily-balance?date=`. */
export interface DailyBalance {
  date: string
  eatenKcal: number
  plannedKcal: number
  expendedKcal: number | null
  balanceKcal: number | null
  proteinG: number
  carbsG: number
  fatG: number
  plannedMeals: PlannedMeal[]
}

/** One day of `GET /api/health/daily-balance?from=&to=`. */
export type BalanceDay = Omit<DailyBalance, 'plannedMeals'>

export const DAILY_BALANCE_PATH = '/api/health/daily-balance'

/** How many days the « 7 jours » card shows: the day and the six before it. */
export const WEEK_DAYS = 7

const dateOf = (key: string): Date => {
  const [year, month, day] = key.split('-').map(Number)

  return new Date(year, month - 1, day)
}

/** « mer. 21 oct. », « Aujourd’hui » for `today`. */
export const dayLabel = (key: string, today: string): string => (key === today ? TODAY_LABEL : formatDay(dateOf(key)))

/** `2026-10-21` moved by `days`. */
export const shiftDay = (key: string, days: number): string => dayKey(addDays(dateOf(key), days))

/** The « 7 jours » range ending on `day`. */
export const weekRange = (day: string): { from: string; to: string } => ({ from: shiftDay(day, -(WEEK_DAYS - 1)), to: day })

/** « 38 g », « 12,5 g ». */
export const formatGrams = (value: number): string => `${new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 }).format(value)} g`

/** The half-width of a balance bar: wide enough for the figure, never narrower than 1 000 kcal. */
export const barScale = (values: (number | null)[]): number =>
  Math.max(1000, ...values.map((value) => (value === null ? 0 : Math.abs(value) * 1.1)))

/** The surface of every card of Santé › Nutrition, as Suivi's. */
export const cardSx = {
  borderRadius: 4,
  bgcolor: 'background.paper',
  px: 2.25,
  py: 2,
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  minWidth: 0,
  fontVariantNumeric: 'tabular-nums',
} as const

const PARIS_CLOCK = new Intl.DateTimeFormat('fr-FR', { timeZone: 'Europe/Paris', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

/** « 12:30 »: an instant's time in Paris; now by default, the time the form proposes. */
export const parisClock = (instant: Date = new Date()): string => PARIS_CLOCK.format(instant)

import { formatMonth, monthsBetween } from './independenceDates'
import { formatCentsRounded } from './accountTypes'
import type { TimelineEvent } from './timelineApi'

/** Markers closer than this many months share a lane's width: the next one is pushed to another lane. */
const MIN_GAP_MONTHS = 5
/** Past this, markers share the last lane rather than growing the row without end. */
export const MAX_LANES = 4

export interface PlacedEvent {
  event: TimelineEvent
  /** Months from the start of the frise, clamped to its horizon. */
  index: number
  /** 0 to 100: where the marker sits on the axis. */
  percent: number
  lane: number
}

/**
 * Puts each event on its month and gives it the first lane where it touches no
 * marker before it, so events of the same period read one above the other.
 */
export const placeEvents = (events: TimelineEvent[], startMonth: string, horizonMonths: number): PlacedEvent[] => {
  const lastIndexOfLane: number[] = []

  return [...events]
    .sort((a, b) => a.month.localeCompare(b.month) || a.id.localeCompare(b.id))
    .map((event) => {
      const index = Math.min(horizonMonths - 1, Math.max(0, monthsBetween(startMonth, event.month)))
      let lane = lastIndexOfLane.findIndex((last) => index - last >= MIN_GAP_MONTHS)
      if (lane === -1) lane = Math.min(lastIndexOfLane.length, MAX_LANES - 1)
      lastIndexOfLane[lane] = index

      return { event, index, percent: ((index + 0.5) / horizonMonths) * 100, lane }
    })
}

export interface YearTick {
  year: string
  percent: number
}

/** One tick per January inside the horizon, plus the start itself. */
export const yearTicks = (startMonth: string, horizonMonths: number): YearTick[] => {
  const [startYear, startNumber] = startMonth.split('-').map(Number)
  const ticks: YearTick[] = [{ year: String(startYear), percent: 0 }]
  for (let index = 1; index < horizonMonths; index++) {
    const absolute = (startNumber ?? 1) - 1 + index
    if (absolute % 12 === 0) {
      ticks.push({ year: String((startYear ?? 0) + absolute / 12), percent: (index / horizonMonths) * 100 })
    }
  }

  return ticks
}

const euros = (amount: number): string => formatCentsRounded(Math.round(amount * 100))

/** The facts an event carries, said plainly: an amount, what it does to the monthly budget, what it does to N. */
export const eventFacts = (event: TimelineEvent): string[] => {
  const facts: string[] = []
  if (event.sublabel) facts.push(event.sublabel)
  if (event.amount_eur !== null) facts.push(euros(event.amount_eur))
  if (event.monthly_impact_eur !== null && event.monthly_impact_eur !== 0) {
    const sign = event.monthly_impact_eur > 0 ? '+' : '−'
    facts.push(`${sign}${euros(Math.abs(event.monthly_impact_eur))} / mois`)
  }
  if (event.n_impact_months !== null && event.n_impact_months !== 0) {
    const months = Math.abs(event.n_impact_months)
    facts.push(`N ${months} mois ${event.n_impact_months < 0 ? 'plus tôt' : 'plus tard'}`)
  }

  return facts
}

/** « Date N projetée · septembre 2045 », or the plain statement that it is not reached. */
export const nDateSentence = (nDate: string | null): string =>
  nDate === null ? "N non atteinte d'ici 20 ans" : `Date N projetée · ${formatMonth(nDate)}`

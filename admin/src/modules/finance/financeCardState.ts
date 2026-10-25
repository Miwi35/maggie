import type { FinanceDashboard } from './useFinanceDashboard'
import type { Rhythm } from './useFinanceScores'

export type HomeRing = Pick<Rhythm, 'status' | 'label' | 'budgetCents' | 'remainingCents' | 'expectedRemainingCents'>

export interface HomeNextProject {
  projectId: string
  name: string
  amountCents: number
  gatheredCents: number
  /** Null when the planning lands it beyond `horizonMonths` (the cushion is being built). */
  purchaseMonth: string | null
  horizonMonths: number
}

export interface FinanceHomeState {
  week: HomeRing
  month: HomeRing
  toQualifyCount: number
  nextProject: HomeNextProject | null
  accountCount: number
  envelopeCount: number
}

const HORIZON_MONTHS = 36

const ring = ({ status, label, budgetCents, remainingCents, expectedRemainingCents }: Rhythm): HomeRing => ({
  status,
  label,
  budgetCents,
  remainingCents,
  expectedRemainingCents,
})

/** The card's state from the Finance dashboard of the current month: the rings, what waits to be qualified, the next project. */
export function composeFinanceState(dashboard: FinanceDashboard): FinanceHomeState {
  const next = dashboard.nextProject
  const beyond = dashboard.projectBeyondHorizon

  return {
    week: ring(dashboard.scores.week.rhythm),
    month: ring(dashboard.scores.month.rhythm),
    toQualifyCount: dashboard.todo.toSortCount,
    nextProject: next
      ? {
          projectId: next.projectId,
          name: next.name,
          amountCents: next.amountCents,
          gatheredCents: next.gatheredCents,
          purchaseMonth: next.purchaseMonth,
          horizonMonths: HORIZON_MONTHS,
        }
      : beyond
        ? { ...beyond, purchaseMonth: null }
        : null,
    accountCount: dashboard.tiles.accounts.accounts.length,
    envelopeCount: dashboard.tiles.budget.envelopes.length,
  }
}

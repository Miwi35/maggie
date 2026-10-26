import type { FinanceDashboard } from './useFinanceDashboard'
import type { Rhythm } from './useFinanceScores'

export type HomeRing = Pick<Rhythm, 'status' | 'label' | 'budgetCents' | 'remainingCents' | 'expectedRemainingCents'>

export interface HomeNextProject {
  projectId: string
  name: string
  amountCents: number
  gatheredCents: number
  purchaseMonth: string
}

export interface FinanceHomeState {
  week: HomeRing
  month: HomeRing
  toQualifyCount: number
  nextProject: HomeNextProject | null
  accountCount: number
  envelopeCount: number
}

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
        }
      : null,
    accountCount: dashboard.tiles.accounts.accounts.length,
    envelopeCount: dashboard.tiles.budget.envelopes.length,
  }
}

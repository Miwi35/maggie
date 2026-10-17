import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReferenceSalary } from './referenceSalary'
import type { BudgetLine } from './useBudgetStatus'
import type { FinanceScores } from './useFinanceScores'

export interface MonthlyFlow {
  month: string
  incomeCents: number
  expenseCents: number
  netCents: number
}

export interface TopPost {
  categoryId: string | null
  categoryName: string | null
  spentCents: number
  previousMonthCents: number
  changeCents: number
}

export interface IndependenceRente {
  categoryId: string
  categoryName: string
  monthlyCents: number
  sharePercent: number
}

/**
 * How far the rentes cover the train de vie (MAG-46). Both sides measured,
 * never declared; the target date and the curve are Premium, so there is no
 * field for them.
 */
export interface IndependenceCounter {
  coveragePercent: number
  lifestyleCents: number
  passiveIncomeCents: number
  gapCents: number
  sampleMonths: number
  isMeasurable: boolean
  hasPassiveIncomeCategories: boolean
  isReached: boolean
  loanPaymentsCents: number
  monthlyNeedCents: number
  coverageWithDebtPercent: number
  nextMilestonePercent: number | null
  nextMilestoneGapCents: number | null
  byCategory: IndependenceRente[]
}

export interface TileAccount {
  id: string
  name: string
  bank: string | null
  balanceCents: number
  currency: string
  isCushion: boolean
  /** The rejected payments of this account not settled yet. */
  rejectionsToSettleCount: number
}

/** The « Comptes » tile: what the accounts hold, and the part set aside. */
export interface AccountsTile {
  totalCents: number
  cushionCents: number
  availableCents: number
  accounts: TileAccount[]
}

export interface TileEnvelope {
  envelopeId: string
  name: string
  budgetCents: number
  consumedCents: number
  isOverspent: boolean
  isOvercommitted: boolean
}

export interface TileCushion {
  state: 'building' | 'recharging' | 'complete'
  targetCents: number
  currentCents: number
  coveragePercent: number
  monthsCovered: number
  isConfigured: boolean
}

export interface ExpectedSalary {
  monthlyCents: number
  source: 'declared' | 'expected' | 'measured'
  /** The next pay day, `YYYY-MM-DD`, when the salary is a recurring operation. */
  expectedOn: string | null
}

/** The « Budget » tile; a null salary or balance means « not known », never zero. */
export interface BudgetTile {
  /** Root envelopes of the month only. */
  envelopes: TileEnvelope[]
  cushion: TileCushion
  expectedSalary: ExpectedSalary | null
  balanceBeforeSalary: { onDate: string; balanceCents: number } | null
}

export interface SavingCapacity {
  monthlyNetIncomeCents: number
  loanPaymentsCents: number
  estimatedLifestyleCents: number
  netCapacityCents: number
  isIncomeKnown: boolean
}

/** The « Planification » tile. */
export interface PlanningTile {
  independence: IndependenceCounter
  savingCapacity: SavingCapacity
}

export interface DashboardTiles {
  accounts: AccountsTile
  budget: BudgetTile
  planning: PlanningTile
}

/** What waits for the owner: the pills of the tiles and the « Revue ce soir » shortcut. */
export interface DashboardTodo {
  toSortCount: number
  rejectionsToSettleCount: number
  /** The review of the month just ended is not done. */
  reviewReady: boolean
  /** What is still to give back to the savings after an advance. */
  advanceToRepayCents: number
}

export interface FinanceDashboard {
  year: number
  month: number
  score: { referenceSalary: ReferenceSalary }
  balance: {
    totalCents: number
    cushionCents: number
    availableCents: number
    accounts: {
      id: string
      name: string
      bank: string | null
      type: string
      balanceCents: number
      currency: string
      isCushion: boolean
    }[]
  }
  monthlyFlows: MonthlyFlow[]
  budgets: BudgetLine[]
  topPosts: TopPost[]
  savingCapacity: SavingCapacity
  independence: IndependenceCounter
  /** The rings and the curve of the month shown: same payload as `/api/finance/scores`. */
  scores: FinanceScores
  tiles: DashboardTiles
  todo: DashboardTodo
}

/** The whole month in one read: the rings, the curve and the three tiles. */
export function useFinanceDashboard(year: number, month: number) {
  const [dashboard, setDashboard] = useState<FinanceDashboard | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  // Two reads can overlap (a Mercure refresh while the period changes): only
  // the last one asked for may write.
  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++latest.current
    setLoading(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch(`/api/finance/dashboard?year=${year}&month=${month}`, {
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (!res.ok) {
        throw new Error(`dashboard answered ${res.status}`)
      }
      const body = (await res.json()) as FinanceDashboard
      if (request === latest.current) {
        setDashboard(body)
        setError(false)
      }
    } catch {
      // keep the previous dashboard, and say the read failed
      if (request === latest.current) {
        setError(true)
      }
    } finally {
      if (request === latest.current) {
        setLoading(false)
      }
    }
  }, [year, month])

  useEffect(() => {
    refresh()
  }, [refresh])

  return { dashboard, loading, error, refresh }
}

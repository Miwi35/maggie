import { useCallback, useEffect, useState } from 'react'
import type { BudgetLine } from './useBudgetStatus'
import type { DailyScore } from './useDailyScore'

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

export interface FinanceDashboard {
  year: number
  month: number
  score: DailyScore
  balance: {
    totalCents: number
    cushionCents: number
    availableCents: number
    accounts: {
      id: string
      name: string
      type: string
      balanceCents: number
      currency: string
      isCushion: boolean
    }[]
  }
  monthlyFlows: MonthlyFlow[]
  budgets: BudgetLine[]
  topPosts: TopPost[]
  savingCapacity: {
    monthlyNetIncomeCents: number
    loanPaymentsCents: number
    estimatedLifestyleCents: number
    netCapacityCents: number
    isIncomeKnown: boolean
  }
}

/** The whole month in one read. */
export function useFinanceDashboard(year: number, month: number) {
  const [dashboard, setDashboard] = useState<FinanceDashboard | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch(`/api/finance/dashboard?year=${year}&month=${month}`, {
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (res.ok) {
        setDashboard((await res.json()) as FinanceDashboard)
      }
    } catch {
      // keep the previous dashboard
    } finally {
      setLoading(false)
    }
  }, [year, month])

  useEffect(() => {
    refresh()
  }, [refresh])

  return { dashboard, loading, refresh }
}

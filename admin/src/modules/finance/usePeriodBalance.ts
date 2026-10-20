import { useCallback, useEffect, useState } from 'react'
import type { PlanningPeriodRef } from './useReviewProgress'
import type { BalanceEnvelope } from './useMonthBalance'

/** One month of the period: what was budgeted, spent and put aside. */
export interface PeriodMonth {
  month: string
  budgetCents: number
  spentCents: number
  savedCents: number
}

/** A planned expense of the period, the stand-in for a project until projects exist. */
export interface PeriodProject {
  id: string
  label: string
  amountCents: number
  currency: string
  month: string
  status: string
  categoryName: string | null
}

/** `GET /api/finance/review/period-balance?month=`: the bilan of the quarter, semester or year a review closes. */
export interface PeriodBalance {
  period: PlanningPeriodRef
  months: PeriodMonth[]
  totals: { budgetCents: number; spentCents: number; savedCents: number }
  envelopes: Array<Omit<BalanceEnvelope, 'envelopeId'> & { categoryId: string }>
  satisfaction: { judgedCents: number; keptCents: number; avoidableCents: number; keptPercent: number | null }
  savings: { savedCents: number; plannedCents: number | null }
  projects: { done: PeriodProject[]; postponed: PeriodProject[] }
}

const headers = () => {
  const token = localStorage.getItem('token')

  return { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }
}

/** Reads the bilan of the period closed by the review of `month`. */
export function usePeriodBalance(month: string) {
  const [balance, setBalance] = useState<PeriodBalance | null>(null)
  const [error, setError] = useState(false)

  const load = useCallback(async () => {
    try {
      const res = await fetch(`/api/finance/review/period-balance?month=${month}`, { headers: headers() })
      if (!res.ok) throw new Error(`period balance answered ${res.status}`)
      setBalance((await res.json()) as PeriodBalance)
      setError(false)
    } catch {
      setError(true)
    }
  }, [month])

  useEffect(() => {
    void load()
  }, [load])

  return { balance, error, retry: () => void load() }
}

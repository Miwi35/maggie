import { useCallback, useEffect, useRef, useState } from 'react'
import type { PeriodScore } from './useFinanceScores'
import type { TileCushion } from './useFinanceDashboard'

export type EnvelopeStatus = 'held' | 'close' | 'over'

export interface BalanceEnvelope {
  envelopeId: string
  name: string
  budgetCents: number
  consumedCents: number
  /** Null for an envelope with no budget: there is no share of nothing. */
  percent: number | null
  status: EnvelopeStatus
}

export interface BalanceSatisfaction {
  judgedCents: number
  keptCents: number
  /** The superfluous amount. */
  avoidableCents: number
  unratedCount: number
  /** Null while nothing is judged: not a 0 %. */
  keptPercent: number | null
}

export interface BalanceSavings {
  savedCents: number
  /** Null when no income is known. */
  plannedCents: number | null
}

/** The balance of one month, as `/api/finance/month-balance` sends it. */
export interface MonthBalance {
  month: string
  isCurrentMonth: boolean
  spending: PeriodScore
  envelopes: BalanceEnvelope[]
  satisfaction: BalanceSatisfaction
  savings: BalanceSavings
  cushion: TileCushion
  /** The lines of the month nobody qualified: they belong to no envelope. */
  toQualify: { count: number; cents: number }
}

export const monthKey = (year: number, month: number): string =>
  `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}`

/** The « État courant » read: one month, one request. */
export function useMonthBalance(year: number, month: number) {
  const [balance, setBalance] = useState<MonthBalance | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  // Two reads can overlap (a Mercure refresh while the month changes): only
  // the last one asked for may write.
  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++latest.current
    setLoading(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch(`/api/finance/month-balance?month=${monthKey(year, month)}`, {
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (!res.ok) {
        throw new Error(`month balance answered ${res.status}`)
      }
      const body = (await res.json()) as MonthBalance
      if (request === latest.current) {
        setBalance(body)
        setError(false)
      }
    } catch {
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

  return { balance, loading, error, refresh }
}

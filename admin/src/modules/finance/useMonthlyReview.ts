import { useCallback, useEffect, useState } from 'react'

export interface PendingSpend {
  id: string
  label: string
  amountCents: number
  currency: string
  bookedAt: string
  categoryId: string | null
  categoryName: string | null
  retrospect: string
}

export interface MonthlyReview {
  year: number
  month: number
  reviewableCents: number
  keptCents: number
  avoidableCents: number
  unratedCents: number
  ratedCount: number
  pendingCount: number
  optimisationScore: number | null
  isComplete: boolean
  pending: PendingSpend[]
  comparison: {
    thisMonthCents: number
    previousMonthCents: number
    recentAverageCents: number
    sameMonthLastYearCents: number
  }
}

export type Verdict = 'keep' | 'avoidable' | 'unrated'

export const VERDICT_LABELS: Record<Verdict, string> = {
  keep: 'À conserver',
  avoidable: "J'aurais pu m'en passer",
  unrated: 'À revoir',
}

function authHeaders(extra: Record<string, string> = {}) {
  const token = localStorage.getItem('token')

  return {
    Accept: 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  }
}

/** The monthly look back, and the verdicts that feed it. */
export function useMonthlyReview(year: number, month: number) {
  const [review, setReview] = useState<MonthlyReview | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetch(`/api/finance/monthly-review?year=${year}&month=${month}`, {
        headers: authHeaders(),
      })
      if (res.ok) {
        setReview((await res.json()) as MonthlyReview)
      }
    } catch {
      // keep the previous review
    } finally {
      setLoading(false)
    }
  }, [year, month])

  const rate = useCallback(
    async (transactionId: string, verdict: Verdict): Promise<boolean> => {
      try {
        const res = await fetch(`/api/transactions/${transactionId}`, {
          method: 'PATCH',
          headers: authHeaders({ 'Content-Type': 'application/merge-patch+json' }),
          body: JSON.stringify({ retrospect: verdict }),
        })
        if (!res.ok) {
          return false
        }
        await refresh()

        return true
      } catch {
        return false
      }
    },
    [refresh],
  )

  useEffect(() => {
    refresh()
  }, [refresh])

  return { review, loading, refresh, rate }
}

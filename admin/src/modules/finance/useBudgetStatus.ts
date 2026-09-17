import { useCallback, useEffect, useState } from 'react'

export interface BudgetLine {
  id: string
  categoryId: string
  categoryName: string
  mode: string
  amountCents: number
  currency: string
  year: number
  month: number | null
  spentCents: number
  committedCents: number
  plannedCents: number
  toArbitrateCents: number
  consumedCents: number
  remainingCents: number
  availableCents: number
  isOverspent: boolean
  isOvercommitted: boolean
}

export interface BudgetStatus {
  year: number
  month: number
  totalBudgetedCents: number
  totalSpentCents: number
  totalCommittedCents: number
  totalPlannedCents: number
  totalConsumedCents: number
  totalRemainingCents: number
  totalAvailableCents: number
  budgets: BudgetLine[]
}

function apiFetch(path: string) {
  const token = localStorage.getItem('token')
  return fetch(path, {
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/** Budget consumption of a period, as computed by the API. */
export function useBudgetStatus(year: number, month: number) {
  const [status, setStatus] = useState<BudgetStatus | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const res = await apiFetch(`/api/finance/budget-status?year=${year}&month=${month}`)
      if (res.ok) {
        setStatus((await res.json()) as BudgetStatus)
      }
    } catch {
      // leave the previous status in place
    } finally {
      setLoading(false)
    }
  }, [year, month])

  useEffect(() => {
    refresh()
  }, [refresh])

  return { status, loading, refresh }
}

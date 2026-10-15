import { useCallback, useEffect, useState } from 'react'

/** What weighs on an envelope, whatever its mode. */
export interface EnvelopeConsumption {
  amountCents: number
  currency: string
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

export interface BudgetLine extends EnvelopeConsumption {
  id: string
  categoryId: string
  categoryName: string
  /** « Loisirs › Bars › Obsydian » */
  categoryPath: string
  /** The envelope of the closest ancestor category that has one, null for a root. */
  parentEnvelopeId: string | null
  mode: string
  year: number
  month: number | null
  /** The season budgeting this month (amountCents is then its amount), null otherwise (MAG-415). */
  season?: { fromMonth: number; toMonth: number; amountCents: number } | null
}

/** A one-off envelope (Hellfest 2026): every spend ever attributed to it, no period. */
export interface OneOffBudgetLine extends EnvelopeConsumption {
  id: string
  name: string
  mode: 'one_off'
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
  /** The monthly envelopes: the month's budget, which the totals add up. */
  budgets: BudgetLine[]
  /** Apart from the month's budget: the annual envelopes of the year, and the one-off ones. */
  outsideMonthlyBudget?: {
    annual: BudgetLine[]
    oneOff: OneOffBudgetLine[]
  }
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

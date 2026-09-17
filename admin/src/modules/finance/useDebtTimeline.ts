import { useCallback, useEffect, useState } from 'react'

export interface LoanSchedule {
  id: string
  name: string
  lender: string | null
  currency: string
  principalRemainingCents: number
  monthlyPaymentCents: number
  annualRateBasisPoints: number
  priority: number
  monthsRemaining: number | null
  freedOn: string | null
  totalInterestCents: number
  endsBeyondHorizon: boolean
}

export interface DebtTimeline {
  horizonMonths: number
  totalPrincipalRemainingCents: number
  totalMonthlyPaymentCents: number
  totalInterestOverHorizonCents: number
  loans: LoanSchedule[]
  reliefByMonth: {
    month: string
    freedCents: number
    cumulativeFreedCents: number
    loans: string[]
  }[]
  savingCapacity: {
    monthlyNetIncomeCents: number
    loanPaymentsCents: number
    estimatedLifestyleCents: number
    netCapacityCents: number
    isIncomeKnown: boolean
  }
}

/** When each fixed charge falls away, and what is left to save. */
export function useDebtTimeline(horizonMonths = 60) {
  const [timeline, setTimeline] = useState<DebtTimeline | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch(`/api/finance/debt-timeline?months=${horizonMonths}`, {
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (res.ok) {
        setTimeline((await res.json()) as DebtTimeline)
      }
    } catch {
      // keep the previous timeline
    } finally {
      setLoading(false)
    }
  }, [horizonMonths])

  useEffect(() => {
    refresh()
  }, [refresh])

  return { timeline, loading, refresh }
}

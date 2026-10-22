import { useEffect, useState } from 'react'

export interface LoanCurve {
  principalRemainingCents: number
  freedOn: string | null
  monthsRemaining: number | null
  totalInterestCents: number
  months: { month: string; remainingCents: number }[]
}

export interface EarlyRepayment {
  amountCents: number
  penaltyMode: string
  penaltyCents: number
  remainingInterestCents: number
  realGainCents: number
}

const headers = (): HeadersInit => {
  const token = localStorage.getItem('token')

  return { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }
}

/** The capital still owed month by month, until the loan ends. Re-read when `version` changes. */
export function useLoanCurve(loanId: string | undefined, version: string) {
  const [curve, setCurve] = useState<LoanCurve | null>(null)

  useEffect(() => {
    if (!loanId) return
    let live = true
    fetch(`/api/finance/loans/${loanId}/schedule`, { headers: headers() })
      .then((res) => (res.ok ? (res.json() as Promise<LoanCurve>) : null))
      .then((body) => live && setCurve(body))
      .catch(() => live && setCurve(null))

    return () => {
      live = false
    }
  }, [loanId, version])

  return curve
}

/**
 * What repaying `amountCents` early would cost and save (the whole capital
 * when null). The API's refusal comes back as `error`, in its own words.
 */
export function useEarlyRepayment(loanId: string | undefined, amountCents: number | null, version: string) {
  const [repayment, setRepayment] = useState<EarlyRepayment | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!loanId) return
    let live = true
    const query = amountCents === null ? '' : `?amountCents=${amountCents}`
    const timer = setTimeout(() => {
      fetch(`/api/finance/loans/${loanId}/early-repayment${query}`, { headers: headers() })
        .then(async (res) => {
          const body = (await res.json().catch(() => ({}))) as EarlyRepayment & { error?: string }
          if (!live) return
          if (res.ok) {
            setRepayment(body)
            setError(null)
          } else {
            setRepayment(null)
            setError(body.error ?? `Erreur ${res.status}`)
          }
        })
        .catch(() => {
          if (!live) return
          setRepayment(null)
          setError('Simulation indisponible.')
        })
    }, 250)

    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [loanId, amountCents, version])

  return { repayment, error }
}

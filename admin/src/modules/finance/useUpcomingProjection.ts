import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'

export type UpcomingSource = 'recurring' | 'planned' | 'committed'

export interface UpcomingItem {
  date: string
  label: string
  /** Signed: positive is income, negative is spending. */
  amountCents: number
  source: UpcomingSource
  categoryId: string | null
  categoryName: string | null
}

export interface UpcomingDay {
  date: string
  incomeCents: number
  expenseCents: number
}

export interface UpcomingWindow {
  from: string
  /** Exclusive. */
  until: string
  incomeCents: number
  /** Positive. */
  expenseCents: number
  netCents: number
  incomes: UpcomingItem[]
  expenses: UpcomingItem[]
  days: UpcomingDay[]
}

export interface UpcomingEnvelope {
  envelopeId: string | null
  categoryId: string
  categoryName: string
  categoryPath: string
  /** What is written for the month. */
  amountCents: number
  committedCents: number
  /** The greater of the two. */
  projectedCents: number
  isOvercommitted: boolean
}

export interface UpcomingMonth extends UpcomingWindow {
  /** `YYYY-MM` */
  month: string
  envelopes: UpcomingEnvelope[]
  envelopesCents: number
  unbudgetedCents: number
  projectedExpenseCents: number
  /** Expected income less projected spending; negative when the month is short. */
  remainingCents: number
}

export interface UpcomingProjection {
  today: string
  week: UpcomingWindow
  month: UpcomingMonth
}

// The finance home's one list: Mercure shares a single stream per distinct list, and over HTTP/1.1 a
// stream too many leaves the page unable to send a request. Envelopes, series and spends move the projection.
export const FINANCE_HOME_TOPICS = [
  '/api/transactions/{id}',
  '/api/accounts/{id}',
  '/api/envelopes/{id}',
  '/api/recurring_operations/{id}',
]

/** What is coming: the week and the month after the current ones, against the adjusted envelopes. */
export function useUpcomingProjection() {
  const [projection, setProjection] = useState<UpcomingProjection | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  // Two reads can overlap (a Mercure refresh while a retry runs): only the last one asked for may write.
  const latest = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++latest.current
    setLoading(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch('/api/finance/upcoming', {
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (!res.ok) {
        throw new Error(`upcoming answered ${res.status}`)
      }
      const body = (await res.json()) as UpcomingProjection
      if (request === latest.current) {
        setProjection(body)
        setError(false)
      }
    } catch {
      // keep the previous projection, and say the read failed
      if (request === latest.current) {
        setError(true)
      }
    } finally {
      if (request === latest.current) {
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useMercure(FINANCE_HOME_TOPICS, () => {
    void refresh()
  })

  return { projection, loading, error, refresh }
}

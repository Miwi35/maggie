import { useCallback, useEffect, useRef, useState } from 'react'

export type TimelineStatus = 'spent' | 'committed' | 'planned' | 'to_arbitrate'

/** One line of an account, as `GET /api/finance/accounts/{id}/timeline` spells it. */
export interface TimelineTransaction {
  id: string
  label: string
  counterpartyName: string | null
  amountCents: number
  currency: string
  status: TimelineStatus
  categoryId: string | null
  transferKind: string
  counterpartAccount: { id: string; name: string } | null
  recurringOperationId: string | null
  recurringIncrease: { percent: number; previousCents: number } | null
}

/** A due date of a recurring operation that never came. */
export interface MissedOccurrence {
  recurringOperationId: string
  label: string
  expectedOn: string
  amountCents: number
}

export interface TimelineDay {
  date: string
  transactions: TimelineTransaction[]
  missedOccurrences: MissedOccurrence[]
}

export interface TimelineAccount {
  id: string
  name: string
  bank: string | null
  type: string
  currency: string
  balanceCents: number
  isCushion: boolean
  isManual: boolean
}

/** One month of one account. `previousMonth` is the month of the latest line before it. */
export interface AccountTimelinePage {
  month: string
  previousMonth: string | null
  account: TimelineAccount
  days: TimelineDay[]
}

export const TIMELINE_ERROR = 'Impossible de lire les transactions du compte'

const fetchMonth = async (accountId: string, month?: string): Promise<AccountTimelinePage> => {
  const token = localStorage.getItem('token')
  const query = month === undefined ? '' : `?month=${month}`
  const res = await fetch(`/api/finance/accounts/${accountId}/timeline${query}`, {
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
  if (!res.ok) throw new Error(`timeline ${res.status}`)

  return (await res.json()) as AccountTimelinePage
}

/**
 * An account day by day (MAG-422). The current month is read first; each
 * `loadPrevious` appends the month of the latest earlier line, so an owner
 * pages back without walking through empty months. Changing the account
 * starts over. `reload` reads every loaded month again.
 */
export function useAccountTimeline(accountId: string | undefined) {
  const [pages, setPages] = useState<AccountTimelinePage[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const pagesRef = useRef<AccountTimelinePage[]>([])
  // An answer for an account the owner has already left must not land.
  const generation = useRef(0)

  const commit = (next: AccountTimelinePage[]) => {
    pagesRef.current = next
    setPages(next)
  }

  const reload = useCallback(async () => {
    if (!accountId) return
    const mine = generation.current
    setLoading(true)
    try {
      // The first page is read without a month so that it follows the calendar;
      // the pages loaded behind it keep their own.
      const loaded = pagesRef.current
      const next = await Promise.all(
        (loaded.length === 0 ? [undefined] : loaded.map((page, index) => (index === 0 ? undefined : page.month))).map(
          (month) => fetchMonth(accountId, month),
        ),
      )
      if (mine !== generation.current) return
      commit(next)
      setError(null)
    } catch {
      if (mine !== generation.current) return
      setError(TIMELINE_ERROR)
    } finally {
      if (mine === generation.current) setLoading(false)
    }
  }, [accountId])

  const loadPrevious = useCallback(async () => {
    const last = pagesRef.current[pagesRef.current.length - 1]
    if (!accountId || !last?.previousMonth) return
    const mine = generation.current
    setLoading(true)
    try {
      const page = await fetchMonth(accountId, last.previousMonth)
      if (mine !== generation.current) return
      commit([...pagesRef.current, page])
      setError(null)
    } catch {
      if (mine !== generation.current) return
      setError(TIMELINE_ERROR)
    } finally {
      if (mine === generation.current) setLoading(false)
    }
  }, [accountId])

  useEffect(() => {
    generation.current += 1
    commit([])
    setError(null)
    void reload()
  }, [reload])

  const last = pages[pages.length - 1]

  return {
    pages,
    account: pages[0]?.account ?? null,
    previousMonth: last?.previousMonth ?? null,
    loading,
    error,
    reload,
    loadPrevious,
  }
}

import { useCallback, useEffect, useRef, useState } from 'react'

export interface SortSuggestion {
  categoryId: string
  categoryPath: string
  source: 'rule' | 'merchant'
  reason: string
}

export interface ToSortTransaction {
  id: string
  label: string
  counterpartyName: string | null
  amountCents: number
  currency: string
  bookedAt: string
  accountId: string
  accountName: string
  suggestion: SortSuggestion | null
}

/** `GET /api/finance/transactions/to-sort`. */
export interface ToSortPile {
  total: number
  transactions: ToSortTransaction[]
}

/** What the owner answers for one line: a category (and maybe a rule), or « it is a transfer ». */
export type SortAnswer =
  | { categoryId: string; alwaysForMerchant?: boolean }
  | { internalTransfer: { counterpartId?: string } }

/** `POST /api/finance/transactions/{id}/sort`. */
export interface SortResult {
  success: boolean
  transactionId: string
  categoryId?: string
  rule: { id: string; labelPattern: string | null } | null
  ruleCreated: boolean
  alsoSorted: number
  remaining: number
}

const LOAD_ERROR = 'Impossible de lire les transactions à trier.'
const SORT_ERROR = "Impossible de classer cette transaction."

const request = (path: string, init: RequestInit = {}) => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

const messageOf = async (res: Response, fallback: string): Promise<string> => {
  const body = (await res.json().catch(() => ({}))) as { error?: string }

  return body.error ?? fallback
}

/** The « À trier » pile, newest first, and the one gesture that takes a line out of it. */
export function useTransactionsToSort() {
  const [pile, setPile] = useState<ToSortPile | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [sortError, setSortError] = useState<string | null>(null)
  const [sorting, setSorting] = useState(false)
  const [lastResult, setLastResult] = useState<SortResult | null>(null)

  // Only the latest read may land: a slow answer must not put a sorted line back.
  const latestRead = useRef(0)

  const load = useCallback(async () => {
    const read = ++latestRead.current
    const current = () => read === latestRead.current
    try {
      const res = await request('/api/finance/transactions/to-sort')
      if (!res.ok) {
        const message = await messageOf(res, LOAD_ERROR)
        if (current()) setError(message)

        return
      }
      const body = (await res.json()) as ToSortPile
      if (current()) {
        setPile(body)
        setError(null)
      }
    } catch {
      if (current()) setError(LOAD_ERROR)
    } finally {
      if (current()) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const retry = useCallback(() => {
    setLoading(true)
    void load()
  }, [load])

  /** Resolves true once the line is sorted and the pile read again; on failure the card stays and `sortError` says why. */
  const sort = useCallback(
    async (transactionId: string, answer: SortAnswer): Promise<boolean> => {
      setSorting(true)
      setSortError(null)
      try {
        const res = await request(`/api/finance/transactions/${transactionId}/sort`, {
          method: 'POST',
          body: JSON.stringify(answer),
        })
        if (!res.ok) {
          setSortError(await messageOf(res, SORT_ERROR))

          return false
        }
        setLastResult((await res.json()) as SortResult)
        await load()

        return true
      } catch {
        setSortError(SORT_ERROR)

        return false
      } finally {
        setSorting(false)
      }
    },
    [load],
  )

  return { pile, loading, error, sortError, sorting, lastResult, retry, sort }
}

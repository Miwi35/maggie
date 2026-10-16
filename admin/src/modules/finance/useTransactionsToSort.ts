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
  /** An expense that is not an internal movement: the only lines a satisfaction can be given to. */
  judgeable: boolean
  retrospect: Satisfaction
}

/** « À conserver », « Superflue », or not said yet. */
export type Satisfaction = 'unrated' | 'keep' | 'avoidable'

/** `GET /api/finance/transactions/to-sort`. */
export interface ToSortPile {
  total: number
  totalCents: number
  transactions: ToSortTransaction[]
}

export interface Qualification {
  mandatory: boolean
  kind: string
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
const SORT_ERROR = "Impossible de qualifier cette transaction."
const QUALIFY_ERROR = "La transaction est classée, mais « obligatoire » et le type n'ont pas pu être enregistrés."
const JUDGE_ERROR = "Impossible d'enregistrer la satisfaction."

const request = (path: string, init: RequestInit & { contentType?: string } = {}) => {
  const { contentType = 'application/json', ...rest } = init
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...rest,
    headers: {
      Accept: 'application/json',
      ...(rest.body ? { 'Content-Type': contentType } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

const messageOf = async (res: Response, fallback: string): Promise<string> => {
  const body = (await res.json().catch(() => ({}))) as { error?: string }

  return body.error ?? fallback
}

/** The « À qualifier » pile, oldest first, and the gestures that take a line out of it or judge it. */
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
      const res = await request('/api/finance/transactions/to-sort?order=oldest')
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

  /**
   * Resolves true once the line is sorted and the pile read again; on failure the card stays and `sortError` says why.
   * A mandatory or a kind chosen with the category is written right after: the sort endpoint knows only the category.
   */
  const sort = useCallback(
    async (transactionId: string, answer: SortAnswer, qualification?: Qualification): Promise<boolean> => {
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
        if (qualification && (qualification.mandatory || qualification.kind !== 'none')) {
          const written = await request(`/api/transactions/${transactionId}`, {
            method: 'PATCH',
            body: JSON.stringify({ qualification }),
            contentType: 'application/merge-patch+json',
          })
          if (!written.ok) setSortError(QUALIFY_ERROR)
        }
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

  /** Merge-patches the transaction; resolves true once written and the pile read again. */
  const patch = useCallback(
    async (transactionId: string, body: Record<string, unknown>, fallback: string): Promise<boolean> => {
      setSorting(true)
      setSortError(null)
      try {
        const res = await request(`/api/transactions/${transactionId}`, {
          method: 'PATCH',
          body: JSON.stringify(body),
          contentType: 'application/merge-patch+json',
        })
        if (!res.ok) {
          const detail = (await res.json().catch(() => ({}))) as { detail?: string; error?: string }
          setSortError(detail.detail ?? detail.error ?? fallback)

          return false
        }
        await load()

        return true
      } catch {
        setSortError(fallback)

        return false
      } finally {
        setSorting(false)
      }
    },
    [load],
  )

  /** Says whether a spend was worth it; saying « unrated » takes the verdict back. */
  const judge = useCallback(
    (transactionId: string, retrospect: Satisfaction) => patch(transactionId, { retrospect }, JUDGE_ERROR),
    [patch],
  )

  return { pile, loading, error, sortError, sorting, lastResult, retry, sort, judge, reload: load }
}

import { useCallback, useEffect, useState } from 'react'
import { bareId } from './envelopeTree'
import type { QualificationValue } from './qualificationKinds'
import type { Satisfaction, SortSuggestion } from './useTransactionsToSort'

/** One line of the review: an unrecognised one to qualify, or one an operation recognised, to check. */
export interface ReviewItem {
  recognised: boolean
  id: string
  label: string
  counterpartyName: string | null
  amountCents: number
  currency: string
  bookedAt: string
  accountId: string
  accountName: string
  /** An expense that is not an internal movement: the only lines a satisfaction can be given to. */
  judgeable: boolean
  retrospect: Satisfaction
  /** Maggie's suggestion, on an unrecognised line. */
  suggestion: SortSuggestion | null
  categoryId: string | null
  categoryPath: string | null
  qualifiedBy: 'rule' | 'series' | null
}

/** `GET /api/finance/review/queue?month=`: the unrecognised lines first, then the recognised ones, each oldest first. */
export interface ReviewQueue {
  month: string
  unrecognisedCount: number
  unrecognisedCents: number
  recognisedCount: number
  items: ReviewItem[]
}

const LOAD_ERROR = 'Impossible de lire les transactions de la revue.'
const SORT_ERROR = 'Impossible de qualifier cette transaction.'
const CONTROL_ERROR = 'Impossible de valider cette transaction.'
const JUDGE_ERROR = "Impossible d'enregistrer la satisfaction."
const CORRECT_ERROR = 'Impossible de corriger cette transaction.'

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
  const body = (await res.json().catch(() => ({}))) as { error?: string; detail?: string }

  return body.error ?? body.detail ?? fallback
}

const patchTransaction = (id: string, body: Record<string, unknown>) => () =>
  request(`/api/transactions/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
    contentType: 'application/merge-patch+json',
  })

const qualifies = (qualification?: QualificationValue) =>
  qualification !== undefined && (qualification.mandatory || qualification.kind !== 'none')

/**
 * The review's queue of `month`, read once: the activity walks it in order, so a
 * line qualified on the way keeps its place (and its counter) instead of leaving
 * the list under the owner's feet. Each gesture patches the line in place.
 */
export function useReviewQueue(month: string) {
  const [queue, setQueue] = useState<ReviewQueue | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [gestureError, setGestureError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      const res = await request(`/api/finance/review/queue?month=${month}`)
      if (!res.ok) {
        setError(await messageOf(res, LOAD_ERROR))

        return
      }
      setQueue((await res.json()) as ReviewQueue)
      setError(null)
    } catch {
      setError(LOAD_ERROR)
    } finally {
      setLoading(false)
    }
  }, [month])

  useEffect(() => {
    void load()
  }, [load])

  const retry = useCallback(() => {
    setLoading(true)
    void load()
  }, [load])

  const patchItem = useCallback((id: string, change: Partial<ReviewItem>) => {
    setQueue((current) =>
      current && { ...current, items: current.items.map((item) => (item.id === id ? { ...item, ...change } : item)) },
    )
  }, [])

  /** Runs the writes of one gesture; resolves true once all are accepted, else `gestureError` says why. */
  const gesture = useCallback(async (writes: (() => Promise<Response>)[], fallback: string): Promise<boolean> => {
    setBusy(true)
    setGestureError(null)
    try {
      for (const write of writes) {
        const res = await write()
        if (!res.ok) {
          setGestureError(await messageOf(res, fallback))

          return false
        }
      }

      return true
    } catch {
      setGestureError(fallback)

      return false
    } finally {
      setBusy(false)
    }
  }, [])

  /** Files an unrecognised line under a category (an IRI or a bare id), with what goes with it. */
  const sort = useCallback(
    async (item: ReviewItem, category: string, categoryPath: string | null, qualification?: QualificationValue) => {
      const categoryId = bareId(category)
      const writes = [
        () =>
          request(`/api/finance/transactions/${item.id}/sort`, {
            method: 'POST',
            body: JSON.stringify({ categoryId }),
          }),
      ]
      if (qualifies(qualification)) {
        writes.push(patchTransaction(item.id, { qualification }))
      }
      const ok = await gesture(writes, SORT_ERROR)
      if (ok) patchItem(item.id, { categoryId, categoryPath })

      return ok
    },
    [gesture, patchItem],
  )

  /** « Accepter »: Maggie's suggestion becomes the line's category. */
  const accept = useCallback(
    (item: ReviewItem) =>
      item.suggestion ? sort(item, item.suggestion.categoryId, item.suggestion.categoryPath) : Promise.resolve(false),
    [sort],
  )

  /** « Valider » on a recognised line: the operation got it right. */
  const control = useCallback(
    (item: ReviewItem) =>
      gesture(
        [
          () =>
            request('/api/finance/review/controls', {
              method: 'POST',
              body: JSON.stringify({ transactionIds: [item.id] }),
            }),
        ],
        CONTROL_ERROR,
      ),
    [gesture],
  )

  /** « Corriger » on a recognised line: qualified by hand, it is no longer the operation's. */
  const correct = useCallback(
    async (item: ReviewItem, category: string, qualification: QualificationValue) => {
      const ok = await gesture([patchTransaction(item.id, { category, qualification })], CORRECT_ERROR)
      if (ok) patchItem(item.id, { categoryId: bareId(category), categoryPath: null, qualifiedBy: null })

      return ok
    },
    [gesture, patchItem],
  )

  /** « À conserver » / « Superflue »; « unrated » takes the verdict back. */
  const judge = useCallback(
    async (item: ReviewItem, retrospect: Satisfaction) => {
      const ok = await gesture([patchTransaction(item.id, { retrospect })], JUDGE_ERROR)
      if (ok) patchItem(item.id, { retrospect })

      return ok
    },
    [gesture, patchItem],
  )

  return { queue, loading, error, gestureError, busy, retry, accept, sort, control, correct, judge }
}

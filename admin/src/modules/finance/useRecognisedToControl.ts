import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'
import type { Satisfaction } from './useTransactionsToSort'
import type { QualificationValue } from './qualificationKinds'

/** One line an operation (a rule or a recurring series) qualified, as the review reads it. */
export interface RecognisedTransaction {
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
  categoryId: string | null
  categoryPath: string | null
  qualifiedBy: 'rule' | 'series' | null
}

/** The lines of one operation; `operationId` is null once the operation was deleted. */
export interface RecognisedGroup {
  operationType: 'rule' | 'series'
  operationId: string | null
  label: string
  count: number
  totalCents: number
  unratedCount: number
  transactions: RecognisedTransaction[]
}

/** `GET /api/finance/review/recognised`. */
export interface RecognisedReview {
  month: string
  total: number
  groups: RecognisedGroup[]
}

const LOAD_ERROR = 'Impossible de lire les transactions reconnues.'
const CONTROL_ERROR = 'Impossible de valider ces transactions.'
const JUDGE_ERROR = "Impossible d'enregistrer la satisfaction."
const CORRECT_ERROR = 'Impossible de corriger cette transaction.'

// A line changed anywhere (a correction made on its sheet, a rule applied) may enter or leave the review.
const TOPICS = ['/api/transactions/{id}']

export const groupKey = (group: Pick<RecognisedGroup, 'operationType' | 'operationId'>) =>
  `${group.operationType}:${group.operationId ?? 'deleted'}`

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

/** The lines the operations qualified this month and nobody checked yet, by operation, and the gestures that check them. */
export function useRecognisedToControl() {
  const [review, setReview] = useState<RecognisedReview | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [gestureError, setGestureError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Only the latest read may land: a slow answer must not put a checked line back.
  const latestRead = useRef(0)

  const load = useCallback(async () => {
    const read = ++latestRead.current
    const current = () => read === latestRead.current
    try {
      const res = await request('/api/finance/review/recognised')
      if (!res.ok) {
        const message = await messageOf(res, LOAD_ERROR)
        if (current()) setError(message)

        return
      }
      const body = (await res.json()) as RecognisedReview
      if (current()) {
        setReview(body)
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

  useMercure(TOPICS, () => {
    void load()
  })

  const retry = useCallback(() => {
    setLoading(true)
    void load()
  }, [load])

  /** Runs one write; on success the review is read again, on failure the lines stay and `gestureError` says why. */
  const gesture = useCallback(
    async (write: () => Promise<Response>, fallback: string): Promise<boolean> => {
      setBusy(true)
      setGestureError(null)
      try {
        const res = await write()
        if (!res.ok) {
          setGestureError(await messageOf(res, fallback))

          return false
        }
        await load()

        return true
      } catch {
        setGestureError(fallback)

        return false
      } finally {
        setBusy(false)
      }
    },
    [load],
  )

  /** Checks these lines; with `keepAll`, the spends nobody judged yet are judged « À conserver » on the way. */
  const control = useCallback(
    (transactionIds: string[], keepAll = false) =>
      gesture(
        () =>
          request('/api/finance/review/controls', {
            method: 'POST',
            body: JSON.stringify(keepAll ? { transactionIds, satisfaction: 'keep' } : { transactionIds }),
          }),
        CONTROL_ERROR,
      ),
    [gesture],
  )

  const patch = useCallback(
    (transactionId: string, body: Record<string, unknown>, fallback: string) =>
      gesture(
        () =>
          request(`/api/transactions/${transactionId}`, {
            method: 'PATCH',
            body: JSON.stringify(body),
            contentType: 'application/merge-patch+json',
          }),
        fallback,
      ),
    [gesture],
  )

  /** Says whether a spend was worth it; saying « unrated » takes the verdict back. */
  const judge = useCallback(
    (transactionId: string, retrospect: Satisfaction) => patch(transactionId, { retrospect }, JUDGE_ERROR),
    [patch],
  )

  /** Qualifies the line by hand: it is no longer the operation's, so it leaves the review. */
  const correct = useCallback(
    (transactionId: string, category: string, qualification: QualificationValue) =>
      patch(transactionId, { category, qualification }, CORRECT_ERROR),
    [patch],
  )

  return { review, loading, error, gestureError, busy, retry, control, judge, correct }
}

import { useCallback, useEffect, useRef, useState } from 'react'
import { linkCounterparty } from './contactsApi'
import { bareId } from './envelopeTree'
import type { QualificationValue } from './qualificationKinds'
import type { Satisfaction } from './satisfaction'

export type { Satisfaction } from './satisfaction'

/** Maggie's proposal for an unrecognised line, and why. */
export interface SortSuggestion {
  categoryId: string
  categoryPath: string
  source: 'rule' | 'merchant'
  reason: string
}

/** How far back the queue reads: one month, since the last review, or everything. */
export type ReviewPeriod = 'month' | 'since_review' | 'all'

/** Which lines the page shows; the queue itself is always read with every state. */
export type ReviewState = 'all' | 'unrecognised' | 'recognised'

/** What `GET /api/finance/review/queue` is asked: none of it for the activity's bare month. */
export interface ReviewQueueFilter {
  /** `YYYY-MM`; sent only for the `month` period (or no period at all). */
  month?: string
  period?: ReviewPeriod
  limit?: number
}

/** What the answer to « Ce virement est » or a sort did to a line this session, for the fiche to say so. */
export type ResolvedKind = 'category' | 'transfer' | 'peer' | 'operation'

/** What the rule that came with a sort did (`POST /api/finance/transactions/{id}/sort`). */
export interface SortResult {
  success: boolean
  transactionId: string
  categoryId?: string
  rule: { id: string; labelPattern: string | null } | null
  ruleCreated: boolean
  alsoSorted: number
  remaining: number
}

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
  qualifiedBySeriesId?: string | null
  qualifiedByRuleId?: string | null
  /** The rule or the series that recognised the line, by name (recognised lines only). */
  operationLabel?: string | null
  /** The person the transfer is attached to (MAG-450). */
  contact?: { id: string; name: string } | null
  /** A transfer no contact claims yet: « Associer à un contact ? » is worth asking. */
  asksForContact?: boolean
  /** The label linking the line to a contact gives them. */
  counterpartyLabel?: string
  /** Why an unrecognised line waits (MAG-453): no category yet, or a transfer with a person not answered yet. */
  reason?: 'category' | 'peer_transfer'
  /** « Ce virement est » is worth asking: a transfer with a person, not answered yet. */
  asksForPeerKind?: boolean
  /** Client side: whether the line was recognised when it entered the list, so it keeps its group when a gesture changes it. */
  listedRecognised?: boolean
}

/** `GET /api/finance/review/queue?month=`: the unrecognised lines first, then the recognised ones, each oldest first. */
export interface ReviewQueue {
  month: string
  unrecognisedCount: number
  unrecognisedCents: number
  recognisedCount: number
  period?: ReviewPeriod
  /** The day the last review closed (`Y-m-d`), for the `since_review` period; null when there was none. */
  sinceReviewDate?: string | null
  total?: number
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

const queueUrl = ({ month, period, limit }: ReviewQueueFilter): string => {
  const params = new URLSearchParams()
  if (period) params.set('period', period)
  if (month && (period === undefined || period === 'month')) params.set('month', month)
  if (limit) params.set('limit', String(limit))

  return `/api/finance/review/queue?${params.toString()}`
}

/** Oldest first; lines of one day keep the order they came in. */
export const byDate = (a: ReviewItem, b: ReviewItem) => (a.bookedAt < b.bookedAt ? -1 : a.bookedAt > b.bookedAt ? 1 : 0)

/**
 * A queue read again while the owner works: every line the answer still holds takes its fresh
 * data, a line it no longer holds (qualified, controlled) stays where it was until the filter
 * changes, and a line it brings that the list did not know joins it by date.
 */
export const mergeQueue = (current: ReviewQueue, fresh: ReviewQueue): ReviewQueue => {
  const freshById = new Map(fresh.items.map((item) => [item.id, item]))
  const known = new Set(current.items.map((item) => item.id))
  const kept = current.items.map((item) => {
    const next = freshById.get(item.id)

    return next ? { ...next, listedRecognised: item.listedRecognised ?? item.recognised } : item
  })
  const added = fresh.items.filter((item) => !known.has(item.id)).map((item) => ({ ...item, listedRecognised: item.recognised }))

  return { ...fresh, items: added.length > 0 ? [...kept, ...added].sort(byDate) : kept }
}

const annotated = (queue: ReviewQueue): ReviewQueue => ({
  ...queue,
  items: queue.items.map((item) => ({ ...item, listedRecognised: item.recognised })),
})

/**
 * The review's queue, read once: the activity and the page walk it in order, so a line
 * qualified on the way keeps its place (and its counter) instead of leaving the list under
 * the owner's feet. Each gesture patches the line in place; `refresh` reads the queue again
 * without moving what is on screen.
 */
export function useReviewQueue(filter: ReviewQueueFilter) {
  const { month, period, limit } = filter
  const [queue, setQueue] = useState<ReviewQueue | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [gestureError, setGestureError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [resolved, setResolved] = useState<Record<string, ResolvedKind>>({})
  const [controlled, setControlled] = useState<ReadonlySet<string>>(new Set())
  const [lastResult, setLastResult] = useState<SortResult | null>(null)

  // Only the latest read may land: a slow answer must not put another period's lines back.
  const latestRead = useRef(0)

  const read = useCallback(
    async (merge: boolean) => {
      const mine = ++latestRead.current
      const current = () => mine === latestRead.current
      try {
        const res = await request(queueUrl({ month, period, limit }))
        if (!res.ok) {
          const message = await messageOf(res, LOAD_ERROR)
          if (current()) setError(message)

          return
        }
        const body = (await res.json()) as ReviewQueue
        if (current()) {
          setQueue((before) => (merge && before ? mergeQueue(before, body) : annotated(body)))
          setError(null)
        }
      } catch {
        if (current()) setError(LOAD_ERROR)
      } finally {
        if (current()) setLoading(false)
      }
    },
    [month, period, limit],
  )

  useEffect(() => {
    // Another period is another list: nothing of the previous one is kept.
    setQueue(null)
    setLoading(true)
    setResolved({})
    setControlled(new Set())
    setLastResult(null)
    void read(false)
  }, [read])

  const retry = useCallback(() => {
    setLoading(true)
    void read(false)
  }, [read])

  /** Reads the queue again, keeping the lines on screen where they are. */
  const refresh = useCallback(() => read(true), [read])

  const patchItem = useCallback((id: string, change: Partial<ReviewItem>) => {
    setQueue((current) =>
      current && { ...current, items: current.items.map((item) => (item.id === id ? { ...item, ...change } : item)) },
    )
  }, [])

  const resolve = useCallback((id: string, kind: ResolvedKind) => {
    setResolved((current) => ({ ...current, [id]: kind }))
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

  /** The sort endpoint's write: it also keeps what the answer says of the rule that came with it. */
  const sortWrite = useCallback(
    (item: ReviewItem, body: Record<string, unknown>, onResult: (result: SortResult | null) => void) => async () => {
      const res = await request(`/api/finance/transactions/${item.id}/sort`, { method: 'POST', body: JSON.stringify(body) })
      if (res.ok) {
        let result: SortResult | null = null
        try {
          result = (await res.json()) as SortResult
        } catch {
          result = null
        }
        onResult(result)
        setLastResult(result)
      }

      return res
    },
    [],
  )

  /**
   * Files an unrecognised line under a category (an IRI or a bare id), with what goes with it.
   * `alwaysForMerchant` asks for a rule on the way; the lines it also files are read back.
   */
  const sort = useCallback(
    async (
      item: ReviewItem,
      category: string,
      categoryPath: string | null,
      qualification?: QualificationValue,
      options: { alwaysForMerchant?: boolean } = {},
    ) => {
      const categoryId = bareId(category)
      let result: SortResult | null = null
      const writes = [
        sortWrite(
          item,
          options.alwaysForMerchant === undefined ? { categoryId } : { categoryId, alwaysForMerchant: options.alwaysForMerchant },
          (answer) => {
            result = answer
          },
        ),
      ]
      if (qualifies(qualification)) {
        writes.push(patchTransaction(item.id, { qualification }))
      }
      const ok = await gesture(writes, SORT_ERROR)
      if (ok) {
        patchItem(item.id, { categoryId, categoryPath })
        resolve(item.id, 'category')
        if (((result as SortResult | null)?.alsoSorted ?? 0) > 0) void refresh()
      }

      return ok
    },
    [gesture, patchItem, resolve, refresh, sortWrite],
  )

  /** « Accepter »: Maggie's suggestion becomes the line's category. */
  const accept = useCallback(
    (item: ReviewItem, options?: { alwaysForMerchant?: boolean }) =>
      item.suggestion
        ? sort(item, item.suggestion.categoryId, item.suggestion.categoryPath, undefined, options)
        : Promise.resolve(false),
    [sort],
  )

  /** « Virement interne »: the line is a movement between the owner's accounts. */
  const markInternalTransfer = useCallback(
    async (item: ReviewItem) => {
      const ok = await gesture([sortWrite(item, { internalTransfer: {} }, () => {})], SORT_ERROR)
      if (ok) resolve(item.id, 'transfer')

      return ok
    },
    [gesture, resolve, sortWrite],
  )

  /** « Valider » on a recognised line: the operation got it right. */
  const control = useCallback(
    async (item: ReviewItem) => {
      const ok = await gesture(
        [
          () =>
            request('/api/finance/review/controls', {
              method: 'POST',
              body: JSON.stringify({ transactionIds: [item.id] }),
            }),
        ],
        CONTROL_ERROR,
      )
      if (ok) setControlled((current) => new Set(current).add(item.id))

      return ok
    },
    [gesture],
  )

  /** « Corriger » on a recognised line: qualified by hand, it is no longer the operation's. */
  const correct = useCallback(
    async (item: ReviewItem, category: string, qualification: QualificationValue, categoryPath: string | null = null) => {
      const ok = await gesture([patchTransaction(item.id, { category, qualification })], CORRECT_ERROR)
      if (ok) patchItem(item.id, { categoryId: bareId(category), categoryPath, qualifiedBy: null, operationLabel: null })

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

  /** Links the line's label to a contact — this transfer and every other one it recognises. Rejects with the API's message. */
  const linkContact = useCallback(
    async (item: ReviewItem, contact: { id: string; name: string }): Promise<void> => {
      await linkCounterparty(contact.id, item.counterpartyLabel ?? item.label)
      patchItem(item.id, { contact: { id: contact.id, name: contact.name }, asksForContact: false })
      void refresh()
    },
    [patchItem, refresh],
  )

  /** « Ce virement est » was saved, or an operation was created from the line: it is handled. */
  const answered = useCallback(
    (item: ReviewItem, kind: Extract<ResolvedKind, 'peer' | 'operation'>) => {
      resolve(item.id, kind)
      if (kind === 'peer') patchItem(item.id, { asksForPeerKind: false })
      void refresh()
    },
    [resolve, patchItem, refresh],
  )

  return {
    queue,
    loading,
    error,
    gestureError,
    busy,
    resolved,
    controlled,
    lastResult,
    retry,
    refresh,
    accept,
    sort,
    markInternalTransfer,
    control,
    correct,
    judge,
    linkContact,
    answered,
  }
}

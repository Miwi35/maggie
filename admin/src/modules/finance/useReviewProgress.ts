import { useCallback, useEffect, useRef, useState } from 'react'

/** The steps of the review, in order; `done` once the last one is left. The two before it only exist at the end of a quarter, a semester or a year (MAG-462). */
export const REVIEW_STEPS = ['review', 'balance', 'envelopes', 'upcoming', 'projects', 'period_balance', 'period_plan', 'done'] as const
export type ReviewStep = (typeof REVIEW_STEPS)[number]

/** What kind of review a month is: the plain monthly one, or the one that closes a longer period. */
export type ReviewLevel = 'month' | 'quarter' | 'semester' | 'year'

/** A quarter (`2027-Q1`), a semester (`2027-S2`) or a year (`2027`), as the API describes it. */
export interface PlanningPeriodRef {
  key: string
  level: Exclude<ReviewLevel, 'month'>
  /** `YYYY-MM`, first month of the period. */
  from: string
  /** `YYYY-MM`, last month of the period. */
  to: string
  months: number
}

/** `GET`/`PUT /api/finance/review/progress`: how far the review of a month went, on any device. */
export interface ReviewProgress {
  month: string
  step: ReviewStep
  handledTransactionIds: string[]
  updatedAt: string | null
  /** The kind of review the month is: the API says, no client works it out. Absent reads as `month`. */
  level?: ReviewLevel
  /** The period this review closes, null for a plain month. */
  period?: PlanningPeriodRef | null
  /** The period it plans, null for a plain month. */
  nextPeriod?: PlanningPeriodRef | null
}

/** What a `PUT` changes: the step replaces, the ids are added to the handled ones. */
export interface ProgressChange {
  step?: ReviewStep
  handledTransactionIds?: string[]
}

const LOAD_ERROR = 'Impossible de lire où en est la revue.'
const SAVE_ERROR = "La progression de la revue n'a pas pu être enregistrée."

const headers = (json = false) => {
  const token = localStorage.getItem('token')

  return {
    Accept: 'application/json',
    ...(json ? { 'Content-Type': 'application/json' } : {}),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  }
}

const messageOf = async (res: Response, fallback: string): Promise<string> => {
  const body = (await res.json().catch(() => ({}))) as { error?: string; detail?: string }

  return body.error ?? body.detail ?? fallback
}

/** Reads the progress of `month` once, and records each step and each transaction left as the review goes. */
export function useReviewProgress(month: string) {
  const [progress, setProgress] = useState<ReviewProgress | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  // Writes go out one after the other, in the order of the gestures: a step written
  // late must not land after the next one.
  const queue = useRef<Promise<unknown>>(Promise.resolve())

  const load = useCallback(async () => {
    try {
      const res = await fetch(`/api/finance/review/progress?month=${month}`, { headers: headers() })
      if (!res.ok) {
        setError(await messageOf(res, LOAD_ERROR))

        return
      }
      setProgress((await res.json()) as ReviewProgress)
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

  /** Records a step or handled transactions; resolves false (and says why) when the API refused. */
  const save = useCallback(
    (change: ProgressChange): Promise<boolean> => {
      const write = async (): Promise<boolean> => {
        try {
          const res = await fetch('/api/finance/review/progress', {
            method: 'PUT',
            headers: headers(true),
            body: JSON.stringify({ month, ...change }),
          })
          if (!res.ok) {
            setSaveError(await messageOf(res, SAVE_ERROR))

            return false
          }
          setProgress((await res.json()) as ReviewProgress)
          setSaveError(null)

          return true
        } catch {
          setSaveError(SAVE_ERROR)

          return false
        }
      }
      const next = queue.current.then(write, write)
      queue.current = next

      return next
    },
    [month],
  )

  return { progress, loading, error, saveError, retry, save }
}

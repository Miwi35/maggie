import { useEffect, useState } from 'react'

export const PREVIEW_DELAY_MS = 300

export interface RulePreviewCriteria {
  labelPattern: string
  matchType: string
  direction: string
  minAmountCents: number | null
  maxAmountCents: number | null
  /** Bare id; null while no category is chosen. */
  categoryId: string | null
  priority: number
  isActive: boolean
  /** Bare id of the rule being edited; null on creation. */
  ruleId: string | null
}

export interface RulePreviewMatch {
  transactionId: string
  label: string
  amountCents: number
  bookedAt: string
  currentCategoryId: string | null
  wouldChange: boolean
}

export interface RulePreviewResult {
  total: number
  changeCount: number
  matches: RulePreviewMatch[]
}

export interface RulePreviewState {
  /** `loading` keeps the previous result on screen until the new one lands. */
  status: 'idle' | 'loading' | 'ready' | 'error'
  result: RulePreviewResult | null
  error: string | null
}

interface Settled {
  key: string
  result: RulePreviewResult | null
  error: string | null
}

const UNAVAILABLE = "L'aperçu est indisponible pour le moment."

/** A refusal whose message came from the API and can be shown as is. */
class PreviewRefused extends Error {}

async function requestPreview(body: string, signal: AbortSignal): Promise<RulePreviewResult> {
  const token = localStorage.getItem('token')
  const res = await fetch('/api/finance/categorization-rules/preview', {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body,
    signal,
  })

  if (!res.ok) {
    const detail = await res.json().then(
      (data: { error?: unknown }) => (typeof data.error === 'string' ? data.error : null),
      () => null,
    )
    throw new PreviewRefused(detail ?? UNAVAILABLE)
  }

  return (await res.json()) as RulePreviewResult
}

/**
 * The transactions a rule would catch, refreshed as its criteria are typed.
 *
 * A request leaves 300 ms after the last keystroke, and the previous one is
 * aborted: a slow answer to "CARRE" must never overwrite the one for
 * "CARREFOUR". Without a pattern there is nothing to ask.
 */
export function useCategorizationRulePreview(
  criteria: RulePreviewCriteria,
  delayMs = PREVIEW_DELAY_MS,
): RulePreviewState {
  const [settled, setSettled] = useState<Settled | null>(null)
  const key = JSON.stringify(criteria)
  const blank = criteria.labelPattern.trim() === ''

  useEffect(() => {
    if (blank) {
      return
    }

    const controller = new AbortController()
    const timer = setTimeout(() => {
      requestPreview(key, controller.signal).then(
        (result) => {
          if (!controller.signal.aborted) {
            setSettled({ key, result, error: null })
          }
        },
        (e: unknown) => {
          if (!controller.signal.aborted) {
            setSettled({ key, result: null, error: e instanceof PreviewRefused ? e.message : UNAVAILABLE })
          }
        },
      )
    }, delayMs)

    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [key, blank, delayMs])

  if (blank) {
    return { status: 'idle', result: null, error: null }
  }

  if (settled?.key === key) {
    return settled.error === null
      ? { status: 'ready', result: settled.result, error: null }
      : { status: 'error', result: null, error: settled.error }
  }

  return { status: 'loading', result: settled?.result ?? null, error: null }
}

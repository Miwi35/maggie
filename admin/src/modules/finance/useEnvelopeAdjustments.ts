import { useCallback, useEffect, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'

export interface Suggestion {
  proposedCents: number
  direction: 'raise' | 'lower'
  reason: string
  seasonal: { fromMonth: number; toMonth: number } | null
}

export interface AdjustmentLine {
  envelopeId: string
  categoryId: string
  categoryName: string
  categoryPath: string
  currency: string
  /** What the read month was budgeted. */
  amountCents: number
  actualCents: number
  targetEnvelopeId: string | null
  /** What the next month is budgeted, 0 while it has no envelope. */
  targetAmountCents: number
  /** Only for the envelopes that do not fit what was spent. */
  suggestion: Suggestion | null
}

export interface EnvelopeAdjustments {
  month: string
  targetMonth: string
  suggestionCount: number
  envelopes: AdjustmentLine[]
}

export interface Adjustment {
  categoryId: string
  amountCents: number
  seasonal?: boolean
}

export type ApplyOutcome = { ok: true } | { ok: false; error: string }

// Spends move the gauges, envelopes and categories move the tree itself.
// One list for the whole budget screen: `useMercure` opens a stream per distinct list, and over
// HTTP/1.1 a sixth stream leaves the page unable to send any request.
export const BUDGET_TOPICS = ['/api/envelopes/{id}', '/api/transactions/{id}', '/api/categories/{id}']

const headers = (extra: Record<string, string> = {}) => {
  const token = localStorage.getItem('token')

  return { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...extra }
}

/** Reads the envelopes of `month` (remount it to read another) against what was spent, and writes the next month's amounts — only on request. */
export function useEnvelopeAdjustments(month: string) {
  const [data, setData] = useState<EnvelopeAdjustments | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState(false)
  const [saving, setSaving] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const res = await fetch(`/api/finance/envelope-adjustments?month=${month}`, { headers: headers() })
      if (!res.ok) {
        setLoadError(true)

        return
      }
      setData((await res.json()) as EnvelopeAdjustments)
      setLoadError(false)
    } catch {
      setLoadError(true)
    } finally {
      setLoading(false)
    }
  }, [month])

  const apply = useCallback(
    async (adjustments: Adjustment[]): Promise<ApplyOutcome> => {
      setSaving(true)
      try {
        const res = await fetch('/api/finance/envelope-adjustments', {
          method: 'POST',
          headers: headers({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ month, adjustments }),
        })
        if (!res.ok) {
          const body = (await res.json().catch(() => ({}))) as { error?: string }

          return { ok: false, error: body.error ?? "Impossible d'enregistrer les montants." }
        }
        await refresh()

        return { ok: true }
      } catch {
        return { ok: false, error: "Impossible d'enregistrer les montants." }
      } finally {
        setSaving(false)
      }
    },
    [month, refresh],
  )

  useEffect(() => {
    void refresh()
  }, [refresh])

  useMercure(BUDGET_TOPICS, () => {
    void refresh()
  })

  return { data, loading, loadError, saving, apply, refresh }
}

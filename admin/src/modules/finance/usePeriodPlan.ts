import { useCallback, useEffect, useState } from 'react'
import type { PlanningPeriodRef } from './useReviewProgress'

/** A planned expense the period ahead already holds. */
export interface PlannedEvent {
  id: string
  label: string
  amountCents: number
  currency: string
  month: number
  status: string
}

/** One category of the plan: what the previous period cost, what is decided, what is proposed. */
export interface PlanCategory {
  categoryId: string
  categoryName: string
  currency: string
  lastYear: { consumedCents: number }
  /** What the envelopes already set on the period add up to; null when there is none. */
  envelopeCents: number | null
  decidedCents: number
  suggestedCents: number
  suggestedMonthlyCents: number
  plannedEvents: PlannedEvent[]
}

/** `GET /api/finance/annual-plan?period=`: the planning session of a period. */
export interface PeriodPlan {
  period: PlanningPeriodRef
  sourcePeriod: PlanningPeriodRef
  totalSuggestedCents: number
  totalEnvelopedCents: number
  categories: PlanCategory[]
}

/** One line of what is applied; see `ApplyPeriodPlan` on the API side. */
export type PlanItem =
  | { kind: 'annual' | 'monthly'; categoryId: string; amountCents: number; currency: string }
  | { kind: 'one_off'; name: string; amountCents: number; currency: string }

export type ApplyResult = { ok: true } | { ok: false; message: string; index: number | null }

const APPLY_ERROR = "Le plan n'a pas pu être appliqué."

const headers = (json = false) => {
  const token = localStorage.getItem('token')

  return {
    Accept: 'application/json',
    ...(json ? { 'Content-Type': 'application/json' } : {}),
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  }
}

/** Reads the plan of `period` and applies it in one gesture: all of it is written, or none. */
export function usePeriodPlan(period: string) {
  const [plan, setPlan] = useState<PeriodPlan | null>(null)
  const [error, setError] = useState(false)
  const [applying, setApplying] = useState(false)

  const load = useCallback(async () => {
    try {
      const res = await fetch(`/api/finance/annual-plan?period=${period}`, { headers: headers() })
      if (!res.ok) throw new Error(`plan answered ${res.status}`)
      setPlan((await res.json()) as PeriodPlan)
      setError(false)
    } catch {
      setError(true)
    }
  }, [period])

  useEffect(() => {
    void load()
  }, [load])

  const apply = useCallback(
    async (items: PlanItem[]): Promise<ApplyResult> => {
      setApplying(true)
      try {
        const res = await fetch('/api/finance/annual-plan', {
          method: 'POST',
          headers: headers(true),
          body: JSON.stringify({ period, items }),
        })
        if (!res.ok) {
          const body = (await res.json().catch(() => ({}))) as { error?: string; index?: number | null }

          return { ok: false, message: body.error ?? APPLY_ERROR, index: body.index ?? null }
        }
        await load()

        return { ok: true }
      } catch {
        return { ok: false, message: APPLY_ERROR, index: null }
      } finally {
        setApplying(false)
      }
    },
    [period, load],
  )

  return { plan, error, applying, apply, retry: () => void load() }
}

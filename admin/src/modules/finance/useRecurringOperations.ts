import { useCallback, useEffect, useState } from 'react'
import { idOf, seriesIri } from './recurringOperations'
import type { AcceptResult, AttachReport, RecurringOverview, RecurringSuggestion } from './recurringOperations'

const request = async (path: string, init: RequestInit = {}) => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init.headers as Record<string, string> | undefined),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
}

/** The series with what they cost, and the attachments waiting for a yes — one read, shared with Maggie. */
export function useRecurringOverview() {
  const [overview, setOverview] = useState<RecurringOverview | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const res = await request('/api/finance/recurring-operations/overview')
      if (!res.ok) {
        setError('Impossible de lire les opérations récurrentes')

        return
      }
      setOverview((await res.json()) as RecurringOverview)
      setError(null)
    } catch {
      setError('Impossible de lire les opérations récurrentes')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return { overview, error, reload: load }
}

/** The series the bank history implies; none exists until the owner accepts it. */
export function useRecurringSuggestions() {
  const [suggestions, setSuggestions] = useState<RecurringSuggestion[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const res = await request('/api/finance/recurring-operations/suggestions')
      if (!res.ok) {
        setError('Impossible de lire les suggestions')

        return
      }
      setSuggestions(((await res.json()) as { suggestions: RecurringSuggestion[] }).suggestions)
      setError(null)
    } catch {
      setError('Impossible de lire les suggestions')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const accept = useCallback(async (accepted: RecurringSuggestion[]): Promise<AcceptResult | null> => {
    try {
      const res = await request('/api/finance/recurring-operations/suggestions/accept', {
        method: 'POST',
        body: JSON.stringify({ suggestions: accepted }),
      })

      return res.ok ? ((await res.json()) as AcceptResult) : null
    } catch {
      return null
    }
  }, [])

  return { suggestions, error, reload: load, accept }
}

/**
 * The catch-up over the history, next to the one of the categorization rules.
 * A rehearsal (`dryRun`) writes nothing and says what it would attach.
 */
export function useAttachRecurringOperations() {
  const [running, setRunning] = useState(false)

  const attach = useCallback(async (dryRun: boolean): Promise<AttachReport | null> => {
    setRunning(true)
    try {
      const res = await request('/api/finance/recurring-operations/attach', {
        method: 'POST',
        body: JSON.stringify({ dryRun }),
      })

      return res.ok ? ((await res.json()) as AttachReport) : null
    } catch {
      return null
    } finally {
      setRunning(false)
    }
  }, [])

  return { attach, running }
}

/**
 * The owner's hand on one line: attach it to an occurrence, or take it off.
 * Both seal the line as manual, so the automatic pass leaves it alone for good.
 */
export function useRecurringLink() {
  const [saving, setSaving] = useState(false)

  const patch = useCallback(async (transactionId: string, body: Record<string, unknown>): Promise<boolean> => {
    setSaving(true)
    try {
      const res = await request(`/api/transactions/${idOf(transactionId)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/merge-patch+json' },
        body: JSON.stringify(body),
      })

      return res.ok
    } catch {
      return false
    } finally {
      setSaving(false)
    }
  }, [])

  const attach = useCallback(
    (transactionId: string, seriesId: string, occurrenceOn: string | null | undefined) =>
      patch(transactionId, {
        recurringOperation: seriesIri(seriesId),
        ...(occurrenceOn ? { recurringOccurrenceOn: occurrenceOn.slice(0, 10) } : {}),
      }),
    [patch],
  )

  const detach = useCallback(
    (transactionId: string) => patch(transactionId, { recurringOperation: null, recurringOccurrenceOn: null }),
    [patch],
  )

  return { attach, detach, saving }
}

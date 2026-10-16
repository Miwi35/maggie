import { useCallback, useEffect, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'

export interface ToQualifySummary {
  count: number
  cents: number
}

const TOPICS = ['/api/transactions/{id}']

/** How many lines still wait for a qualification and what they weigh; null until known, or when it cannot be read. */
export function useToQualifySummary(): ToQualifySummary | null {
  const [summary, setSummary] = useState<ToQualifySummary | null>(null)

  const load = useCallback(async () => {
    try {
      const token = localStorage.getItem('token')
      const res = await fetch('/api/finance/transactions/to-sort?limit=1', {
        headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      })
      if (!res.ok) return
      const body = (await res.json()) as { total?: unknown; totalCents?: unknown }
      if (typeof body.total === 'number' && typeof body.totalCents === 'number') {
        setSummary({ count: body.total, cents: body.totalCents })
      }
    } catch {
      // The pill is a shortcut: when the count cannot be read it stays out of the way.
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  useMercure(TOPICS, () => {
    void load()
  })

  return summary
}

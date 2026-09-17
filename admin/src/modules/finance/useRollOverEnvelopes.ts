import { useCallback, useState } from 'react'

export interface RollOverResult {
  created: number
  skipped: number
}

interface RollOverParams {
  fromYear: number
  fromMonth: number | null
  year: number
  month: number | null
  useActualSpending?: boolean
}

/** Copies the envelopes of one period onto another, on demand. */
export function useRollOverEnvelopes() {
  const [running, setRunning] = useState(false)

  const rollOver = useCallback(async (params: RollOverParams): Promise<RollOverResult | null> => {
    setRunning(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch('/api/finance/rollover-envelopes', {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify(params),
      })
      if (!res.ok) {
        return null
      }

      return (await res.json()) as RollOverResult
    } catch {
      return null
    } finally {
      setRunning(false)
    }
  }, [])

  return { rollOver, running }
}

import { useCallback, useState } from 'react'

export interface ApplyResult {
  categorized: number
  scanned: number
}

/** Runs the rule engine over the uncategorized history, on demand. */
export function useApplyCategorizationRules() {
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<ApplyResult | null>(null)

  const apply = useCallback(async (): Promise<ApplyResult | null> => {
    setRunning(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch('/api/finance/apply-categorization-rules', {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })
      if (!res.ok) {
        return null
      }
      const data = (await res.json()) as ApplyResult
      setResult(data)

      return data
    } catch {
      return null
    } finally {
      setRunning(false)
    }
  }, [])

  return { apply, running, result }
}

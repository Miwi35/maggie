import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'
import { ApiStatusError, apiUrl, headers } from '../calendar/taskViews'
import { HOME_RESOURCE_TOPICS } from '../home/homeResourceTopics'
import { composeFinanceState } from './financeCardState'
import type { FinanceHomeState } from './financeCardState'
import type { FinanceDashboard } from './useFinanceDashboard'

async function read(): Promise<FinanceHomeState> {
  const response = await fetch(`${apiUrl}/finance/dashboard`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) throw new ApiStatusError(`Tableau de bord : ${response.status}`, response.status)

  return composeFinanceState((await response.json()) as FinanceDashboard)
}

/**
 * What the Finance card shows, composed from the Finance dashboard of the current month (one request) and read
 * again when a resource behind it publishes. A refresh keeps the previous state, the last read wins.
 */
export function useFinanceCardState() {
  const [state, setState] = useState<FinanceHomeState | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const latest = useRef(0)

  const load = useCallback(async (initial: boolean) => {
    const request = ++latest.current
    if (initial) setLoading(true)
    try {
      const body = await read()
      if (request === latest.current) {
        setState(body)
        setError(false)
      }
    } catch {
      if (request === latest.current) setError(true)
    } finally {
      if (request === latest.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load(true)
    const reads = latest
    return () => {
      reads.current++
    }
  }, [load])

  useMercure(HOME_RESOURCE_TOPICS, () => {
    void load(false)
  })

  const retry = useCallback(() => load(true), [load])

  return { state, loading, error, retry }
}

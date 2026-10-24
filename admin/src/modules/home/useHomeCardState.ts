import { useCallback, useEffect, useRef, useState } from 'react'
import { getToken } from '../../auth/session'
import { useMercure } from '../../hooks/useMercure'
import type { HomeDay } from './homeDay'

/**
 * Every card of the Accueil listens on one stream, `home/{module}` for all modules.
 *
 * Over HTTP/1.1 a browser keeps six connections per origin and each EventSource
 * holds one for good: with the bell, the chat and the agent feed already open, a
 * stream per card left a third card's Accueil unable to send any request (MAG-473).
 */
const HOME_TOPICS = ['/home/{module}']

/** Whether a `home/{module}` update is for this card; one that cannot be read refreshes it anyway. */
function announces(data: string | undefined, module: string): boolean {
  try {
    const parsed = JSON.parse(data ?? '') as { module?: unknown }
    return typeof parsed.module !== 'string' || parsed.module === module
  } catch {
    return true
  }
}

/**
 * What a card of the Accueil shows: `GET /api/home/{module}?day=…`, read again
 * whenever the module publishes on `home/{module}`.
 *
 * A refresh keeps the previous state on screen (no flash of loading); a failed
 * one says so and keeps it too.
 */
export function useHomeCardState<T>(module: string, day: HomeDay) {
  const [state, setState] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  // Two reads can overlap (a Mercure refresh while the day changes): only the last one asked for may write.
  const latest = useRef(0)

  const load = useCallback(
    async (initial: boolean) => {
      const request = ++latest.current
      if (initial) setLoading(true)
      try {
        const token = getToken()
        const res = await fetch(`/api/home/${encodeURIComponent(module)}?day=${day}`, {
          headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        })
        if (!res.ok) throw new Error(`home answered ${res.status}`)
        const body = (await res.json()) as T
        if (request === latest.current) {
          setState(body)
          setError(false)
        }
      } catch {
        if (request === latest.current) setError(true)
      } finally {
        if (request === latest.current) setLoading(false)
      }
    },
    [module, day],
  )

  useEffect(() => {
    void load(true)
    // Unmounted or re-keyed: a read still in flight must not write.
    const reads = latest
    return () => {
      reads.current++
    }
  }, [load])

  useMercure(HOME_TOPICS, (data) => {
    if (announces(data, module)) void load(false)
  })

  const retry = useCallback(() => load(true), [load])

  return { state, loading, error, retry }
}

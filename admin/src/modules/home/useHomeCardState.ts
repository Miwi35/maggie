import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getToken } from '../../auth/session'
import { useMercure } from '../../hooks/useMercure'
import type { HomeDay } from './homeDay'

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

  const topics = useMemo(() => [`/home/${module}`], [module])
  useMercure(topics, () => {
    void load(false)
  })

  const retry = useCallback(() => load(true), [load])

  return { state, loading, error, retry }
}

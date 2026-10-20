import { useCallback, useEffect, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import { localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { checkRoutine, fetchRoutinesDay, ROUTINE_TOPICS } from './routinesApi'
import type { DayRoutine, RoutinesDay } from './routinesApi'

export interface RoutinesDayState {
  day: RoutinesDay | null
  loading: boolean
  failed: boolean
  /** The routines whose tick is on its way: a second gesture before the answer sends nothing. */
  pending: ReadonlySet<string>
  toggle: (routine: DayRoutine) => void
}

/**
 * The routines due today and their ticks (MAG-512), read again when a routine or a tick is
 * published, so the band, the home widget and the « Routines » tab stay in step.
 */
export function useRoutinesDay(): RoutinesDayState {
  const notify = useNotify()
  const [day, setDay] = useState<RoutinesDay | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const inFlight = useRef(new Set<string>())
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set())

  // Only the answer to the last read is drawn; a tick's own answer wins over a read sent before it.
  const request = useRef(0)

  const load = useCallback(() => {
    const current = ++request.current
    fetchRoutinesDay(localDay(new Date()))
      .then((result) => {
        if (current !== request.current) return
        setDay(result)
        setFailed(false)
      })
      .catch((error: unknown) => {
        if (current !== request.current) return
        console.error(error)
        setFailed(true)
      })
      .finally(() => {
        if (current === request.current) setLoading(false)
      })
  }, [])

  useEffect(load, [load])

  useMercure(ROUTINE_TOPICS, load)

  // A page left open past midnight reads the day again when it comes back.
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') load()
    }
    window.addEventListener('focus', load)
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      window.removeEventListener('focus', load)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [load])

  const toggle = useCallback(
    (routine: DayRoutine) => {
      if (!day || inFlight.current.has(routine.id)) return
      inFlight.current.add(routine.id)
      setPending(new Set(inFlight.current))
      const checked = !routine.checked
      checkRoutine(routine.id, day.date, checked)
        .then(() => {
          request.current++
          setDay((current) =>
            current
              ? {
                  ...current,
                  moments: current.moments.map((moment) => ({
                    ...moment,
                    routines: moment.routines.map((r) => (r.id === routine.id ? { ...r, checked } : r)),
                  })),
                }
              : current,
          )
        })
        .catch((error: Error) => notify(`Erreur : ${error.message}`, { type: 'info' }))
        .finally(() => {
          inFlight.current.delete(routine.id)
          setPending(new Set(inFlight.current))
        })
    },
    [day, notify],
  )

  return { day, loading, failed, pending, toggle }
}

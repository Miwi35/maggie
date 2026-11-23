import { useCallback, useEffect, useRef, useState } from 'react'
import { addDays, localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import type { Trip } from '../calendar/trips'
import { useLiveRead } from '../calendar/calendarLive'
import { ApiStatusError, apiUrl, headers } from '../calendar/taskViews'
import { composeAgendaState } from './cards/agendaCardState'
import type { ApiAgenda, ApiEvent } from './cards/agendaCardState'
import type { AgendaHomeState } from './cards/agendaTimeline'
import { localMidnightIso } from './cards/tasksCardState'
import type { HomeDay } from './homeDay'
import { HOME_RESOURCE_TOPICS } from './homeResourceTopics'

async function members<T>(path: string, label: string): Promise<T[]> {
  const response = await fetch(`${apiUrl}${path}`, { headers: headers() })
  if (!response.ok) throw new ApiStatusError(`${label} : ${response.status}`, response.status)
  const body = (await response.json()) as { member?: T[] } | null

  return body?.member ?? []
}

async function read(day: HomeDay, answeredBefore: boolean): Promise<AgendaHomeState> {
  const now = new Date()
  const date = day === 'today' ? localDay(now) : addDays(localDay(now), 1)
  const period = new URLSearchParams({
    expand: 'true',
    'endAt[after]': localMidnightIso(date),
    'startAt[before]': localMidnightIso(addDays(date, 1)),
    pagination: 'false',
  })

  try {
    const [events, agendas, trips] = await Promise.all([
      members<ApiEvent>(`/events?${period}`, 'Événements'),
      members<ApiAgenda>('/agendas?pagination=false', 'Agendas'),
      // Best effort: a failed trips read gives a card without trips, never an error.
      members<Trip>(`/trips?date=${date}&pagination=false`, 'Trajets').catch(() => [] as Trip[]),
    ])

    return composeAgendaState(date, day === 'today' ? now.getTime() : null, events, agendas, trips)
  } catch (e) {
    // A module that is not there answers 404 from the first read: its card is not drawn, which is not an error.
    // A 404 after an answer is an ingress blip (a deploy): an error, the card keeps what it had (MAG-987).
    if (!answeredBefore && e instanceof ApiStatusError && e.status === 404) return composeAgendaState(date, null, [], [])
    throw e
  }
}

/**
 * What the Agenda card shows, composed from the events (series unfolded by the API) and the agendas,
 * and read again when either publishes. A refresh keeps the previous state, the last read wins.
 */
export function useAgendaCardState(day: HomeDay) {
  const [state, setState] = useState<AgendaHomeState | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const latest = useRef(0)
  const answered = useRef(false)

  const load = useCallback(
    async (initial: boolean) => {
      const request = ++latest.current
      if (initial) setLoading(true)
      try {
        const body = await read(day, answered.current)
        if (request === latest.current) {
          answered.current = true
          setState(body)
          setError(false)
        }
      } catch {
        if (request === latest.current) setError(true)
      } finally {
        if (request === latest.current) setLoading(false)
      }
    },
    [day],
  )

  useEffect(() => {
    answered.current = false
    void load(true)
    const reads = latest
    return () => {
      reads.current++
    }
  }, [load])

  // A burst of messages is one read; `true` skips the catch-up read, the card holds no state to compare.
  useMercure(
    HOME_RESOURCE_TOPICS,
    useLiveRead(() => load(false).then(() => true)),
  )

  const retry = useCallback(() => load(true), [load])

  return { state, loading, error, retry }
}

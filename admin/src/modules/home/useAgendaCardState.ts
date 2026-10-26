import { useCallback, useEffect, useRef, useState } from 'react'
import { addDays, localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
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

async function read(day: HomeDay): Promise<AgendaHomeState> {
  const now = new Date()
  const date = day === 'today' ? localDay(now) : addDays(localDay(now), 1)
  const period = new URLSearchParams({
    expand: 'true',
    'endAt[after]': localMidnightIso(date),
    'startAt[before]': localMidnightIso(addDays(date, 1)),
    pagination: 'false',
  })

  try {
    const [events, agendas] = await Promise.all([
      members<ApiEvent>(`/events?${period}`, 'Événements'),
      members<ApiAgenda>('/agendas?pagination=false', 'Agendas'),
    ])

    return composeAgendaState(date, day === 'today' ? now.getTime() : null, events, agendas)
  } catch (e) {
    // A module that is not there answers 404: its card is not drawn, which is not an error.
    if (e instanceof ApiStatusError && e.status === 404) return composeAgendaState(date, null, [], [])
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

  const load = useCallback(
    async (initial: boolean) => {
      const request = ++latest.current
      if (initial) setLoading(true)
      try {
        const body = await read(day)
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
    [day],
  )

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

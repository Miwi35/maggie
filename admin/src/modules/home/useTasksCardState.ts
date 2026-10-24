import { useCallback, useEffect, useRef, useState } from 'react'
import { addDays, localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { fetchRoutinesDay, ROUTINE_TOPICS } from '../calendar/routinesApi'
import { ApiStatusError, fetchCompletedBetween, fetchOpenDueOn, fetchTaskView } from '../calendar/taskViews'
import { API_PATH as TASK_SETTINGS_PATH, apiFetch } from '../settings/taskSettingsApi'
import type { TaskSettingsResponse } from '../settings/taskSettingsApi'
import { composeTasksState, emptyTasksState, localMidnightIso } from './cards/tasksCardState'
import type { TasksHomeState } from './cards/tasksCardState'
import type { HomeDay } from './homeDay'

// One array for the whole app: the shared stream is keyed on its content, and the hook resubscribes on identity.
const TOPICS = ['/api/tasks/{id}', ...ROUTINE_TOPICS]

const DONE_LINES = 5

const hhmm = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`

const ABSENT = Symbol('absent')

// A module that is not there answers 404: its card is not drawn, which is not an error.
const orAbsent = <T>(read: Promise<T>): Promise<T | typeof ABSENT> =>
  read.catch((e: unknown) => {
    if (e instanceof ApiStatusError && e.status === 404) return ABSENT
    throw e
  })

/** The user's day moments (`GET /api/task-settings/me`), which decide the routines the card shows today. */
async function fetchDayMoments(): Promise<Partial<Record<string, string>>> {
  const response = await apiFetch(TASK_SETTINGS_PATH)
  if (!response.ok) throw new ApiStatusError(`Réglages des tâches : ${response.status}`, response.status)
  const body = (await response.json()) as Partial<TaskSettingsResponse> | null

  return body?.dayMoments ?? {}
}

async function read(day: HomeDay): Promise<TasksHomeState> {
  const now = new Date()
  const today = localDay(now)
  const tomorrow = addDays(today, 1)

  const [open, done, routines, dayMoments] = await Promise.all([
    orAbsent(day === 'today' ? fetchTaskView('today').then((view) => view.member) : fetchOpenDueOn(tomorrow)),
    orAbsent(day === 'today' ? fetchCompletedBetween(localMidnightIso(today), localMidnightIso(tomorrow), DONE_LINES) : Promise.resolve([])),
    orAbsent(fetchRoutinesDay(day === 'today' ? today : tomorrow)),
    orAbsent(day === 'today' ? fetchDayMoments() : Promise.resolve(undefined)),
  ])
  if (open === ABSENT || done === ABSENT || routines === ABSENT || dayMoments === ABSENT) return emptyTasksState(day)

  return composeTasksState(day, { open, done, routines, dayMoments }, hhmm(now))
}

/**
 * What the Tâches card shows, composed from the tasks and routines resources and read again when either
 * publishes. Same contract as `useHomeCardState`: a refresh keeps the previous state, the last read wins.
 */
export function useTasksCardState(day: HomeDay) {
  const [state, setState] = useState<TasksHomeState | null>(null)
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

  useMercure(TOPICS, () => {
    void load(false)
  })

  const retry = useCallback(() => load(true), [load])

  return { state, loading, error, retry }
}

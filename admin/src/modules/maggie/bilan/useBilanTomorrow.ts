import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { addDays } from '../../../dates'
import { ApiStatusError, fetchTask, setTaskDueDate, setTaskSchedule } from '../../calendar/taskViews'
import type { ViewTask } from '../../calendar/taskViews'
import { clock } from '../../home/cards/agendaTimeline'
import { useAgendaCardState } from '../../home/useAgendaCardState'
import { useHealthCardState } from '../../home/useHealthCardState'
import { isReturn } from '../../calendar/trips'
import { getToken } from '../../../auth/session'
import type { BilanSession } from './bilanSession'
import { answeredTrips, plannedForTomorrow, tomorrowSpares, withSwap, withTripAnswer } from './bilanState'
import { lowerFirst, modePhrase, tripQuestion } from './tripQuestions'
import type { TripChoice, TripQuestion } from './tripQuestions'

/** One line of tomorrow's programme: `time` is empty for a whole-day event. */
export interface ProgrammeRow {
  key: string
  time: string
  title: string
  detail: string
}

export interface PlannedRow {
  taskId: string
  title: string
  reason?: string
  fromJournee: boolean
  task: ViewTask
}

export const SWAP_FAILED = (title: string): string => `Impossible d’échanger « ${title} » : réessayez.`
export const PLAN_FAILED = 'Impossible de prévoir vos tâches de demain : réessayez.'
export const TRIP_FAILED = 'Impossible d’envoyer votre réponse : réessayez.'

const sortRows = (a: ProgrammeRow, b: ProgrammeRow): number => {
  if ((a.time === '') !== (b.time === '')) return a.time === '' ? -1 : 1

  return a.time < b.time ? -1 : a.time > b.time ? 1 : 0
}

const sendToMaggie = async (message: string): Promise<boolean> => {
  const token = getToken()
  try {
    const response = await fetch('/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify({ message }),
    })

    return response.ok
  } catch {
    return false
  }
}

/**
 * The Demain step: the programme, the tasks Maggie chose at the creation of the bilan (and the ones left « Demain »
 * at Journée), ⇄ to swap one for the first of the reserve, and the trip questions. « Bonne nuit » plans the shown tasks.
 */
export const useBilanTomorrow = (
  today: string,
  session: BilanSession | null,
  active: boolean,
  amend: (change: (session: BilanSession) => Record<string, unknown>) => void,
  openChat: () => void,
) => {
  const tomorrow = addDays(today, 1)
  const agenda = useAgendaCardState('tomorrow')
  const health = useHealthCardState('tomorrow')
  const [cache, setCache] = useState<Record<string, ViewTask | null>>({})
  const [attempt, setAttempt] = useState(0)
  const [failed, setFailed] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const busy = useRef(false)

  const planned = useMemo(() => (session ? plannedForTomorrow(session) : []), [session])
  const missing = planned.map((entry) => entry.taskId).filter((id) => !(id in cache))
  const missingKey = missing.join(',')

  useEffect(() => {
    if (!active || missingKey === '') return
    let live = true
    Promise.all(
      missingKey.split(',').map(async (id) => {
        try {
          return [id, await fetchTask(id)] as const
        } catch (error) {
          if (error instanceof ApiStatusError && error.status === 404) return [id, null] as const
          throw error
        }
      }),
    )
      .then((entries) => {
        if (!live) return
        setFailed(false)
        setCache((current) => ({ ...current, ...Object.fromEntries(entries) }))
      })
      .catch(() => {
        if (live) setFailed(true)
      })

    return () => {
      live = false
    }
  }, [active, missingKey, attempt])

  const rows: PlannedRow[] = planned.flatMap((entry) => {
    const task = cache[entry.taskId]

    // Done or gone since: neither shown nor planned.
    return task && !task.completedAt ? [{ ...entry, title: task.title || entry.title, task }] : []
  })

  const events = useMemo(() => agenda.state?.events ?? [], [agenda.state])
  const trips = useMemo(() => agenda.state?.trips ?? [], [agenda.state])
  const programme: ProgrammeRow[] = useMemo(() => {
    const live = events.filter((event) => event.status !== 'cancelled')
    const list: ProgrammeRow[] = live.map((event) => ({
      key: `event-${event.id}`,
      time: event.allDay || !event.startAt ? '' : clock(event.startAt, event.timeZone),
      title: event.summary,
      detail: '',
    }))
    for (const trip of trips) {
      const event = live.find((candidate) => candidate.id === trip.event.slice(trip.event.lastIndexOf('/') + 1))
      if (!event) continue
      list.push({
        key: `trip-${trip.id}`,
        time: clock(trip.departAt, event.timeZone),
        title: isReturn(trip) ? `Retour · ${event.summary}` : `Départ pour ${lowerFirst(event.summary)}`,
        detail: `${trip.durationMinutes} min ${modePhrase(trip.mode)}`,
      })
    }
    const workout = health.state?.workout
    if (workout) list.push({ key: `workout-${workout.id}`, time: workout.time, title: `${workout.title} · ${workout.durationMinutes} min`, detail: 'Santé' })

    return list.sort(sortRows)
  }, [events, trips, health.state?.workout])

  const question: TripQuestion | null = useMemo(() => (session ? tripQuestion(events, trips, new Set(answeredTrips(session))) : null), [events, trips, session])

  const swap = useCallback(
    async (taskId: string, title: string) => {
      if (!session || busy.current || !withSwap(session, taskId, title)) return
      busy.current = true
      setMessage(null)
      try {
        const fromJournee = planned.some((entry) => entry.taskId === taskId && entry.fromJournee)
        if (fromJournee) await setTaskDueDate(taskId, null)
        amend((current) => withSwap(current, taskId, title) ?? {})
      } catch {
        setMessage(SWAP_FAILED(title))
      } finally {
        busy.current = false
      }
    },
    [amend, planned, session],
  )

  const answer = useCallback(
    async (choice: TripChoice) => {
      if (!question || busy.current) return
      if (choice.message === null) {
        openChat()

        return
      }
      busy.current = true
      setMessage(null)
      const key = question.key
      amend((current) => withTripAnswer(current, key))
      const sent = await sendToMaggie(choice.message)
      if (!sent) {
        amend((current) => withTripAnswer(current, key, false))
        setMessage(TRIP_FAILED)
      }
      busy.current = false
    },
    [amend, openChat, question],
  )

  /** « Bonne nuit »: the shown tasks get tomorrow as their day (never their due date), a task of the reserve planned tomorrow is released. */
  const plan = useCallback(async (): Promise<boolean> => {
    if (!session) return true
    setMessage(null)
    // Tasks not read yet cannot be planned: the bilan stays open rather than forget them.
    if (missing.length > 0) {
      setMessage(PLAN_FAILED)

      return false
    }
    try {
      for (const row of rows) await setTaskSchedule(row.taskId, { scheduledDate: tomorrow })
      for (const spare of tomorrowSpares(session)) {
        const task = await fetchTask(spare.taskId).catch(() => null)
        if (task && !task.completedAt && task.scheduledDate?.slice(0, 10) === tomorrow) {
          await setTaskSchedule(spare.taskId, { scheduledDate: null, scheduledMoment: null, scheduledTime: null })
        }
      }

      return true
    } catch {
      setMessage(PLAN_FAILED)

      return false
    }
  }, [missing.length, rows, session, tomorrow])

  return {
    date: agenda.state?.date ?? tomorrow,
    events,
    programme,
    programmeError: agenda.error,
    rows,
    loading: missing.length > 0 && !failed,
    failed,
    retry: () => setAttempt((count) => count + 1),
    canSwap: session ? tomorrowSpares(session).length > 0 : false,
    swap,
    question,
    answer,
    message,
    plan,
  }
}

import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'
import { useLiveRead } from '../calendar/calendarLive'
import { localMidnightIso } from '../home/cards/tasksCardState'
import { addDays } from '../../dates'
import { weekRange } from './activitySeries'
import { DAILY_ENERGY_PATH } from './dailyEnergySeries'
import type { DailyEnergy } from './dailyEnergySeries'
import { apiFetch, HEALTH_PROFILE_PATH, HEALTH_PROFILE_TOPICS } from './healthProfileApi'
import type { HealthProfile } from './healthProfileApi'
import type { Workout } from './workoutFormat'
import { WORKOUT_TOPICS } from './workoutsApi'

export interface ActivityDay {
  profile: HealthProfile
  /** J-6 to today. */
  week: DailyEnergy[]
  /** The day's sessions, every source. */
  workouts: Workout[]
  today: string
  now: Date
}

// One array, so the page opens one stream (MAG-473).
const TOPICS = [...HEALTH_PROFILE_TOPICS, ...WORKOUT_TOPICS, '/api/daily-activities/{id}']

async function get<T>(path: string, accept = 'application/json'): Promise<T> {
  const res = await apiFetch(path, { headers: { Accept: accept } })
  if (!res.ok) throw new Error(String(res.status))

  return (await res.json()) as T
}

async function read(): Promise<ActivityDay> {
  const now = new Date()
  const { from, to } = weekRange(now)
  const sessions = new URLSearchParams({
    'startedAt[after]': localMidnightIso(to),
    'startedAt[before]': localMidnightIso(addDays(to, 1)),
    pagination: 'false',
  })
  const [profile, week, workouts] = await Promise.all([
    get<HealthProfile>(HEALTH_PROFILE_PATH),
    get<DailyEnergy[]>(`${DAILY_ENERGY_PATH}?from=${from}&to=${to}`),
    get<{ member?: Workout[] }>(`/api/workouts?${sessions}`, 'application/ld+json'),
  ])

  return { profile, week, workouts: workouts.member ?? [], today: to, now }
}

/**
 * What Santé › Activité shows: the profile, the week's steps and the day's
 * sessions, read again when any of them publishes. A refresh keeps the previous
 * state, the last read wins.
 */
export function useActivityDay() {
  const [state, setState] = useState<ActivityDay | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const latest = useRef(0)

  const load = useCallback(async (initial: boolean) => {
    const request = ++latest.current
    if (initial) {
      setLoading(true)
      setError(false)
    }
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

  useMercure(
    TOPICS,
    useLiveRead(() => load(false).then(() => true)),
  )

  const retry = useCallback(() => load(true), [load])

  return { state, loading, error, retry }
}

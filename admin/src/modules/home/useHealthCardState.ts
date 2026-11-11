import { useCallback, useEffect, useRef, useState } from 'react'
import { addDays, localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { ApiStatusError, apiUrl, headers } from '../calendar/taskViews'
import { composeHealthState } from './cards/healthCardState'
import type { HealthHomeState } from './cards/healthCardState'
import { localMidnightIso } from './cards/tasksCardState'
import type { HomeDay } from './homeDay'
import { HOME_RESOURCE_TOPICS } from './homeResourceTopics'
import type { DailyEnergy } from '../health/dailyEnergySeries'
import type { FastingDay } from '../health/fasting'
import type { HealthProfile } from '../health/healthProfileApi'
import type { DailyBalance } from '../health/nutritionBalance'
import type { Workout } from '../health/workoutFormat'

// A module that is not there answers 404: that part of the card is empty, which is not an error.
async function get<T>(path: string, label: string): Promise<T | null> {
  const response = await fetch(`${apiUrl}${path}`, { headers: headers({ Accept: 'application/ld+json' }) })
  if (response.status === 404) return null
  if (!response.ok) throw new ApiStatusError(`${label} : ${response.status}`, response.status)

  return (await response.json()) as T
}

async function read(day: HomeDay): Promise<HealthHomeState> {
  const today = localDay(new Date())
  const date = day === 'today' ? today : addDays(today, 1)
  const workouts = new URLSearchParams({
    source: 'planned',
    'startedAt[after]': localMidnightIso(date),
    'startedAt[before]': localMidnightIso(addDays(date, 1)),
    'order[startedAt]': 'asc',
    pagination: 'false',
  })

  const [planned, energy, balance, profile, fasting] = await Promise.all([
    get<{ member?: Workout[] }>(`/workouts?${workouts}`, 'Séances'),
    day === 'today' ? get<DailyEnergy[]>(`/health/daily-energy?from=${date}&to=${date}`, 'Pas') : Promise.resolve(null),
    get<DailyBalance>(`/health/daily-balance?date=${date}`, 'Bilan'),
    get<HealthProfile>('/health/profile', 'Profil'),
    get<FastingDay>(`/health/fasting?date=${date}`, 'Jeûne'),
  ])

  return composeHealthState(day, {
    workouts: planned?.member ?? [],
    energy: energy?.[0] ?? null,
    balance,
    profile,
    fasting,
  })
}

/**
 * What the Santé card shows for a day, composed from the module's resources and read again when any of them
 * publishes. A refresh keeps the previous state, the last read wins.
 */
export function useHealthCardState(day: HomeDay) {
  const [state, setState] = useState<HealthHomeState | null>(null)
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

import { useCallback, useEffect, useRef, useState } from 'react'
import { addDays, localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { ApiStatusError, apiUrl, headers } from '../calendar/taskViews'
import { HOME_RESOURCE_TOPICS } from '../home/homeResourceTopics'
import type { HomeDay } from '../home/homeDay'
import { composeNutritionState } from './nutritionCardState'
import type { ApiMeal, ApiMealNeed, NutritionHomeState } from './nutritionCardState'

async function members<T>(path: string, label: string): Promise<T[]> {
  const response = await fetch(`${apiUrl}${path}`, { headers: headers() })
  // A module that is not there answers 404: that part of the card is empty, which is not an error.
  if (response.status === 404) return []
  if (!response.ok) throw new ApiStatusError(`${label} : ${response.status}`, response.status)
  const body = (await response.json()) as { member?: T[] } | null

  return body?.member ?? []
}

async function read(day: HomeDay): Promise<NutritionHomeState> {
  const today = localDay(new Date())
  const date = day === 'today' ? today : addDays(today, 1)
  const meals = new URLSearchParams({ 'date[after]': date, 'date[before]': date, 'order[date]': 'asc', pagination: 'false' })
  const needs = new URLSearchParams({ from: date, to: date, missing: 'true' })

  const [planned, lacking] = await Promise.all([
    members<ApiMeal>(`/meals?${meals}`, 'Repas'),
    members<ApiMealNeed>(`/grocery/recipe-needs?${needs}`, 'Besoins des recettes'),
  ])

  return composeNutritionState(planned, lacking)
}

/**
 * What the Cuisine card shows for a day, composed from that day's meals and what their recipes lack, and read
 * again when any resource behind them publishes. Same contract as `useHomeCardState`: a refresh keeps the previous
 * state, the last read wins.
 */
export function useNutritionCardState(day: HomeDay) {
  const [state, setState] = useState<NutritionHomeState | null>(null)
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

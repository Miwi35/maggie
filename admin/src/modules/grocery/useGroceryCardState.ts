import { useCallback, useEffect, useRef, useState } from 'react'
import { addDays, localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { ApiStatusError, apiUrl, headers } from '../calendar/taskViews'
import { HOME_RESOURCE_TOPICS } from '../home/homeResourceTopics'
import { composeGroceryState } from './groceryCardState'
import type { ApiGroceryItem, ApiRecipeNeed, GroceryHomeState } from './groceryCardState'

async function get<T>(path: string, label: string): Promise<T | null> {
  const response = await fetch(`${apiUrl}${path}`, { headers: headers() })
  // A module that is not there answers 404: that part of the card is empty, which is not an error.
  if (response.status === 404) return null
  if (!response.ok) throw new ApiStatusError(`${label} : ${response.status}`, response.status)

  return (await response.json()) as T
}

/** The open list: the user has a single one, whose own resource embeds its lines. */
async function readItems(): Promise<ApiGroceryItem[]> {
  const query = new URLSearchParams({ 'order[createdAt]': 'DESC', itemsPerPage: '1' })
  const lists = await get<{ member?: { id: string }[] }>(`/grocery_lists?${query}`, 'Listes de courses')
  const id = lists?.member?.[0]?.id
  if (!id) return []
  const list = await get<{ items?: ApiGroceryItem[] }>(`/grocery_lists/${id}`, 'Liste de courses')

  return list?.items ?? []
}

async function readNeeds(today: string): Promise<ApiRecipeNeed[]> {
  const query = new URLSearchParams({ from: today, to: addDays(today, 1), missing: 'true' })
  const needs = await get<{ member?: ApiRecipeNeed[] }>(`/grocery/recipe-needs?${query}`, 'Besoins des recettes')

  return needs?.member ?? []
}

async function read(): Promise<GroceryHomeState> {
  const today = localDay(new Date())
  const [items, needs] = await Promise.all([readItems(), readNeeds(today)])

  return composeGroceryState(today, items, needs)
}

/**
 * What the Courses card shows, composed from the open list and the recipe needs and read again when any
 * resource behind them publishes. It does not follow the day selector. A refresh keeps
 * the previous state, the last read wins.
 */
export function useGroceryCardState() {
  const [state, setState] = useState<GroceryHomeState | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const latest = useRef(0)

  const load = useCallback(async (initial: boolean) => {
    const request = ++latest.current
    if (initial) setLoading(true)
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

  useMercure(HOME_RESOURCE_TOPICS, () => {
    void load(false)
  })

  const retry = useCallback(() => load(true), [load])

  return { state, loading, error, retry }
}

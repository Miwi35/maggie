import { useEffect, useState } from 'react'

export interface RecipeFacts {
  lastMealDate: string | null
  kcalPerServing: number | null
}

const NO_FACTS: Record<string, RecipeFacts> = {}

/**
 * What the table of recipes reads beside each recipe and the recipe does not carry — the day of its
 * last meal and the kcal of one serving (`GET /api/recipe-list-facts`, MAG-629), by recipe id. Read
 * again whenever `listed` changes, a recipe edited elsewhere moving its kcal. A refusal leaves the
 * columns on a dash: the table does not depend on it.
 */
export const useRecipeFacts = (listed: unknown): Record<string, RecipeFacts> => {
  const [facts, setFacts] = useState(NO_FACTS)

  useEffect(() => {
    const controller = new AbortController()
    const token = localStorage.getItem('token')
    fetch('/api/recipe-list-facts', {
      signal: controller.signal,
      headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
      .then(async (response) => {
        if (!response.ok) throw new Error(`GET answered ${response.status}`)
        const body = (await response.json()) as { recipes?: Record<string, RecipeFacts> }
        if (!controller.signal.aborted) setFacts(body.recipes ?? NO_FACTS)
      })
      .catch(() => {})

    return () => controller.abort()
  }, [listed])

  return facts
}

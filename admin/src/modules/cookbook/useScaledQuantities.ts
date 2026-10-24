import { useEffect, useState } from 'react'

export interface ScaledLine {
  id?: string
  ingredientName?: string
  quantity: number
  unit: string
}

export type Scaled = { state: 'idle' } | { state: 'loading' } | { state: 'failed' } | { state: 'ready'; lines: ScaledLine[] }

/**
 * The recipe's quantities for another number of people, as the API scales them
 * (`GET /api/recipes/{id}?guests=N`, MAG-483) — the same rule and rounding as the grocery list. The
 * client computes nothing and nothing is saved. Idle while `guests` is the recipe's own servings.
 */
export const useScaledQuantities = (recipeId: string | null, servings: number, guests: number): Scaled => {
  const [scaled, setScaled] = useState<Scaled>({ state: 'idle' })
  const simulating = recipeId !== null && guests !== servings

  useEffect(() => {
    if (!simulating) {
      setScaled({ state: 'idle' })
      return
    }

    const controller = new AbortController()
    setScaled({ state: 'loading' })
    const token = localStorage.getItem('token')
    fetch(`/api/recipes/${recipeId}?guests=${guests}`, {
      signal: controller.signal,
      headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
      .then(async (response) => {
        if (!response.ok) throw new Error(`GET answered ${response.status}`)
        const body = await response.json()
        if (!controller.signal.aborted) setScaled({ state: 'ready', lines: Array.isArray(body.ingredients) ? body.ingredients : [] })
      })
      .catch(() => {
        if (!controller.signal.aborted) setScaled({ state: 'failed' })
      })

    return () => controller.abort()
  }, [simulating, recipeId, guests])

  return scaled
}

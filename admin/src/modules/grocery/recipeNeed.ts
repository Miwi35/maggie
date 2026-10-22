import { useEffect, useState } from 'react'
import type { RecipeNeed } from './quantityEntry'

const entrypoint = import.meta.env.VITE_API_URL || 'http://localhost/api'

/** One meal of the week and what it needs of the product, in base units. */
export interface MealNeed {
  mealId: string
  name: string
  date: string
  quantity: number
  unit: string
}

export interface RecipeNeedOfWeek {
  recipeNeed: RecipeNeed | null
  meals: MealNeed[]
}

// « mer. 23 oct. » — the day of a meal, read in the owner's calendar.
export const mealDateLabel = (date: string): string => {
  const day = new Date(`${date.slice(0, 10)}T12:00:00`)

  return Number.isNaN(day.getTime()) ? date : day.toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' })
}

const NONE: RecipeNeedOfWeek = { recipeNeed: null, meals: [] }

// What the meals planned over the next seven days need of a product (MAG-573),
// read when the add or edit dialog knows which product it is about.
export const fetchRecipeNeed = async (productId: string): Promise<RecipeNeedOfWeek> => {
  const response = await fetch(`${entrypoint}/grocery/recipe-need/${encodeURIComponent(productId)}`, {
    headers: { Authorization: `Bearer ${localStorage.getItem('token')}` },
  })
  if (!response.ok) return NONE
  const body = (await response.json()) as Partial<RecipeNeedOfWeek>
  const need = body.recipeNeed

  return {
    recipeNeed: need && typeof need.quantity === 'number' && need.quantity > 0 && need.unit ? need : null,
    meals: Array.isArray(body.meals) ? body.meals : [],
  }
}

/**
 * The week's need of a product, none while it loads or when the read fails: it
 * is a hint beside the quantity, never a reason to block the dialog.
 */
export function useRecipeNeed(productId: string | null): RecipeNeedOfWeek {
  const [loaded, setLoaded] = useState<{ productId: string; data: RecipeNeedOfWeek } | null>(null)

  useEffect(() => {
    if (!productId) return
    let current = true
    fetchRecipeNeed(productId)
      .then((data) => current && setLoaded({ productId, data }))
      .catch(() => current && setLoaded({ productId, data: NONE }))

    return () => {
      current = false
    }
  }, [productId])

  return productId && loaded?.productId === productId ? loaded.data : NONE
}

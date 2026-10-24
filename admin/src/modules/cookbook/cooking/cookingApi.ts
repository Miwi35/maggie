import type { RecipeRecord } from '../recipeSheet'

/** A meal as `GET /api/meals/{id}` serves it: its recipes by address, and the guests that count. */
export interface CookingMeal {
  id: string
  recipes: (string | { '@id'?: string; id?: string })[]
  guests: number
}

const idOf = (value: string | { '@id'?: string; id?: string }): string =>
  (typeof value === 'string' ? value : (value['@id'] ?? value.id ?? '')).split('/').pop() ?? ''

export const recipeIdsOf = (meal: CookingMeal): string[] => meal.recipes.map(idOf).filter((id) => id !== '')

const get = async <T>(path: string, signal: AbortSignal): Promise<T> => {
  const token = localStorage.getItem('token')
  const response = await fetch(path, {
    signal,
    headers: { Accept: 'application/ld+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
  })
  if (!response.ok) throw new Error(`GET ${path} answered ${response.status}`)

  return (await response.json()) as T
}

export const fetchMeal = (id: string, signal: AbortSignal) => get<CookingMeal>(`/api/meals/${encodeURIComponent(id)}`, signal)

/** The recipe with its quantities scaled to `servings` by the API (MAG-483): the client computes nothing. */
export const fetchRecipe = (id: string, servings: number | null, signal: AbortSignal) =>
  get<RecipeRecord>(`/api/recipes/${encodeURIComponent(id)}${servings === null ? '' : `?guests=${servings}`}`, signal)

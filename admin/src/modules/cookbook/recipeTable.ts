import { parseDay } from '../../dates'
import type { RecipeRecord } from './recipeSheet'

/** The tags of the mockup, in its order; the owner's other tags follow alphabetically. */
const MOCKUP_TAGS = ['végétarien', 'rapide', 'four', 'dessert']

export interface TagPill {
  /** The tag, lower case: what recipes are matched on. */
  key: string
  label: string
  count: number
}

const capitalised = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1)

export const tagPills = (recipes: Pick<RecipeRecord, 'tags'>[]): TagPill[] => {
  const counts = new Map<string, number>()
  for (const recipe of recipes) {
    for (const key of new Set((recipe.tags ?? []).map((tag) => tag.trim().toLowerCase()).filter(Boolean))) counts.set(key, (counts.get(key) ?? 0) + 1)
  }

  const rank = (key: string) => (MOCKUP_TAGS.includes(key) ? MOCKUP_TAGS.indexOf(key) : MOCKUP_TAGS.length)

  return [...counts.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b, 'fr'))
    .map(([key, count]) => ({ key, label: capitalised(key), count }))
}

const plain = (text: string): string => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()

export const filterRecipes = <T extends Pick<RecipeRecord, 'name' | 'tags'>>(recipes: T[], query: string, tag: string | null): T[] => {
  const wanted = plain(query.trim())

  return recipes.filter(
    (recipe) =>
      (tag === null || (recipe.tags ?? []).some((own) => own.trim().toLowerCase() === tag)) && (wanted === '' || plain(recipe.name).includes(wanted)),
  )
}

/** The time the steps add up to — the ones left to a pot included, as the sheet's title counts them. Null when no step says. */
export const stepsMinutes = (steps: readonly { durationMinutes?: number | null; background?: boolean }[] | undefined): number | null => {
  const total = (steps ?? []).reduce((sum, step) => sum + (step.durationMinutes ?? 0), 0)

  return total > 0 ? total : null
}

/** « 24 oct. »: the day of the last meal; a dash for a recipe never served. */
export const lastMealLabel = (day: string | null | undefined): string => {
  if (!day) return '—'

  return parseDay(day).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })
}

import { localDay } from '../../dates'
import { idOf } from './recipeSheet'
import type { RecipeRecord } from './recipeSheet'

export const MEAL_TOPICS = ['/api/meals/{id}']
export const MEAL_SETTINGS_PATH = '/api/meal-settings/me'
// What the API gives a meal when the user never set a default (MealSettings::DEFAULT_GUESTS).
export const FALLBACK_GUESTS = 2

export const DAYS = ['Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi', 'Dimanche']
export const SLOTS = [
  { value: 'lunch', label: 'Déjeuner' },
  { value: 'dinner', label: 'Dîner' },
]

export type Recipe = RecipeRecord

export interface Meal {
  id: string
  '@id'?: string
  /** The day, `YYYY-MM-DD`. A meal has no time (MAG-251). */
  date: string
  slot: string
  summary?: string
  /** IRIs as the API lists them; objects in a Mercure payload. */
  recipes: (Recipe | string)[] | null
  /** How many people the meal is cooked for, and whether that is the user's default (MAG-482). */
  guests?: number
  guestsIsDefault?: boolean
  /** Null while the owner has not chosen what of the meal goes on the list (MAG-295). */
  groceryChoiceMadeAt?: string | null
}

export const formatDate = localDay

export function getMonday(d: Date): Date {
  const date = new Date(d)
  const day = date.getDay()
  date.setDate(date.getDate() - day + (day === 0 ? -6 : 1))
  date.setHours(0, 0, 0, 0)
  return date
}

export function addDays(d: Date, days: number): Date {
  const result = new Date(d)
  result.setDate(result.getDate() + days)
  return result
}

/** « 20 → 26 octobre », « 28 oct. → 3 nov. », and the year only when the week is not in this one. */
export function weekLabel(weekStart: Date, today: Date): string {
  const end = addDays(weekStart, 6)
  const sameMonth = weekStart.getMonth() === end.getMonth() && weekStart.getFullYear() === end.getFullYear()
  const year = end.getFullYear() === today.getFullYear() ? '' : ` ${end.getFullYear()}`

  if (sameMonth) {
    return `${weekStart.getDate()} → ${end.getDate()} ${end.toLocaleDateString('fr-FR', { month: 'long' })}${year}`
  }

  const short = (d: Date) => `${d.getDate()} ${d.toLocaleDateString('fr-FR', { month: 'short' })}`

  return `${short(weekStart)} → ${short(end)}${year}`
}

export const slotLabel = (slot: string) => SLOTS.find((s) => s.value === slot)?.label ?? slot

/** Chronological: the day, then lunch before dinner. */
export const byDayAndSlot = (a: Meal, b: Meal): number =>
  a.date.localeCompare(b.date) || SLOTS.findIndex((s) => s.value === a.slot) - SLOTS.findIndex((s) => s.value === b.slot)

/** The IRI of a record of `resource`, whatever the data provider made of its id. */
export function iriOf(resource: string, record: { id?: unknown; '@id'?: unknown }): string {
  if (typeof record['@id'] === 'string') return record['@id']
  const id = String(record.id ?? '')

  return id.startsWith('/') ? id : `/api/${resource}/${id}`
}

/** Where a meal that was just created lives, null when the answer says nothing of it. */
export function createdMealIri(created: { id?: unknown; '@id'?: unknown }): string | null {
  if (typeof created['@id'] !== 'string' && (typeof created.id !== 'string' || created.id === '')) return null

  return iriOf('meals', created)
}

/** A recipe of a meal: its IRI, and the recipe behind it when the list of recipes knows it. */
export interface MealRecipe {
  iri: string
  id: string
  recipe: Recipe | null
}

export function mealRecipesOf(meal: Meal, known: Map<string, Recipe>): MealRecipe[] {
  return (meal.recipes ?? []).map((entry) => {
    const iri = typeof entry === 'string' ? entry : iriOf('recipes', entry)
    const id = idOf(iri)
    const embedded = typeof entry === 'string' ? null : entry

    return { iri, id, recipe: known.get(id) ?? (embedded?.name ? embedded : null) }
  })
}

/** How many distinct products the recipes of these meals call for. */
export function distinctProducts(meals: Meal[], known: Map<string, Recipe>): number {
  const products = new Set<string>()

  for (const meal of meals) {
    for (const { recipe } of mealRecipesOf(meal, known)) {
      for (const line of recipe?.ingredients ?? []) {
        products.add(line.ingredient?.['@id'] ?? line.ingredient?.id ?? line.ingredientName)
      }
    }
  }

  return products.size
}

/** The meal to choose the grocery list of: the first one not chosen yet, or the first one. */
export function mealToChoose(meals: Meal[]): Meal | null {
  const sorted = [...meals].sort(byDayAndSlot)

  return sorted.find((meal) => meal.groceryChoiceMadeAt == null) ?? sorted[0] ?? null
}

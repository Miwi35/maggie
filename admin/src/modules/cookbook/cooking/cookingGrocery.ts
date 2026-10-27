import { canonicalRecordId } from '../recipeSheet'
import type { RecipeRecord } from '../recipeSheet'
import { groupByStoreAndAisle } from '../../grocery/groceryAisles'
import type { AisleSection, ListLine } from '../../grocery/groceryAisles'
import { isDeferred } from '../../grocery/groceryListLive'
import type { NeededEntry, PackagedProduct } from '../../grocery/groceryQuantity'
import { bareId } from '../../grocery/recurring'
import type { QueuedRecipe } from './cookingSession'

/** A line of the open list, as the activity reads it (REST sends the product's `@id`, Mercure its `id`). */
export interface CookingGroceryItem extends ListLine {
  label: string
  product?: (PackagedProduct & { id?: string; '@id'?: string }) | null
  quantity?: number | null
  unit?: string | null
  desiredQuantity?: number | null
  desiredUnit?: string | null
  needed?: NeededEntry[] | null
  checked: boolean
  buyAfter?: string | null
}

/** What the meal needs by product, in base units: « 300 g » of this one, « 2 pièces » of that one. */
export type MealNeeds = Map<string, NeededEntry[]>

export interface CookingStore {
  key: string
  name: string
  /** The lines of the meal, in the order of the store's aisles: they open the store. */
  meal: CookingGroceryItem[]
  /** The other lines, aisle by aisle. */
  sections: AisleSection<CookingGroceryItem>[]
}

const BASE: Record<string, [string, number]> = { kg: ['g', 1000], l: ['ml', 1000], cl: ['ml', 10] }

const baseOf = (quantity: number, unit: string): NeededEntry => {
  const [base, factor] = BASE[unit] ?? [unit, 1]

  return { quantity: quantity * factor, unit: base }
}

const keyOf = (id: string): string => canonicalRecordId(bareId(id))

/** The product a line buys, in the one spelling recipes and lines can be compared by. */
export const productKeyOf = (item: CookingGroceryItem): string | null => {
  const raw = item.product?.['@id'] ?? item.product?.id

  return raw ? keyOf(raw) : null
}

/** The sum, per product, of the lines of every recipe of the queue: the recipes arrive scaled to each entry's servings. */
export const mealNeeds = (queue: QueuedRecipe[], recipes: Record<string, RecipeRecord>): MealNeeds => {
  const needs: MealNeeds = new Map()
  for (const entry of queue) {
    for (const line of recipes[entry.recipeId]?.ingredients ?? []) {
      if (!line.ingredient?.id) continue
      const key = keyOf(line.ingredient.id)
      const amount = baseOf(line.quantity, line.unit)
      const entries = needs.get(key) ?? []
      const same = entries.find((existing) => existing.unit === amount.unit)
      if (same) same.quantity += amount.quantity
      else entries.push(amount)
      needs.set(key, entries)
    }
  }

  return needs
}

export const needOf = (item: CookingGroceryItem, needs: MealNeeds): NeededEntry[] | null => {
  const key = productKeyOf(item)

  return key ? (needs.get(key) ?? null) : null
}

/**
 * What the meal needs of a product that the list plans too little of, in the unit the list counts in;
 * null when the list covers it, or when the units cannot be compared (a pack against grams).
 */
export const shortfallOf = (item: CookingGroceryItem, need: NeededEntry[]): NeededEntry | null => {
  const packaged = !!item.product?.packagingUnit
  const listed = packaged ? { quantity: item.desiredQuantity, unit: item.desiredUnit } : { quantity: item.quantity, unit: item.unit }
  if (listed.quantity == null || !listed.unit) return null
  const listedBase = baseOf(listed.quantity, listed.unit)
  const wanted = need.find((entry) => entry.unit === listedBase.unit)

  return wanted && listedBase.quantity + 1e-9 < wanted.quantity ? wanted : null
}

/** What is left to buy today: neither ticked nor put off to a later day. */
export const openCount = (items: CookingGroceryItem[], today: string): number => items.filter((item) => !item.checked && !isDeferred(item, today)).length

/** The list of the day by store in visit order, the lines of the meal first in each store, the rest aisle by aisle. */
export const cookingStores = (items: CookingGroceryItem[], needs: MealNeeds, today: string): CookingStore[] =>
  groupByStoreAndAisle(items.filter((item) => !isDeferred(item, today))).map((group) => {
    const mine = (item: CookingGroceryItem) => needOf(item, needs) !== null
    const sections = group.sections.map((section) => ({ ...section, items: section.items.filter((item) => !mine(item)) })).filter((section) => section.items.length > 0)

    return { key: group.storeId ?? '__unassigned__', name: group.storeName, meal: group.items.filter(mine), sections }
  })

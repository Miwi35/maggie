export type MealSlotName = 'lunch' | 'dinner'

/** A meal of `GET /api/meals`: the title is the owner's when typed, the recipes' names otherwise. */
export interface ApiMeal {
  id: string
  summary?: string
  slot: MealSlotName
  guests?: number
  recipeNames?: string[]
}

/** An entry of `GET /api/grocery/recipe-needs`: one product a meal lacks. */
export interface ApiMealNeed {
  productId: string
  name: string
  mealId: string
}

export interface HomeMeal {
  id: string
  title: string
  guests: number
  recipes: string[]
  missing: string[]
}

export interface HomeSlot {
  slot: MealSlotName
  meals: HomeMeal[]
}

export interface CuisineHomeState {
  slots: HomeSlot[]
}

const SLOTS: readonly MealSlotName[] = ['lunch', 'dinner']

/** The card's state from the day's meals and what they lack. Both slots are always there, an empty one with no meal. */
export function composeCuisineState(meals: ApiMeal[], needs: ApiMealNeed[]): CuisineHomeState {
  const missing = new Map<string, Map<string, string>>()
  for (const need of needs) {
    const products = missing.get(need.mealId) ?? new Map<string, string>()
    products.set(need.productId, need.name)
    missing.set(need.mealId, products)
  }

  return {
    slots: SLOTS.map((slot) => ({
      slot,
      meals: meals
        .filter((meal) => meal.slot === slot)
        .map(
          (meal): HomeMeal => ({
            id: meal.id,
            title: meal.summary ?? '',
            guests: meal.guests ?? 1,
            recipes: meal.recipeNames ?? [],
            missing: [...(missing.get(meal.id)?.values() ?? [])],
          }),
        ),
    })),
  }
}

/** The first line of a meal: its first recipe, its title when it has none. */
export const mealHeadline = (meal: HomeMeal): string => meal.recipes[0] ?? meal.title

/** All the recipes on one line, only from two recipes. */
export const mealRecipesLine = (meal: HomeMeal): string | null => (meal.recipes.length >= 2 ? meal.recipes.join(' · ') : null)

/** « manque : parmesan, basilic », null when nothing lacks. */
export const missingLabel = (meal: HomeMeal): string | null =>
  meal.missing.length > 0 ? `manque : ${meal.missing.map((name) => name.charAt(0).toLowerCase() + name.slice(1)).join(', ')}` : null

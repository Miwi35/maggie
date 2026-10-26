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

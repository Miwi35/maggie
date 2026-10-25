import type { RecipeLine, RecipeStep } from '../recipeSheet'

export interface StepIngredient {
  key: string
  name: string
  line: RecipeLine | null
}

/** The recipe's lines (scaled by the API) of the ingredients a step uses; an ingredient without a line keeps its name. */
export const stepIngredients = (step: RecipeStep, lines: RecipeLine[]): StepIngredient[] =>
  step.ingredients.flatMap((ref): StepIngredient[] => {
    if (typeof ref === 'string') return [{ key: ref, name: ref, line: null }]
    const matching = lines.filter((line) => line.ingredient?.id === ref.id)
    if (matching.length === 0) return [{ key: ref.id ?? ref.name, name: ref.name, line: null }]

    return matching.map((line, index) => ({ key: `${ref.id}-${line.id ?? index}`, name: ref.name, line }))
  })

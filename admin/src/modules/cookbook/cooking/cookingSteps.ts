import type { RecipeLine, RecipeStep } from '../recipeSheet'

const ARTICLE = /^(le|la|les|un|une|des|du|de|[ld]['’].+)$/i
const NAME_WORDS = 5

const joinAnd = (items: string[]): string => (items.length <= 1 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} et ${items[items.length - 1]}`)

/** « Cuire les pâtes » is awaited as « les pâtes »: from the first article on, or the whole text. */
export const awaitedName = (text: string): string => {
  const words = text.trim().split(/\s+/).filter(Boolean)
  const from = words.findIndex((word) => ARTICLE.test(word))
  const picked = (from >= 0 ? words.slice(from) : words).slice(0, NAME_WORDS).join(' ').replace(/[,;.:!?]+$/, '')

  return picked.charAt(0).toLowerCase() + picked.slice(1)
}

/**
 * The earlier steps this one still waits for. Before the timers (MAG-582) a step without a duration is
 * finished once passed, and a timed one only when the cook says « C'est prêt ».
 */
export const pendingWaits = (steps: RecipeStep[], position: number, ready: number[]): number[] =>
  (steps[position - 1]?.waitsFor ?? []).filter((awaited) => {
    const step = steps[awaited - 1]

    return step !== undefined && step.durationMinutes !== null && step.durationMinutes !== undefined && !ready.includes(awaited)
  })

/** « Attend les pâtes et les légumes · étapes 3 et 4 ». */
export const waitLabel = (steps: RecipeStep[], pending: number[]): string => {
  const names = joinAnd(pending.map((position) => awaitedName(steps[position - 1]?.text ?? '')))

  return `Attend ${names} · ${pending.length === 1 ? 'étape' : 'étapes'} ${joinAnd(pending.map(String))}`
}

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

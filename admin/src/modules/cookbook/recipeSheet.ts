import { packagingLabel } from '../grocery/packaging'
import { UNIT_CHOICES } from '../grocery/productChoices'
import { nameOf } from './recipeSteps'
import type { NameRef, RecipeStepDraft } from './recipeSteps'

export { UNIT_CHOICES }

export interface RecipeLine {
  id?: string
  ingredient?: { '@id'?: string; id: string; name: string; ciqualAlimCode?: string | null; inSeason?: boolean | null }
  ingredientName: string
  ciqualAlimCode?: string | null
  quantity: number
  unit: string
  inSeason?: boolean | null
}

export type RecipeStep = RecipeStepDraft & { position?: number }

export interface RecipeRecord {
  id: string
  '@id'?: string
  name: string
  servings?: number
  tags?: string[]
  notes?: string | null
  ingredients?: RecipeLine[]
  utensils?: NameRef[]
  steps?: RecipeStep[]
}

/** What the sheet knows of a product of the catalogue: its nutrition and how it is bought. */
export interface ProductInfo {
  id: string
  name?: string
  kcalPer100g?: number | null
  proteinPer100g?: number | null
  carbsPer100g?: number | null
  fatPer100g?: number | null
  pieceWeightGrams?: number | null
  packagingUnit?: string | null
  packagingSize?: number | null
  packagingSizeUnit?: string | null
}

export const idOf = (value: unknown): string => String(value).split('/').pop() ?? ''

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const UUID_NOTATION = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const ULID_NOTATION = /^[0-9a-z]{26}$/i

/**
 * An id (bare or the end of an IRI) in the one spelling the API names a record by: the
 * upper-case base32 ULID. The API finds a record whatever the notation of the address, but
 * react-admin refuses a record whose id is not the one it asked for.
 */
export const canonicalRecordId = (id: string): string => {
  const cut = id.lastIndexOf('/') + 1
  const tail = id.slice(cut)
  if (UUID_NOTATION.test(tail)) {
    let rest = BigInt(`0x${tail.replaceAll('-', '')}`)
    let base32 = ''
    for (let i = 0; i < 26; i++, rest >>= 5n) base32 = CROCKFORD[Number(rest & 31n)] + base32

    return id.slice(0, cut) + base32
  }

  return ULID_NOTATION.test(tail) ? id.slice(0, cut) + tail.toUpperCase() : id
}

export const productIdOf = (line: RecipeLine): string | null => line.ingredient?.id ?? null

export const durationLabel = (minutes: number): string => {
  if (minutes < 60) return `${minutes} min`
  const rest = minutes % 60
  return `${Math.floor(minutes / 60)} h${rest === 0 ? '' : ` ${String(rest).padStart(2, '0')}`}`
}

/** The lines as the API reads them: the whole list, each line naming its product. */
export const linesPayload = (lines: RecipeLine[]) =>
  lines.map((line) => {
    const product = productIdOf(line)
    return {
      ...(product ? { ingredientId: product } : { ciqualAlimCode: line.ciqualAlimCode }),
      quantity: line.quantity,
      unit: line.unit,
    }
  })

export const utensilNames = (utensils: NameRef[] | undefined): string[] => (utensils ?? []).map(nameOf)

const usesProduct = (step: RecipeStep, product: string) => step.ingredients.some((ref) => typeof ref !== 'string' && ref.id === product)

/** The steps (their positions, 1-based) that use a product of the recipe. */
export const stepsUsing = (steps: RecipeStep[], product: string): number[] =>
  steps.flatMap((step, index) => (usesProduct(step, product) ? [index + 1] : []))

/** The steps without the product: what is sent once the owner agreed to take the links off. */
export const withoutProduct = (steps: RecipeStep[], product: string): RecipeStep[] =>
  steps.map((step) => (usesProduct(step, product) ? { ...step, ingredients: step.ingredients.filter((ref) => typeof ref === 'string' || ref.id !== product) } : step))

/** The steps in the order of their position. */
export const orderedSteps = (steps: RecipeStep[] | undefined): RecipeStep[] =>
  [...(steps ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0))

/** « ét. 2 · 6 »: the short form of the steps that use an ingredient, for a line of the list. */
export const stepRefLabel = (steps: number[]): string | null => (steps.length === 0 ? null : `ét. ${steps.join(' · ')}`)

/** « Étapes · 3 h 17, dont 2 h 30 en fond »: the time the steps add up to, and the part left to a pot. */
export const stepsTitle = (steps: RecipeStep[]): string => {
  const total = steps.reduce((sum, step) => sum + (step.durationMinutes ?? 0), 0)
  if (total === 0) return 'Étapes'
  const background = steps.reduce((sum, step) => sum + (step.background ? (step.durationMinutes ?? 0) : 0), 0)

  return `Étapes · ${durationLabel(total)}${background > 0 ? `, dont ${durationLabel(background)} en fond` : ''}`
}

/** « 4 portions » for the title of the ingredients. */
export const servingsLabel = (servings: number): string => `${servings} portion${servings > 1 ? 's' : ''}`

const ARTICLES = new Set(['le', 'la', 'les', 'un', 'une', 'du', 'de', 'des', 'd', 'l'])

/** The short name of a pastille: « Bœuf » for « Bœuf (paleron) », « Planche » for « Planche à découper ». */
export const shortName = (name: string): string => {
  const words = name
    .split(/[\s’']+/)
    .map((word) => word.replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, ''))
    .filter(Boolean)
  const first = words.find((word) => !ARTICLES.has(word.toLowerCase()))
  const short = first ?? words[0] ?? name

  return short.charAt(0).toUpperCase() + short.slice(1)
}

export const stepLabel = (steps: number[]): string => (steps.length === 1 ? `étape ${steps[0]}` : `étapes ${steps.join(', ')}`)

export const productPackaging = (product: ProductInfo | undefined): string | null =>
  product ? packagingLabel(product.packagingUnit, product.packagingSize, product.packagingSizeUnit) : null

// The recipe's weights and volumes read as grams (a millilitre of food weighs about a gram); a
// piece weighs what its product says (MAG-647). Any other count (bunches, cans) has no weight the
// app knows, so it is left out and the sheet says so.
const GRAMS: Record<string, number> = { g: 1, kg: 1000, ml: 1, cl: 10, l: 1000 }

const gramsOf = (line: RecipeLine, product: ProductInfo | undefined): number | undefined => {
  if (line.unit === 'piece') return product?.pieceWeightGrams && product.pieceWeightGrams > 0 ? line.quantity * product.pieceWeightGrams : undefined
  const perUnit = GRAMS[line.unit]
  return perUnit === undefined ? undefined : line.quantity * perUnit
}

export interface Nutrition {
  kcal: number
  protein: number
  carbs: number
  fat: number
  /** Lines the figures leave out: a product with no nutrition, or counted in pieces / bunches with no weight. */
  skipped: number
}

export const nutritionPerServing = (lines: RecipeLine[], servings: number, products: Map<string, ProductInfo>): Nutrition | null => {
  if (lines.length === 0 || servings <= 0) return null

  const total = { kcal: 0, protein: 0, carbs: 0, fat: 0 }
  let skipped = 0
  let counted = 0
  for (const line of lines) {
    const product = products.get(productIdOf(line) ?? '')
    const grams = gramsOf(line, product)
    if (!product || grams === undefined || product.kcalPer100g == null) {
      skipped += 1
      continue
    }
    const factor = grams / 100
    total.kcal += factor * product.kcalPer100g
    total.protein += factor * (product.proteinPer100g ?? 0)
    total.carbs += factor * (product.carbsPer100g ?? 0)
    total.fat += factor * (product.fatPer100g ?? 0)
    counted += 1
  }
  if (counted === 0) return null

  return {
    kcal: Math.round(total.kcal / servings),
    protein: Math.round(total.protein / servings),
    carbs: Math.round(total.carbs / servings),
    fat: Math.round(total.fat / servings),
    skipped,
  }
}

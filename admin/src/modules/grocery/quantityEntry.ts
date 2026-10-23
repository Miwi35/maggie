import { unitLabel } from './groceryQuantity'
import type { PackagedProduct } from './groceryQuantity'
import { quantityLabel } from './packaging'

// What the add and edit dialogs let one type for a line (MAG-573): either a
// quantity with its unit, or — for a product bought in a packaging — a number of
// packagings. The dialog sends exactly the mode that is chosen, never both.
export type QuantityMode = 'quantity' | 'packagings'

export interface EntryProduct extends PackagedProduct {
  defaultUnit?: string | null
  restockQuantity?: number | null
}

// Where the number of packagings comes from: the product's restock, what the
// week's meals need, the bare default of one, or the owner's own hand.
export type DraftSource = 'restock' | 'need' | 'default' | 'user'

export interface QuantityDraft {
  mode: QuantityMode
  quantity: string
  unit: string
  packagings: string
  source: DraftSource
}

/** What the week's meals need of a product, in base units (the API's `recipeNeed`). */
export interface RecipeNeed {
  quantity: number
  unit: string
}

const EMPTY: QuantityDraft = { mode: 'quantity', quantity: '', unit: '', packagings: '', source: 'default' }

const hasPackaging = (product?: EntryProduct | null): boolean => !!product?.packagingUnit

// The unit a quantity is typed in when the owner leaves « Conditionnement »: the
// product's usual one, else the unit its packaging is measured in (« g » for 500 g).
const typedUnit = (product?: EntryProduct | null): string => product?.defaultUnit ?? product?.packagingSizeUnit ?? ''

// Opening the add dialog on a product: its restock quantity in packagings, else
// what the week's meals need rounded up to packagings, else one packaging — or,
// without packaging, an empty quantity in the product's usual unit.
export const initialDraft = (product?: EntryProduct | null, need?: RecipeNeed | null): QuantityDraft => {
  if (!hasPackaging(product)) return { ...EMPTY, unit: product?.defaultUnit ?? '' }
  const unit = typedUnit(product)
  const base = { ...EMPTY, unit, mode: 'packagings' as const }

  const restock = product?.restockQuantity
  if (restock != null && Number.isInteger(restock) && restock >= 1) return { ...base, packagings: String(restock), source: 'restock' }

  const needed = product && need ? packagingsForNeed(product, need) : null

  return needed !== null ? { ...base, packagings: String(needed), source: 'need' } : { ...base, packagings: '1' }
}

// The need arrives after the dialog has opened: it fills the packagings only
// when nothing else (restock, the owner) has chosen them yet.
export const withNeed = (draft: QuantityDraft, product: EntryProduct | null | undefined, need: RecipeNeed | null): QuantityDraft => {
  if (draft.source !== 'default' || draft.mode !== 'packagings' || !product || !need) return draft
  const needed = packagingsForNeed(product, need)

  return needed === null ? draft : { ...draft, packagings: String(needed), source: 'need' }
}

// Editing a line of a packaged product: the packagings it holds now, and in
// Quantité the quantity that was asked, when the line keeps it.
export const draftForLine = (
  quantity: number | null | undefined,
  product?: EntryProduct | null,
  desired?: { quantity?: number | null; unit?: string | null } | null,
): QuantityDraft => {
  if (!hasPackaging(product)) return { ...EMPTY }
  const asked = desired?.quantity != null && desired.unit ? desired : null

  return {
    ...EMPTY,
    unit: asked?.unit ?? typedUnit(product),
    quantity: asked?.quantity != null ? String(asked.quantity) : '',
    mode: 'packagings',
    packagings: String(quantity != null && quantity >= 1 ? Math.round(quantity) : 1),
    source: 'user',
  }
}

export const parsePackagings = (text: string): number | null => {
  const value = Number(text.trim())

  return text.trim() === '' || !Number.isInteger(value) || value < 1 ? null : value
}

const parseAmount = (text: string): number | null => {
  const value = Number(text.trim().replace(',', '.'))

  return text.trim() === '' || !Number.isFinite(value) || value <= 0 ? null : value
}

// « Ajouter » is allowed with no quantity at all, but not with a number of
// packagings that is not a whole number of at least one, nor a quantity that is
// not a positive number.
export const isDraftValid = (draft: QuantityDraft): boolean =>
  draft.mode === 'packagings' ? parsePackagings(draft.packagings) !== null : draft.quantity.trim() === '' || parseAmount(draft.quantity) !== null

export const draftPayload = (draft: QuantityDraft): Record<string, unknown> =>
  draft.mode === 'packagings'
    ? { packagings: parsePackagings(draft.packagings) }
    : { quantity: parseAmount(draft.quantity), unit: draft.unit || null }

const FAMILIES: Record<string, [string, number]> = {
  g: ['mass', 1],
  kg: ['mass', 1000],
  ml: ['volume', 1],
  cl: ['volume', 10],
  l: ['volume', 1000],
}

const EPSILON = 1e-9

// The same rule as the API's `Packaging::inPackagings`: a whole number of
// packagings, rounded up, at least one — whatever the unit the quantity is typed
// in. A unit that cannot be turned into the packaging counts as one.
export const packagingsFor = (product: EntryProduct, quantity: number, unit: string): number => {
  const packagingUnit = product.packagingUnit as string
  const typed = unit || packagingUnit
  let fraction: number | null = null

  if (typed === packagingUnit) {
    fraction = quantity
  } else if (product.packagingSize && product.packagingSizeUnit) {
    const from = FAMILIES[typed]
    const to = FAMILIES[product.packagingSizeUnit]
    if (typed === product.packagingSizeUnit) fraction = quantity / product.packagingSize
    else if (from && to && from[0] === to[0]) fraction = (quantity * from[1]) / (product.packagingSize * to[1])
  }

  return fraction === null ? 1 : Math.max(1, Math.ceil(fraction - EPSILON))
}

const contentOf = (product: EntryProduct): string =>
  product.packagingSize && product.packagingSizeUnit ? ` de ${quantityLabel(product.packagingSize, product.packagingSizeUnit)}` : ''

// « paquets de 500 g » — beside the stepper, what each of its units is.
export const packagingCaption = (product: EntryProduct, count: number): string =>
  `${unitLabel(product.packagingUnit as string, count)}${contentOf(product)}`

export const readable = (quantity: number, unit: string): string => {
  if (unit === 'g' && quantity >= 1000) return quantityLabel(quantity / 1000, 'kg')
  if (unit === 'ml' && quantity >= 1000) return quantityLabel(quantity / 1000, 'l')

  return quantityLabel(quantity, unit)
}

// A quantity in the base unit of its family (g, ml), the way the API keeps what
// was asked: « 1,4 kg » is 1400 g. Any other unit stays as it is.
export const toBase = (quantity: number, unit: string): { quantity: number; unit: string } => {
  const family = FAMILIES[unit]
  if (!family) return { quantity, unit }

  return { quantity: quantity * family[1], unit: family[0] === 'mass' ? 'g' : 'ml' }
}

// What one packaging holds, in base units; null when the product does not say.
const packContent = (product: EntryProduct): { quantity: number; unit: string } | null =>
  product.packagingSize && product.packagingSizeUnit ? toBase(product.packagingSize, product.packagingSizeUnit) : null

// The packagings that hold a need: rounded up, at least one. Null when the pack
// content is unknown or not in the unit of the need.
export const packagingsForNeed = (product: EntryProduct, need: RecipeNeed): number | null => {
  const pack = packContent(product)
  const needed = toBase(need.quantity, need.unit)
  if (!pack || pack.unit !== needed.unit || need.quantity <= 0) return null

  return Math.max(1, Math.ceil(needed.quantity / pack.quantity - EPSILON))
}

// The packagings the draft would put on the list, whatever the mode.
export const chosenPackagings = (draft: QuantityDraft, product?: EntryProduct | null): number | null => {
  if (!product || !hasPackaging(product)) return null
  if (draft.mode === 'packagings') return parsePackagings(draft.packagings)
  const quantity = parseAmount(draft.quantity)

  return quantity === null ? null : packagingsFor(product, quantity, draft.unit)
}

export interface Coverage {
  covered: boolean
  /** The packagings that would cover the week. */
  packagingsToCover: number
}

// Does what is chosen cover what the week's meals need? Null when it cannot be
// told: no need, no pack content, a need in another family than the pack, or
// nothing valid chosen yet.
export const coverage = (draft: QuantityDraft, product?: EntryProduct | null, need?: RecipeNeed | null): Coverage | null => {
  if (!product || !need) return null
  const toCover = packagingsForNeed(product, need)
  const chosen = chosenPackagings(draft, product)
  if (toCover === null || chosen === null) return null

  return { covered: chosen >= toCover, packagingsToCover: toCover }
}

// « Ne couvre pas les besoins de la semaine (1,2 kg pour les recettes) ».
export const shortfallMessage = (need: RecipeNeed): string =>
  `Ne couvre pas les besoins de la semaine (${readable(need.quantity, need.unit)} pour les recettes)`

// « Ajuster à 3 paquets ».
export const adjustLabel = (product: EntryProduct, count: number): string => `Ajuster à ${quantityLabel(count, product.packagingUnit as string)}`

// « 1 kg (dont 800 g pour les recettes) · 2 paquets »: what is wanted, how much
// of it is for the meals, and the packagings it comes to. « dont » is left out
// when the chosen quantity is below the need — the alert says so.
export const summaryText = (draft: QuantityDraft, product?: EntryProduct | null, need?: RecipeNeed | null): string | null => {
  const count = chosenPackagings(draft, product)
  if (!product || count === null) return null

  const packs = quantityLabel(count, product.packagingUnit as string)
  const pack = packContent(product)
  let wanted: { quantity: number; unit: string } | null = null
  if (draft.mode === 'packagings') {
    wanted = pack ? { quantity: count * pack.quantity, unit: pack.unit } : null
  } else {
    const typed = parseAmount(draft.quantity)
    const base = typed !== null && draft.unit ? toBase(typed, draft.unit) : null
    wanted = base && base.unit !== product.packagingUnit ? base : null
  }
  if (!wanted) return packs

  let text = readable(wanted.quantity, wanted.unit)
  if (need) {
    const needed = toBase(need.quantity, need.unit)
    if (needed.unit === wanted.unit && needed.quantity <= wanted.quantity + EPSILON) text += ` (dont ${readable(need.quantity, need.unit)} pour les recettes)`
  }

  return `${text} · ${packs}`
}

export interface QuantitySuggestion {
  kind: 'need' | 'restock'
  label: string
  quantity: number
  unit: string
}

// Quantities offered in Quantité mode: what the week's meals need, and the
// product's restock in base units (2 packs of 500 g: 1 kg).
export const suggestions = (product?: EntryProduct | null, need?: RecipeNeed | null): QuantitySuggestion[] => {
  if (!product || !hasPackaging(product)) return []
  const list: QuantitySuggestion[] = []
  if (need) list.push({ kind: 'need', label: readable(need.quantity, need.unit), quantity: need.quantity, unit: need.unit })

  const restock = product.restockQuantity
  const pack = packContent(product)
  if (restock != null && restock >= 1 && pack) {
    const quantity = restock * pack.quantity
    if (!list.some((one) => one.quantity === quantity && one.unit === pack.unit)) {
      list.push({ kind: 'restock', label: readable(quantity, pack.unit), quantity, unit: pack.unit })
    }
  }

  return list
}

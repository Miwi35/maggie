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

export interface QuantityDraft {
  mode: QuantityMode
  quantity: string
  unit: string
  packagings: string
  /** The number of packagings comes from the product's automatic restock. */
  prefilled: boolean
}

const EMPTY: QuantityDraft = { mode: 'quantity', quantity: '', unit: '', packagings: '', prefilled: false }

const hasPackaging = (product?: EntryProduct | null): boolean => !!product?.packagingUnit

// The unit a quantity is typed in when the owner leaves « Conditionnement »: the
// product's usual one, else the unit its packaging is measured in (« g » for 500 g).
const typedUnit = (product?: EntryProduct | null): string => product?.defaultUnit ?? product?.packagingSizeUnit ?? ''

// Opening the add dialog on a product: its restock quantity in packagings, else
// one packaging, else an empty quantity in the product's usual unit.
export const initialDraft = (product?: EntryProduct | null): QuantityDraft => {
  if (!hasPackaging(product)) return { ...EMPTY, unit: product?.defaultUnit ?? '' }
  const unit = typedUnit(product)

  const restock = product?.restockQuantity
  const fromRestock = restock != null && Number.isInteger(restock) && restock >= 1

  return { ...EMPTY, unit, mode: 'packagings', packagings: String(fromRestock ? restock : 1), prefilled: fromRestock }
}

// Editing a line of a packaged product: the packagings it holds now.
export const draftForLine = (quantity: number | null | undefined, product?: EntryProduct | null): QuantityDraft =>
  hasPackaging(product)
    ? { ...EMPTY, unit: typedUnit(product), mode: 'packagings', packagings: String(quantity != null && quantity >= 1 ? Math.round(quantity) : 1) }
    : { ...EMPTY }

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

const readable = (quantity: number, unit: string): string => {
  if (unit === 'g' && quantity >= 1000) return quantityLabel(quantity / 1000, 'kg')
  if (unit === 'ml' && quantity >= 1000) return quantityLabel(quantity / 1000, 'l')

  return quantityLabel(quantity, unit)
}

// The conversion shown under the field: « = 1 kg » for 2 packs of 500 g, « = 1
// paquet de 500 g à acheter » for 300 g. Null when there is nothing to convert.
export const conversionHint = (draft: QuantityDraft, product?: EntryProduct | null): string | null => {
  if (!product || !hasPackaging(product)) return null

  if (draft.mode === 'packagings') {
    const count = parsePackagings(draft.packagings)
    if (count === null || !product.packagingSize || !product.packagingSizeUnit) return null

    return `= ${readable(count * product.packagingSize, product.packagingSizeUnit)}`
  }

  const quantity = parseAmount(draft.quantity)
  if (quantity === null) return null

  const count = packagingsFor(product, quantity, draft.unit)

  return `= ${quantityLabel(count, product.packagingUnit as string)}${contentOf(product)} à acheter`
}

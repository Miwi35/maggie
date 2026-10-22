import { quantityLabel } from './packaging'

// What a list line lets the shopper adjust (MAG-299): only a product bought in a
// packaging has a quantity, and it is always a whole number of that packaging —
// « 2 paquets (500 g) », whatever the unit it came in. − and + move by one
// packaging. The other lines have nothing to adjust; their quantity, when a
// recipe gave one, is only an indication.
const UNIT_LABELS: Record<string, [string, string]> = {
  piece: ['pièce', 'pièces'],
  bunch: ['botte', 'bottes'],
  can: ['boîte', 'boîtes'],
  bottle: ['bouteille', 'bouteilles'],
  pack: ['paquet', 'paquets'],
  sachet: ['sachet', 'sachets'],
  jar: ['bocal', 'bocaux'],
}

export interface PackagedProduct {
  packagingUnit?: string | null
  packagingSize?: number | null
  packagingSizeUnit?: string | null
}

export const isPackaged = (product?: PackagedProduct | null): boolean => !!product?.packagingUnit

export const increaseQuantity = (quantity: number | null | undefined): number => (quantity ?? 0) + 1

export const decreaseQuantity = (quantity: number | null | undefined): number => Math.max(1, (quantity ?? 0) - 1)

export const canDecrease = (quantity: number | null | undefined): boolean => quantity != null && quantity > 1

export const formatQuantity = (quantity: number): string => String(Math.round(quantity * 1000) / 1000).replace('.', ',')

export const unitLabel = (unit: string | undefined, quantity: number): string => {
  if (!unit) return ''
  const labels = UNIT_LABELS[unit]
  if (!labels) return unit
  return quantity > 1 ? labels[1] : labels[0]
}

// `null` when the text is no number of packagings to send: empty, not a whole
// number, zero or negative.
export const parseQuantity = (text: string): number | null => {
  const value = Number(text.trim())
  if (text.trim() === '' || !Number.isInteger(value) || value <= 0) return null
  return value
}

export const lineUnit = (product?: PackagedProduct | null): string | undefined => product?.packagingUnit || undefined

// « (500 g) » — what one pack holds.
export const packagingContent = (product?: PackagedProduct | null): string | null => {
  if (!product?.packagingUnit || product.packagingSize == null || !product.packagingSizeUnit) return null
  return `(${quantityLabel(product.packagingSize, product.packagingSizeUnit)})`
}

// « 300 g » — the quantity a recipe gave to a product without packaging.
export const quantityHint = (quantity?: number | null, unit?: string | null): string | null => {
  if (quantity == null) return null
  const name = unitLabel(unit ?? undefined, quantity)

  return name ? `${formatQuantity(quantity)} ${name}` : formatQuantity(quantity)
}

// What the meals need of a product, in base units (MAG-380): « 800 g », or
// « 300 g + 2 pièces » when the units cannot be added.
export interface NeededEntry {
  quantity: number
  unit: string | null
}

const neededEntryLabel = ({ quantity, unit }: NeededEntry): string => {
  if (unit === 'g' && quantity >= 1000) return `${formatQuantity(quantity / 1000)} kg`
  if (unit === 'ml' && quantity >= 1000) return `${formatQuantity(quantity / 1000)} l`
  const name = unitLabel(unit ?? undefined, quantity)

  return name ? `${formatQuantity(quantity)} ${name}` : formatQuantity(quantity)
}

export const neededLabel = (needed?: NeededEntry[] | null): string | null =>
  needed && needed.length > 0 ? needed.map(neededEntryLabel).join(' + ') : null

// Under a packaged line's name (MAG-573): what was asked, and how much of it is
// for the week's meals — « 1,4 kg (dont 800 g pour les recettes) ». Without a
// wanted quantity, only the need: « 800 g pour les recettes ».
export const desiredLabel = (quantity?: number | null, unit?: string | null, needed?: NeededEntry[] | null): string | null => {
  const need = neededLabel(needed)
  if (quantity == null || !unit) return need ? `${need} pour les recettes` : null
  const asked = neededEntryLabel({ quantity, unit })

  return need ? `${asked} (dont ${need} pour les recettes)` : asked
}

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

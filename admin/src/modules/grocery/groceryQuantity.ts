// Step of the − / + buttons on a list line (MAG-291): a counted unit moves by
// one, a weight or a volume by what a shopper would add at a time. The smallest
// quantity a line can be lowered to is one step: − never removes the line.
const STEPS: Record<string, number> = { g: 100, kg: 0.1, ml: 100, cl: 10, l: 0.1 }

const UNIT_LABELS: Record<string, [string, string]> = {
  piece: ['pièce', 'pièces'],
  bunch: ['botte', 'bottes'],
  can: ['boîte', 'boîtes'],
  bottle: ['bouteille', 'bouteilles'],
  pack: ['paquet', 'paquets'],
  sachet: ['sachet', 'sachets'],
  jar: ['bocal', 'bocaux'],
}

export const quantityStep = (unit?: string): number => (unit ? (STEPS[unit] ?? 1) : 1)

// 0.1 + 0.2 is 0.30000000000000004: quantities are kept to three decimals.
const round = (n: number) => Math.round(n * 1000) / 1000

export const increaseQuantity = (quantity: number | null | undefined, unit?: string): number =>
  round((quantity ?? 0) + quantityStep(unit))

export const decreaseQuantity = (quantity: number | null | undefined, unit?: string): number =>
  Math.max(quantityStep(unit), round((quantity ?? 0) - quantityStep(unit)))

export const canDecrease = (quantity: number | null | undefined, unit?: string): boolean =>
  quantity != null && quantity > quantityStep(unit)

export const formatQuantity = (quantity: number): string => String(round(quantity)).replace('.', ',')

export const unitLabel = (unit: string | undefined, quantity: number): string => {
  if (!unit) return ''
  const labels = UNIT_LABELS[unit]
  if (!labels) return unit
  return quantity > 1 ? labels[1] : labels[0]
}

// `null` when the text is no quantity to send: empty, not a number, zero or negative.
export const parseQuantity = (text: string): number | null => {
  const value = Number(text.trim().replace(',', '.'))
  if (text.trim() === '' || !Number.isFinite(value) || value <= 0) return null
  return round(value)
}

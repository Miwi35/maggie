import { UNIT_CHOICES } from './productChoices'

/** Plural of the counted units, for a content such as « paquet de 6 pièces ». */
const PLURALS: Record<string, string> = {
  piece: 'pièces',
  bunch: 'bottes',
  can: 'boîtes',
  bottle: 'bouteilles',
  pack: 'paquets',
  sachet: 'sachets',
  jar: 'bocaux',
}

const unitName = (unit: string): string => UNIT_CHOICES.find((choice) => choice.id === unit)?.name ?? unit

const formatSize = (size: number): string => size.toLocaleString('fr-FR', { maximumFractionDigits: 3 })

/**
 * What a product is bought in, as the owner says it: « paquet de 500 g »,
 * « bocal ». Null when the product has no packaging.
 */
export const packagingLabel = (
  unit?: string | null,
  size?: number | null,
  sizeUnit?: string | null,
): string | null => {
  if (!unit) return null
  const bought = unitName(unit)
  if (size == null || !sizeUnit) return bought
  const content = size > 1 && PLURALS[sizeUnit] ? PLURALS[sizeUnit] : unitName(sizeUnit)

  return `${bought} de ${formatSize(size)} ${content}`
}

/** A count of what is bought, as the owner says it: « 1 paquet », « 2 bocaux », « 300 g ». */
export const quantityLabel = (quantity: number, unit: string): string => {
  const name = quantity > 1 && PLURALS[unit] ? PLURALS[unit] : unitName(unit)

  return `${formatSize(quantity)} ${name}`
}

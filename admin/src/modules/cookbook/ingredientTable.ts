export interface IngredientRow {
  id: string
  name: string
  ciqualAlimCode?: string | null
  kcalPer100g?: number | null
  pieceWeightGrams?: number | null
}

export type ValuesState = 'ciqual' | 'none'
export type PillKey = 'all' | ValuesState

export interface ValuesPill {
  key: PillKey
  label: string
  count: number
}

/** « ciqual » when drawn from the Ciqual, « none » with no energy and no code; null for values typed by hand. */
export const valuesState = (ingredient: Pick<IngredientRow, 'ciqualAlimCode' | 'kcalPer100g'>): ValuesState | null => {
  if (ingredient.ciqualAlimCode) return 'ciqual'

  return ingredient.kcalPer100g == null ? 'none' : null
}

export const valuesPills = (list: Pick<IngredientRow, 'ciqualAlimCode' | 'kcalPer100g'>[]): ValuesPill[] => [
  { key: 'all', label: 'Tous', count: list.length },
  { key: 'none', label: 'Sans valeurs', count: list.filter((own) => valuesState(own) === 'none').length },
  { key: 'ciqual', label: 'Ciqual', count: list.filter((own) => valuesState(own) === 'ciqual').length },
]

const plain = (text: string): string => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()

export const filterIngredients = <T extends Pick<IngredientRow, 'name' | 'ciqualAlimCode' | 'kcalPer100g'>>(list: T[], query: string, pill: PillKey): T[] => {
  const wanted = plain(query.trim())

  return list.filter((own) => (pill === 'all' || valuesState(own) === pill) && (wanted === '' || plain(own.name).includes(wanted)))
}

export const energyLabel = (kcal: number | null | undefined): string => (kcal == null ? '—' : `${Math.round(kcal)} kcal`)

export const pieceLabel = (grams: number | null | undefined): string => (grams == null ? '—' : `≈ ${Math.round(grams)} g`)

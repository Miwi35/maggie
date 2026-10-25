import { CATEGORY_CHOICES } from './productChoices'
import { NO_AISLE } from './groceryAisles'
import { bareId } from './recurring'
import type { Aisle } from './aislesApi'

export interface ProductRow {
  id: string
  name: string
  category?: string | null
  packagingUnit?: string | null
  packagingSize?: number | null
  packagingSizeUnit?: string | null
  stockState?: string | null
  seasonMonths?: number[] | null
  inSeason?: boolean | null
  preferredStore?: string | null
  fallbackStore?: string | null
  aisles?: { store: string; aisle: string }[]
}

export const PASTILLES = [
  { id: 'all', label: 'Tous' },
  { id: 'season', label: 'De saison' },
  { id: 'packaged', label: 'Conditionnés' },
  { id: 'low', label: 'Stock bas' },
] as const

export type PastilleId = (typeof PASTILLES)[number]['id']

/** « De saison »: the API's `inSeason` when it computed one, else the months against this month. */
const isInSeason = (row: ProductRow, month: number): boolean => row.inSeason ?? row.seasonMonths?.includes(month) ?? false

export const matchesPastille = (row: ProductRow, pastille: PastilleId, month: number): boolean => {
  switch (pastille) {
    case 'season':
      return isInSeason(row, month)
    case 'packaged':
      return Boolean(row.packagingUnit)
    case 'low':
      return row.stockState === 'low' || row.stockState === 'out'
    default:
      return true
  }
}

const fold = (text: string): string =>
  text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim()

export const matchesSearch = (row: ProductRow, search: string): boolean => fold(row.name).includes(fold(search))

/** The French name of a stored category; an unknown one reads « Autre », never the raw English value. */
export const categoryName = (category?: string | null): string =>
  CATEGORY_CHOICES.find((choice) => choice.id === category)?.name ?? 'Autre'

export interface ProductGroup {
  title: string
  rows: ProductRow[]
}

/** With no store: one group per category, in the order of the form's choices, « Autre » last. */
export const groupsByCategory = (rows: ProductRow[]): ProductGroup[] => {
  const known = new Set(CATEGORY_CHOICES.map((choice) => choice.id as string))
  const ordered = CATEGORY_CHOICES.filter((choice) => choice.id !== 'other').map((choice) => ({
    title: choice.name,
    rows: rows.filter((row) => row.category === choice.id),
  }))
  const other = { title: categoryName('other'), rows: rows.filter((row) => !row.category || row.category === 'other' || !known.has(row.category)) }

  return [...ordered, other].filter((group) => group.rows.length > 0)
}

/** The aisle a product has in a store, when that store still has it. */
export const aisleIn = (row: ProductRow, storeId: string, aisles: Aisle[]): Aisle | undefined => {
  const entry = row.aisles?.find((a) => bareId(a.store) === storeId)

  return entry ? aisles.find((a) => a.id === bareId(entry.aisle)) : undefined
}

/** With a store: one group per aisle, in the order of the way through it; « Sans rayon » last. */
export const groupsByAisle = (rows: ProductRow[], storeId: string, aisles: Aisle[]): ProductGroup[] => {
  const byAisle = new Map<string, ProductRow[]>()
  for (const row of rows) {
    const id = aisleIn(row, storeId, aisles)?.id ?? ''
    byAisle.set(id, [...(byAisle.get(id) ?? []), row])
  }

  return [...aisles.map((a) => ({ title: a.name, rows: byAisle.get(a.id) ?? [] })), { title: NO_AISLE, rows: byAisle.get('') ?? [] }].filter(
    (group) => group.rows.length > 0,
  )
}

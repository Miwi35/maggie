import type { StockState } from './productChoices'
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

export interface ProductGroup {
  title: string
  rows: ProductRow[]
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

/** The aisle of a product in its usual store, when that store's aisles are known. */
export const usualAisle = (row: ProductRow, aislesByStore: Map<string, Aisle[]>): Aisle | undefined => {
  if (!row.preferredStore) return undefined
  const storeId = bareId(row.preferredStore)

  return aisleIn(row, storeId, aislesByStore.get(storeId) ?? [])
}

/** « Super U · Biocoop », the usual store alone, or nothing. */
export const storesLabel = (row: ProductRow, storeName: (iri?: string | null) => string): string =>
  [row.preferredStore, row.fallbackStore]
    .map((iri) => storeName(iri))
    .filter(Boolean)
    .join(' · ')

export const STOCK_LOOKS: Record<StockState, { label: 'en stock' | 'bas' | 'épuisé'; tone: 'neutral' | 'courses' | 'warning' }> = {
  in_stock: { label: 'en stock', tone: 'neutral' },
  low: { label: 'bas', tone: 'courses' },
  out: { label: 'épuisé', tone: 'warning' },
}

/** An unknown state reads as in stock, like `StockStateChip`. */
export const stockLook = (state?: string | null) => STOCK_LOOKS[state as StockState] ?? STOCK_LOOKS.in_stock

export type SortField = 'name' | 'aisle' | 'stockState'

/** Sorting by aisle needs a store: the API only orders by aisle inside one. */
export const sortCycle = (hasStore: boolean): SortField[] => (hasStore ? ['name', 'aisle', 'stockState'] : ['name', 'stockState'])

export const SORT_WORDS: Record<SortField, string> = { name: 'nom', aisle: 'rayon', stockState: 'stock' }

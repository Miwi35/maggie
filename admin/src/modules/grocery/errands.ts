import { packagingLabel, quantityLabel } from './packaging'
import type { Row } from '../../hooks/liveList'

// « Courses passées » (MAG-488): an errand is one store, one moment, the lines
// bought there. The API archives it when the errand ends; the admin only reads.

export interface PurchasedItem {
  id: string
  label: string
  quantity?: number | null
  unit?: string | null
  packagingUnit?: string | null
  packagingSize?: number | null
  packagingSizeUnit?: string | null
}

/** The filter values of the screen: a store IRI and a month (`2026-10`), each optional. */
export interface ErrandFilter {
  store?: string
  month?: string
}

const pad = (value: number): string => String(value).padStart(2, '0')

/** `2026-10` → the first day of that month and of the next, as the API's date filter takes them. */
export const monthBounds = (month: string): { start: string; end: string } | null => {
  const match = /^(\d{4})-(\d{2})$/.exec(month)
  if (!match) return null
  const year = Number(match[1])
  const index = Number(match[2])
  if (index < 1 || index > 12) return null
  const next = index === 12 ? { year: year + 1, index: 1 } : { year, index: index + 1 }

  return { start: `${year}-${pad(index)}-01`, end: `${next.year}-${pad(next.index)}-01` }
}

/** The query parameters of the list: `store`, `endedAt[after]` and `endedAt[strictly_before]`. */
export const errandQuery = ({ store, month }: ErrandFilter): Record<string, string> => {
  const query: Record<string, string> = {}
  if (store) query.store = store
  const bounds = month ? monthBounds(month) : null
  if (bounds) {
    query['endedAt[after]'] = bounds.start
    query['endedAt[strictly_before]'] = bounds.end
  }

  return query
}

/** Whether an errand pushed by Mercure belongs to the filtered list. */
export const errandMatches =
  ({ store, month }: ErrandFilter) =>
  (row: Row): boolean => {
    if (store && row.store !== store) return false
    if (month && typeof row.endedAt === 'string' && !row.endedAt.startsWith(month)) return false

    return true
  }

/** « 2 paquets · paquet de 500 g », « 3 pièces », or nothing when the line had no quantity. */
export const purchasedQuantity = (item: PurchasedItem): string => {
  const parts: string[] = []
  if (item.quantity != null) {
    parts.push(item.unit ? quantityLabel(item.quantity, item.unit) : String(item.quantity).replace('.', ','))
  }
  // The packaging alone (« paquet ») says nothing « 2 paquets » does not: only its content does.
  if (item.packagingSize != null && item.packagingSizeUnit) {
    const packaging = packagingLabel(item.packagingUnit, item.packagingSize, item.packagingSizeUnit)
    if (packaging) parts.push(packaging)
  }

  return parts.join(' · ')
}

/** « mardi 14 octobre 2026, 18:05 » */
export const formatEndedAt = (endedAt: string): string =>
  new Date(endedAt).toLocaleString('fr-FR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })

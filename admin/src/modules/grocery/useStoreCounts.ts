import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'
import { AISLE_TOPIC, fetchAisleCounts, fetchStoreProductCount } from './aislesApi'
import { bareId } from './recurring'

export interface StoreCount {
  aisles: number
  products: number
}

const AISLE_TOPICS = [AISLE_TOPIC]
const PRODUCT_TOPICS = ['/api/products/{id}']

// Products are counted from the search index, which the worker updates after the event left.
const INDEX_DELAY_MS = 3000

/**
 * What each store holds — its aisles and its products, by bare store id — read
 * again whenever an aisle or a product changes. A store whose counts are not
 * read yet, or could not be, has no entry.
 */
export const useStoreCounts = (storeIds: string[]): Record<string, StoreCount> => {
  const [counts, setCounts] = useState<Record<string, StoreCount>>({})
  const key = storeIds.map(bareId).join('|')

  const reload = useCallback(async () => {
    const ids = key ? key.split('|') : []
    if (ids.length === 0) return
    try {
      const [aisles, products] = await Promise.all([
        fetchAisleCounts(),
        Promise.all(ids.map((id) => fetchStoreProductCount(id))),
      ])
      setCounts(Object.fromEntries(ids.map((id, i) => [id, { aisles: aisles[id] ?? 0, products: products[i] }])))
    } catch {
      // The counters stay as they were: the page does not depend on them.
    }
  }, [key])

  useEffect(() => {
    reload()
  }, [reload])

  const later = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(later.current), [])

  useMercure(AISLE_TOPICS, reload)
  useMercure(PRODUCT_TOPICS, () => {
    reload()
    clearTimeout(later.current)
    later.current = setTimeout(reload, INDEX_DELAY_MS)
  })

  return counts
}

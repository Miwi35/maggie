import { useCallback, useEffect, useRef, useState } from 'react'
import { useMercure } from '../../hooks/useMercure'
import { STORES_PAGE_TOPICS, fetchStoreAisleCount, fetchStoreProductCount } from './aislesApi'
import { bareId } from './recurring'

export interface StoreCount {
  aisles: number
  products: number
}

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

  const latest = useRef(0)

  const reload = useCallback(async () => {
    const ids = key ? key.split('|') : []
    if (ids.length === 0) return
    const request = ++latest.current
    const read = await Promise.all(
      ids.map(async (id): Promise<[string, StoreCount] | null> => {
        try {
          const [aisles, products] = await Promise.all([fetchStoreAisleCount(id), fetchStoreProductCount(id)])
          return [id, { aisles, products }]
        } catch {
          return null // This store keeps its last counters: the page does not depend on them.
        }
      }),
    )
    if (request !== latest.current) return // a newer read is under way and will answer
    const fresh = Object.fromEntries(read.filter((entry): entry is [string, StoreCount] => entry !== null))
    setCounts((previous) => ({ ...previous, ...fresh }))
  }, [key])

  useEffect(() => {
    reload()
  }, [reload])

  const later = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(later.current), [])

  useMercure(STORES_PAGE_TOPICS, () => {
    reload()
    clearTimeout(later.current)
    later.current = setTimeout(reload, INDEX_DELAY_MS)
  })

  return counts
}

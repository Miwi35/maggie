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

// A move renumbers the aisles of a store and each publishes its update: the burst is waited out, then read once.
const BURST_MS = 300

/** The store a Mercure payload names (an aisle's `storeId`), or null when it names none. */
const storeOf = (data?: string): string | null => {
  try {
    const storeId = data ? JSON.parse(data)?.storeId : null
    return typeof storeId === 'string' && storeId ? bareId(storeId) : null
  } catch {
    return null
  }
}

/**
 * What each store holds — its aisles and its products, by bare store id — read
 * again whenever an aisle or a product changes. A store whose counts are not
 * read yet, or could not be, has no entry.
 */
export const useStoreCounts = (storeIds: string[]): Record<string, StoreCount> => {
  const [counts, setCounts] = useState<Record<string, StoreCount>>({})
  const key = storeIds.map(bareId).join('|')

  // The last read begun of each store: an older one that answers late is dropped.
  const sequence = useRef(0)
  const latest = useRef<Record<string, number>>({})

  const read = useCallback(async (ids: string[]) => {
    const requests = Object.fromEntries(ids.map((id) => [id, ++sequence.current]))
    Object.assign(latest.current, requests)
    const answers = await Promise.all(
      ids.map(async (id): Promise<[string, StoreCount] | null> => {
        try {
          const [aisles, products] = await Promise.all([fetchStoreAisleCount(id), fetchStoreProductCount(id)])
          return [id, { aisles, products }]
        } catch {
          return null // This store keeps its last counters: the page does not depend on them.
        }
      }),
    )
    const fresh = Object.fromEntries(
      answers.filter((entry): entry is [string, StoreCount] => entry !== null && latest.current[entry[0]] === requests[entry[0]]),
    )
    if (Object.keys(fresh).length > 0) setCounts((previous) => ({ ...previous, ...fresh }))
  }, [])

  const ids = useRef<string[]>([])
  useEffect(() => {
    ids.current = key ? key.split('|') : []
    read(ids.current)
  }, [key, read])

  // What the messages of the current burst concern: some stores, or every one when a message names none.
  const burst = useRef<{ timer?: ReturnType<typeof setTimeout>; stores: Set<string>; all: boolean }>({
    stores: new Set(),
    all: false,
  })
  const later = useRef<{ timer?: ReturnType<typeof setTimeout>; stores: Set<string> }>({ stores: new Set() })
  useEffect(
    () => () => {
      clearTimeout(burst.current.timer)
      clearTimeout(later.current.timer)
    },
    [],
  )

  const settle = useCallback(() => {
    const { stores, all } = burst.current
    const concerned = all ? ids.current : ids.current.filter((id) => stores.has(id))
    burst.current = { stores: new Set(), all: false }
    if (concerned.length === 0) return
    read(concerned)
    concerned.forEach((id) => later.current.stores.add(id))
    clearTimeout(later.current.timer)
    later.current.timer = setTimeout(() => {
      const again = [...later.current.stores]
      later.current.stores = new Set()
      read(again)
    }, INDEX_DELAY_MS)
  }, [read])

  useMercure(STORES_PAGE_TOPICS, (data) => {
    const store = storeOf(data)
    if (store === null) burst.current.all = true
    else burst.current.stores.add(store)
    clearTimeout(burst.current.timer)
    burst.current.timer = setTimeout(settle, BURST_MS)
  })

  return counts
}

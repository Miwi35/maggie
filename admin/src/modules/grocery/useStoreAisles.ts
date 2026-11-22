import { useEffect, useState } from 'react'
import { fetchAisles } from './aislesApi'
import type { Aisle } from './aislesApi'

/**
 * The aisles of one store, first aisle first — empty while they load or when
 * there is no store. `add` shows an aisle just created.
 */
export const useStoreAisles = (storeId: string | null) => {
  const [loaded, setLoaded] = useState<{ storeId: string; aisles: Aisle[] } | null>(null)

  useEffect(() => {
    if (!storeId) return
    let live = true
    fetchAisles(storeId)
      .catch(() => [] as Aisle[])
      .then((aisles) => {
        if (live) setLoaded({ storeId, aisles })
      })
    return () => {
      live = false
    }
  }, [storeId])

  const ready = Boolean(storeId) && loaded?.storeId === storeId
  const aisles = ready && loaded ? loaded.aisles : []
  const add = (aisle: Aisle) => {
    if (!storeId) return
    setLoaded((prev) => ({
      storeId,
      aisles: [...(prev?.storeId === storeId ? prev.aisles : []), aisle],
    }))
  }
  return { aisles, ready, add }
}

/** The aisles of each given store, read once per distinct store; a store that fails reads as having none. */
export const useAislesByStore = (storeIds: string[]): Map<string, Aisle[]> => {
  const [loaded, setLoaded] = useState<Map<string, Aisle[]>>(new Map())
  const key = [...new Set(storeIds)].sort().join(',')

  useEffect(() => {
    if (!key) return
    let live = true
    const ids = key.split(',')
    Promise.all(ids.map((id) => fetchAisles(id).catch(() => [] as Aisle[]))).then((all) => {
      if (live) setLoaded(new Map(ids.map((id, i) => [id, all[i]])))
    })
    return () => {
      live = false
    }
  }, [key])

  return loaded
}

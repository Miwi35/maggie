import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMercure } from '../../../hooks/useMercure'
import { GROCERY_LIST_TOPICS } from '../../grocery/groceryListLive'
import { isRecurringUpdate } from '../../grocery/recurring'
import { isSuggestionOnlyUpdate } from '../../grocery/purchaseSuggestions'
import type { CookingGroceryItem } from './cookingGrocery'
import { fetchGroceryItems, patchChecked } from './cookingGroceryApi'

export type GroceryLoad = 'loading' | 'ready' | 'error'

export interface CookingGrocery {
  load: GroceryLoad
  items: CookingGroceryItem[]
  /** Why the last tick did not go through; the box has already gone back. */
  notice: string | null
  retry: () => void
  clearNotice: () => void
  toggle: (item: CookingGroceryItem) => void
}

/**
 * The open shopping list, read when the activity opens and followed live (Mercure, the same
 * stream as the Courses page). A tick shows at once and goes back, with a message, when the API refuses it.
 */
export const useCookingGrocery = (): CookingGrocery => {
  const [attempt, setAttempt] = useState(0)
  const [load, setLoad] = useState<GroceryLoad>('loading')
  const [items, setItems] = useState<CookingGroceryItem[]>([])
  const [ticks, setTicks] = useState<Record<string, boolean>>({})
  const [notice, setNotice] = useState<string | null>(null)
  const reads = useRef<AbortController | null>(null)

  const read = useCallback(
    (initial: boolean) => {
      reads.current?.abort()
      const controller = new AbortController()
      reads.current = controller
      if (initial) setLoad('loading')
      fetchGroceryItems(controller.signal).then(
        (fresh) => {
          if (controller.signal.aborted) return
          setItems(fresh)
          setLoad('ready')
        },
        () => {
          if (!controller.signal.aborted && initial) setLoad('error')
        },
      )
    },
    [],
  )

  useEffect(() => {
    read(true)

    return () => reads.current?.abort()
  }, [read, attempt])

  useMercure(
    GROCERY_LIST_TOPICS,
    useCallback(
      (data?: string) => {
        if (isRecurringUpdate(data) || isSuggestionOnlyUpdate(data)) return
        try {
          const payload = data ? JSON.parse(data) : null
          if (payload?.action === 'check' && payload.itemId) {
            setItems((previous) => previous.map((item) => (item.id === payload.itemId ? { ...item, checked: payload.checked } : item)))
          } else if (payload?.action === 'remove' && payload.itemId) {
            setItems((previous) => previous.filter((item) => item.id !== payload.itemId))
          } else if (payload?.action === 'reorder') {
            read(false)
          } else if (Array.isArray(payload?.items)) {
            setItems(payload.items)
          } else {
            read(false)
          }
        } catch {
          read(false)
        }
      },
      [read],
    ),
  )

  const shown = useMemo(() => items.map((item) => (item.id in ticks ? { ...item, checked: ticks[item.id] } : item)), [items, ticks])

  const toggle = useCallback((item: CookingGroceryItem) => {
    const checked = !item.checked
    setTicks((previous) => ({ ...previous, [item.id]: checked }))
    void patchChecked(item.id, checked).then((saved) => {
      setTicks((previous) => {
        const rest = { ...previous }
        delete rest[item.id]

        return rest
      })
      if (saved) setItems((previous) => previous.map((line) => (line.id === item.id ? { ...line, checked } : line)))
      else setNotice(`Impossible de ${checked ? 'cocher' : 'décocher'} « ${item.label} »`)
    })
  }, [])

  const retry = useCallback(() => setAttempt((count) => count + 1), [])
  const clearNotice = useCallback(() => setNotice(null), [])

  return { load, items: shown, notice, retry, clearNotice, toggle }
}

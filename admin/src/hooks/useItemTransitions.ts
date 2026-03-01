import { useEffect, useMemo, useRef, useState } from 'react'

export const TRANSITION_DURATION = 500

/**
 * Tracks item additions/removals between renders and provides
 * animation state for smooth list transitions.
 *
 * Skips animations on initial data load — only animates subsequent changes
 * (e.g. Mercure live updates).
 */
export function useItemTransitions<T>(
  items: T[],
  getId: (item: T) => string,
): { addedIds: Set<string>; removingItems: T[] } {
  const getIdRef = useRef(getId)
  getIdRef.current = getId

  const prevMapRef = useRef<Map<string, T>>(new Map())
  const hasInitialDataRef = useRef(false)
  const [addedIds, setAddedIds] = useState<Set<string>>(new Set())
  const [removingItems, setRemovingItems] = useState<T[]>([])

  useEffect(() => {
    const fn = getIdRef.current
    const currentMap = new Map(items.map((i) => [fn(i), i]))

    // Skip animations until first non-empty data arrives (initial load)
    if (!hasInitialDataRef.current) {
      prevMapRef.current = currentMap
      if (currentMap.size > 0) hasInitialDataRef.current = true
      return
    }

    const prevMap = prevMapRef.current

    const added = new Set<string>()
    for (const id of currentMap.keys()) {
      if (!prevMap.has(id)) added.add(id)
    }

    const removed: T[] = []
    for (const [id, item] of prevMap) {
      if (!currentMap.has(id)) removed.push(item)
    }

    if (added.size > 0) {
      setAddedIds(added)
      setTimeout(() => setAddedIds(new Set()), TRANSITION_DURATION)
    }

    if (removed.length > 0) {
      setRemovingItems((prev) => [...prev, ...removed])
      setTimeout(() => {
        const rIds = new Set(removed.map(fn))
        setRemovingItems((prev) => prev.filter((i) => !rIds.has(fn(i))))
      }, TRANSITION_DURATION)
    }

    prevMapRef.current = currentMap
  }, [items])

  return { addedIds, removingItems }
}

/** sx mixin for a green fade-in highlight on newly added items */
export const addedSx = {
  '@keyframes item-added': {
    from: { backgroundColor: 'rgba(76, 175, 80, 0.2)' },
    to: { backgroundColor: 'transparent' },
  },
  animation: `item-added ${TRANSITION_DURATION}ms ease-out`,
} as const

/** sx mixin for a red fade-out on removed items */
export const removingSx = {
  '@keyframes item-removing': {
    from: { backgroundColor: 'rgba(244, 67, 54, 0.15)', opacity: 1 },
    to: { backgroundColor: 'transparent', opacity: 0 },
  },
  animation: `item-removing ${TRANSITION_DURATION}ms ease-out forwards`,
  pointerEvents: 'none',
} as const

/**
 * Returns the appropriate transition sx for an item, or undefined if no animation.
 */
export function transitionSx(
  itemId: string,
  addedIds: Set<string>,
  removingIds: Set<string>,
) {
  if (addedIds.has(itemId)) return addedSx
  if (removingIds.has(itemId)) return removingSx
  return undefined
}

/**
 * Convenience hook to get a removingIds Set from removingItems.
 */
export function useRemovingIds<T>(removingItems: T[], getId: (item: T) => string): Set<string> {
  return useMemo(() => new Set(removingItems.map(getId)), [removingItems, getId])
}

import { useState } from 'react'

/**
 * Where the cursor goes when its line leaves the list (qualified, corrected): the line that followed it,
 * else the one before, else nothing. `before` is the order the cursor was read in, `after` the order now.
 */
export const neighbourAfterRemoval = (before: string[], removedId: string, after: string[]): string | null => {
  const present = new Set(after)
  const at = before.indexOf(removedId)
  if (at < 0) return after[0] ?? null
  for (let i = at + 1; i < before.length; i++) {
    if (present.has(before[i])) return before[i]
  }
  for (let i = at - 1; i >= 0; i--) {
    if (present.has(before[i])) return before[i]
  }

  return null
}

export interface SequenceCursor {
  /** The line the fiche shows, or the last one it showed once back on the list; null on an empty list. */
  cursorId: string | null
  /** The fiche is on screen (a narrow window shows it instead of the list). */
  isOpen: boolean
  /** 1-based position of the cursor in the list; 0 when there is none. */
  position: number
  total: number
  hasPrevious: boolean
  hasNext: boolean
  open: (id: string) => void
  close: () => void
  previous: () => void
  next: () => void
}

/**
 * The list ↔ fiche cursor of the review pages (MAG-507): opening a line, « Précédent » / « Suivant » in
 * the list's order, back to the list keeping the position. A line that leaves the list under the cursor
 * hands it to the next one.
 */
export function useSequenceCursor(ids: string[]): SequenceCursor {
  const [stored, setStored] = useState<string | null>(null)
  const [isOpen, setIsOpen] = useState(false)
  const [seen, setSeen] = useState(ids)

  // Adjusted while rendering, from the previous list: the cursor never shows a line that left.
  if (seen.length !== ids.length || seen.some((id, i) => id !== ids[i])) {
    setSeen(ids)
    if (stored !== null && !ids.includes(stored)) setStored(neighbourAfterRemoval(seen, stored, ids))
  }

  const cursorId = stored !== null && !ids.includes(stored) ? neighbourAfterRemoval(seen, stored, ids) : stored

  const index = cursorId === null ? -1 : ids.indexOf(cursorId)
  const shown = isOpen && cursorId !== null

  const go = (to: number) => {
    if (to >= 0 && to < ids.length) setStored(ids[to])
  }

  return {
    cursorId,
    isOpen: shown,
    position: index + 1,
    total: ids.length,
    hasPrevious: index > 0,
    hasNext: index >= 0 && index < ids.length - 1,
    open: (id) => {
      setStored(id)
      setIsOpen(true)
    },
    close: () => setIsOpen(false),
    previous: () => go(index - 1),
    next: () => go(index + 1),
  }
}

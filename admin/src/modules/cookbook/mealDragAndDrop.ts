import { KeyboardCode } from '@dnd-kit/core'
import type { KeyboardCoordinateGetter } from '@dnd-kit/core'

export interface MealCellRef {
  slot: string
  dayIndex: number
}

export const mealCellId = ({ slot, dayIndex }: MealCellRef): string => `${slot}:${dayIndex}`

export function parseMealCellId(id: unknown): MealCellRef | null {
  const match = /^(\w+):([0-6])$/.exec(String(id))
  return match ? { slot: match[1], dayIndex: Number(match[2]) } : null
}

const ARROWS: Record<string, [number, number]> = {
  [KeyboardCode.Right]: [1, 0],
  [KeyboardCode.Left]: [-1, 0],
  [KeyboardCode.Down]: [0, 1],
  [KeyboardCode.Up]: [0, -1],
}

/** Where a keyboard drag is, tracked here because the rects the sensor hands over lag a key press behind. */
export interface KeyboardDrag {
  cell: string
  /** The meal's offset inside its cell, kept as it moves so it lands in the same spot. */
  offset: { x: number; y: number }
}

/**
 * An arrow key jumps to the neighbouring cell in that direction, whatever the
 * layout — the seven-column grid on a tablet or the day cards on a phone. The
 * default 25px step would crawl across a cell and never reach the next.
 *
 * The cell the meal is in is remembered in `drag` rather than read back from
 * `collisionRect`: that rect is measured after React has re-rendered, so two
 * quick key presses would both start from the cell the meal left.
 */
export const neighbourCellCoordinates =
  (drag: { current: KeyboardDrag | null }): KeyboardCoordinateGetter =>
  (event, { context: { droppableRects, droppableContainers, collisionRect } }) => {
    const direction = ARROWS[event.code]
    if (!direction || !collisionRect) {
      return undefined
    }

    const enabled = droppableContainers.getEnabled()

    if (!drag.current) {
      const centre = { x: collisionRect.left + collisionRect.width / 2, y: collisionRect.top + collisionRect.height / 2 }
      const here = enabled.find((container) => {
        const rect = droppableRects.get(container.id)
        return rect && centre.x >= rect.left && centre.x <= rect.right && centre.y >= rect.top && centre.y <= rect.bottom
      })
      const hereRect = here && droppableRects.get(here.id)
      if (!here || !hereRect) {
        return undefined
      }
      drag.current = { cell: String(here.id), offset: { x: collisionRect.left - hereRect.left, y: collisionRect.top - hereRect.top } }
    }

    const { cell, offset } = drag.current
    const fromRect = droppableRects.get(cell)
    if (!fromRect) {
      return undefined
    }

    const [dx, dy] = direction
    const from = { x: fromRect.left + fromRect.width / 2, y: fromRect.top + fromRect.height / 2 }

    let best: { id: string; left: number; top: number; distance: number } | null = null
    for (const container of enabled) {
      if (String(container.id) === cell) continue
      const rect = droppableRects.get(container.id)
      if (!rect) continue

      const to = { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
      const along = (to.x - from.x) * dx + (to.y - from.y) * dy
      const across = Math.abs((to.x - from.x) * dy) + Math.abs((to.y - from.y) * dx)
      // Strictly ahead, and not further sideways than ahead: the cell the
      // arrow points at, not one diagonally off.
      if (along <= 1 || across > along) continue

      const distance = Math.hypot(to.x - from.x, to.y - from.y)
      if (!best || distance < best.distance) {
        best = { id: String(container.id), left: rect.left, top: rect.top, distance }
      }
    }

    if (best) {
      drag.current = { cell: best.id, offset }
      return { x: best.left + offset.x, y: best.top + offset.y }
    }

    return { x: fromRect.left + offset.x, y: fromRect.top + offset.y }
  }

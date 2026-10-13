import { describe, test, expect } from 'vitest'
import type { KeyboardCoordinateGetter } from '@dnd-kit/core'
import { mealCellId, mealCollision, neighbourCellCoordinates, parseMealCellId } from './mealDragAndDrop'
import type { KeyboardDrag } from './mealDragAndDrop'

type Args = Parameters<KeyboardCoordinateGetter>

// The desktop week: a 100×80 cell per day, lunch above dinner.
const cellRect = (slot: string, dayIndex: number) => ({
  left: 100 + dayIndex * 100,
  top: slot === 'lunch' ? 100 : 200,
  width: 100,
  height: 80,
  right: 200 + dayIndex * 100,
  bottom: slot === 'lunch' ? 180 : 280,
})

const cells = ['lunch', 'dinner'].flatMap((slot) => [0, 1, 2, 3, 4, 5, 6].map((dayIndex) => ({ slot, dayIndex })))

// A meal 60×20 at the top-left of its cell.
const mealIn = (from: { slot: string; dayIndex: number }) => {
  const origin = cellRect(from.slot, from.dayIndex)
  return { left: origin.left, top: origin.top, width: 60, height: 20, right: origin.left + 60, bottom: origin.top + 20 }
}

const rects = new Map(cells.map((cell) => [mealCellId(cell), cellRect(cell.slot, cell.dayIndex)]))

const getter = (drag: { current: KeyboardDrag | null }) => neighbourCellCoordinates(drag)

const press = (code: string, from: { slot: string; dayIndex: number }, drag: { current: KeyboardDrag | null } = { current: null }) => {
  const context = {
    collisionRect: mealIn(from),
    droppableRects: rects,
    droppableContainers: { getEnabled: () => cells.map((cell) => ({ id: mealCellId(cell) })) },
  }

  return getter(drag)({ code, preventDefault: () => {} } as unknown as Args[0], { context, currentCoordinates: { x: 0, y: 0 } } as unknown as Args[1])
}

// Where the meal's top-left must be for it to sit in a cell as it sat in the first.
const placedIn = (slot: string, dayIndex: number) => {
  const rect = cellRect(slot, dayIndex)
  return { x: rect.left, y: rect.top }
}

describe('the keyboard moves a meal from cell to cell', () => {
  test.each([
    ['ArrowRight', { slot: 'lunch', dayIndex: 1 }, placedIn('lunch', 2)],
    ['ArrowLeft', { slot: 'lunch', dayIndex: 1 }, placedIn('lunch', 0)],
    ['ArrowDown', { slot: 'lunch', dayIndex: 1 }, placedIn('dinner', 1)],
    ['ArrowUp', { slot: 'dinner', dayIndex: 4 }, placedIn('lunch', 4)],
  ])('%s from %o', (code, from, expected) => {
    expect(press(code, from)).toEqual(expected)
  })

  test.each([
    ['ArrowLeft', { slot: 'lunch', dayIndex: 0 }],
    ['ArrowRight', { slot: 'dinner', dayIndex: 6 }],
    ['ArrowUp', { slot: 'lunch', dayIndex: 3 }],
    ['ArrowDown', { slot: 'dinner', dayIndex: 3 }],
  ])('stays put at the edge of the week: %s from %o', (code, from) => {
    const origin = cellRect(from.slot, from.dayIndex)
    expect(press(code, from)).toEqual({ x: origin.left, y: origin.top })
  })

  test('two quick presses share one journey, though the rects have not caught up', () => {
    const drag = { current: null }
    const start = { slot: 'dinner', dayIndex: 5 }

    // The meal is still measured in its first cell for the second press.
    expect(press('ArrowRight', start, drag)).toEqual(placedIn('dinner', 6))
    expect(press('ArrowUp', start, drag)).toEqual(placedIn('lunch', 6))
  })

  test('ignores keys that are not arrows', () => {
    expect(press('KeyA', { slot: 'lunch', dayIndex: 1 })).toBeUndefined()
  })
})

describe('cell ids', () => {
  test('round-trip', () => {
    expect(parseMealCellId(mealCellId({ slot: 'dinner', dayIndex: 3 }))).toEqual({ slot: 'dinner', dayIndex: 3 })
  })

  test.each(['', 'dinner', 'dinner:7', '01MEAL'])('%j is not a cell', (id) => {
    expect(parseMealCellId(id)).toBeNull()
  })
})

describe('mealCollision', () => {
  const droppableContainers = cells.map((cell) => ({ id: mealCellId(cell), disabled: false, node: { current: null }, rect: { current: null }, data: { current: undefined } }))
  const args = (
    pointerCoordinates: { x: number; y: number } | null,
    collisionRect = mealIn({ slot: 'lunch', dayIndex: 1 }),
    droppableRects: Map<string, ReturnType<typeof cellRect>> = rects,
  ) =>
    ({
      active: { id: 'meal' },
      collisionRect: { ...collisionRect },
      droppableRects,
      droppableContainers,
      pointerCoordinates,
    }) as unknown as Parameters<ReturnType<typeof mealCollision>>[0]

  const noKeyboardDrag = { current: null }

  test('a pointer over a cell drops in that cell', () => {
    const over = mealCollision(noKeyboardDrag)(args({ x: 350, y: 240 }))

    expect(over.map((c) => c.id)).toEqual([mealCellId({ slot: 'dinner', dayIndex: 2 })])
  })

  test('a pointer released over no cell drops nowhere, however near a cell is', () => {
    // Above the grid, and in the gap between two cells.
    expect(mealCollision(noKeyboardDrag)(args({ x: 350, y: 20 }))).toEqual([])
    expect(mealCollision(noKeyboardDrag)(args({ x: 350, y: 190 }))).toEqual([])
  })

  test('the keyboard, before its first arrow, is over the cell the meal sits in', () => {
    const over = mealCollision(noKeyboardDrag)(args(null, mealIn({ slot: 'dinner', dayIndex: 3 })))

    expect(over.map((c) => c.id)).toEqual([mealCellId({ slot: 'dinner', dayIndex: 3 })])
  })

  test('the keyboard is over the cell its last arrow chose, however tall the cells around it', () => {
    // Sunday lunch holds the meals of other journeys: three times as tall, the
    // dinner row pushed down. The meal sits at its top, nearer the centre of
    // Saturday lunch, or of Sunday dinner, than of its own cell.
    const tall = new Map(rects)
    tall.set(mealCellId({ slot: 'lunch', dayIndex: 6 }), { ...cellRect('lunch', 6), height: 300, bottom: 400 })
    for (const dayIndex of [0, 1, 2, 3, 4, 5, 6]) {
      tall.set(mealCellId({ slot: 'dinner', dayIndex }), { ...cellRect('dinner', dayIndex), top: 420, bottom: 500 })
    }
    const sundayLunch = mealCellId({ slot: 'lunch', dayIndex: 6 })
    const drag = { current: { cell: sundayLunch, offset: { x: 0, y: 0 } } }

    const over = mealCollision(drag)(args(null, mealIn({ slot: 'lunch', dayIndex: 6 }), tall))

    expect(over.map((c) => c.id)).toEqual([sundayLunch])
  })
})

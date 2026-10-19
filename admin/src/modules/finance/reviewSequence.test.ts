import { describe, test, expect } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { neighbourAfterRemoval, useSequenceCursor } from './reviewSequence'

describe('neighbourAfterRemoval', () => {
  test('hands over to the line that followed', () => {
    expect(neighbourAfterRemoval(['a', 'b', 'c'], 'b', ['a', 'c'])).toBe('c')
  })

  test('falls back on the line before when the last one left', () => {
    expect(neighbourAfterRemoval(['a', 'b', 'c'], 'c', ['a', 'b'])).toBe('b')
  })

  test('skips followers that left too', () => {
    expect(neighbourAfterRemoval(['a', 'b', 'c', 'd'], 'b', ['a', 'd'])).toBe('d')
    expect(neighbourAfterRemoval(['a', 'b', 'c'], 'b', ['a'])).toBe('a')
  })

  test('nothing is left: no neighbour', () => {
    expect(neighbourAfterRemoval(['a'], 'a', [])).toBeNull()
  })

  test('an unknown line takes the first one', () => {
    expect(neighbourAfterRemoval(['a', 'b'], 'z', ['b'])).toBe('b')
  })
})

describe('useSequenceCursor', () => {
  const setup = (initial: string[]) => renderHook(({ ids }) => useSequenceCursor(ids), { initialProps: { ids: initial } })

  test('starts closed, with no cursor', () => {
    const { result } = setup(['a', 'b', 'c'])

    expect(result.current).toMatchObject({ cursorId: null, isOpen: false, position: 0, total: 3, hasPrevious: false, hasNext: false })
  })

  test('opens a line and walks the list in order', () => {
    const { result } = setup(['a', 'b', 'c'])

    act(() => result.current.open('b'))
    expect(result.current).toMatchObject({ cursorId: 'b', isOpen: true, position: 2, hasPrevious: true, hasNext: true })

    act(() => result.current.next())
    expect(result.current).toMatchObject({ cursorId: 'c', position: 3, hasNext: false })

    act(() => result.current.next())
    expect(result.current.cursorId).toBe('c')

    act(() => result.current.previous())
    act(() => result.current.previous())
    act(() => result.current.previous())
    expect(result.current).toMatchObject({ cursorId: 'a', position: 1, hasPrevious: false })
  })

  test('closing keeps the position', () => {
    const { result } = setup(['a', 'b', 'c'])
    act(() => result.current.open('c'))

    act(() => result.current.close())

    expect(result.current).toMatchObject({ cursorId: 'c', isOpen: false, position: 3 })
  })

  test('a line that leaves moves the cursor to the one that followed', () => {
    const { result, rerender } = setup(['a', 'b', 'c'])
    act(() => result.current.open('b'))

    rerender({ ids: ['a', 'c'] })

    expect(result.current).toMatchObject({ cursorId: 'c', isOpen: true, position: 2, total: 2, hasNext: false })
  })

  test('the last line leaving moves the cursor back, the list emptying closes the fiche', () => {
    const { result, rerender } = setup(['a', 'b'])
    act(() => result.current.open('b'))

    rerender({ ids: ['a'] })
    expect(result.current).toMatchObject({ cursorId: 'a', isOpen: true })

    rerender({ ids: [] })
    expect(result.current).toMatchObject({ cursorId: null, isOpen: false, position: 0, total: 0 })
  })
})

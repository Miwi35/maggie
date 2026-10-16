import { describe, test, expect } from 'vitest'
import { restoreAt } from './dayReview'

describe('restoreAt — « Annuler » puts a task set aside back in its place (MAG-500)', () => {
  const original = ['a', 'b', 'c', 'd', 'e']

  test('at the index it held', () => {
    expect(restoreAt(['a', 'b', 'd', 'e'], 'c', original)).toEqual(['a', 'b', 'c', 'd', 'e'])
  })

  test('first when it was first', () => {
    expect(restoreAt(['b', 'c'], 'a', original)).toEqual(['a', 'b', 'c'])
  })

  test('after the last one still there before it, when others left too', () => {
    expect(restoreAt(['a', 'e'], 'd', original)).toEqual(['a', 'd', 'e'])
  })

  test('nothing changes when it is already there', () => {
    expect(restoreAt(['a', 'c'], 'c', original)).toEqual(['a', 'c'])
  })
})

import { describe, test, expect } from 'vitest'
import { placeOnDay } from './dayPlan'

describe('placeOnDay — where a task lands in the day (MAG-497)', () => {
  const day = ['A', 'B', 'C']

  test('a reserve task fills the free room after the last slot', () => {
    expect(placeOnDay(day, 'R', 'free')).toEqual(['A', 'B', 'C', 'R'])
  })

  test('a reserve task dropped past the last slot fills the free room too', () => {
    expect(placeOnDay(day, 'R', 4)).toEqual(['A', 'B', 'C', 'R'])
  })

  test('a reserve task dropped on a slot takes it: that slot goes back to the reserve', () => {
    expect(placeOnDay(day, 'R', 1)).toEqual(['A', 'R', 'C'])
  })

  test('with the five taken there is no free room', () => {
    expect(placeOnDay(['A', 'B', 'C', 'D', 'E'], 'R', 'free')).toBeNull()
    expect(placeOnDay(['A', 'B', 'C', 'D', 'E'], 'R', 4)).toEqual(['A', 'B', 'C', 'D', 'R'])
  })

  test('a task of the day moves to the slot it is dropped on, the others shift', () => {
    expect(placeOnDay(day, 'A', 2)).toEqual(['B', 'C', 'A'])
    expect(placeOnDay(day, 'C', 0)).toEqual(['C', 'A', 'B'])
    expect(placeOnDay(day, 'A', 1)).toEqual(['B', 'A', 'C'])
  })

  test('a task of the day dropped on the free room goes last', () => {
    expect(placeOnDay(day, 'A', 'free')).toEqual(['B', 'C', 'A'])
  })

  test('a task dropped where it already is changes nothing', () => {
    expect(placeOnDay(day, 'B', 1)).toBeNull()
    expect(placeOnDay(day, 'C', 'free')).toBeNull()
  })
})

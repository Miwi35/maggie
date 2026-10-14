import { describe, test, expect } from 'vitest'
import { centsForTravel, moveBoundary, sharesOf } from './envelopeSplit'

// Loisirs 300 €: Bars 100 €, Cinéma 50 €, 150 € libres.
const loisirs = sharesOf([10000, 5000], 30000)

describe('envelopeSplit', () => {
  test('the free part is what the daughters leave in the mother', () => {
    expect(loisirs).toEqual({ amounts: [10000, 5000], freeCents: 15000 })
  })

  test('moving the Bars|Cinéma boundary by 20 € gives Bars 120, Cinéma 30, free 150', () => {
    expect(moveBoundary(loisirs, 0, 2000)).toEqual({ amounts: [12000, 3000], freeCents: 15000 })
  })

  test('the last boundary trades with the free part', () => {
    expect(moveBoundary(loisirs, 1, 2000)).toEqual({ amounts: [10000, 7000], freeCents: 13000 })
  })

  test('a boundary cannot push a neighbour below zero', () => {
    expect(moveBoundary(loisirs, 0, 99999)).toEqual({ amounts: [15000, 0], freeCents: 15000 })
    expect(moveBoundary(loisirs, 0, -99999)).toEqual({ amounts: [0, 15000], freeCents: 15000 })
  })

  test('the last boundary cannot go past the mother', () => {
    expect(moveBoundary(loisirs, 1, 99999)).toEqual({ amounts: [10000, 20000], freeCents: 0 })
  })

  test('an over-allocated mother can only give back on its last boundary', () => {
    // Loisirs 100 €, Bars 200 €, Cinéma 10 €: free is -110 €.
    const over = sharesOf([20000, 1000], 10000)

    expect(moveBoundary(over, 1, 500)).toEqual({ amounts: [20000, 1000], freeCents: -11000 })
    expect(moveBoundary(over, 1, -500)).toEqual({ amounts: [20000, 500], freeCents: -10500 })
  })

  test('a pointer travel is snapped to whole euros', () => {
    // 200 px bar for 300 €: 1 px = 1,50 €.
    expect(centsForTravel(20, 200, 30000)).toBe(3000)
    expect(centsForTravel(1, 200, 30000)).toBe(200)
    expect(centsForTravel(10, 0, 30000)).toBe(0)
  })
})

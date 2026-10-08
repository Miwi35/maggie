import { describe, test, expect } from 'vitest'
import {
  canDecrease,
  decreaseQuantity,
  formatQuantity,
  increaseQuantity,
  parseQuantity,
  quantityStep,
  unitLabel,
} from './groceryQuantity'

describe('groceryQuantity', () => {
  test.each([
    ['piece', 1],
    ['pack', 1],
    ['bunch', 1],
    [undefined, 1],
    ['g', 100],
    ['kg', 0.1],
    ['ml', 100],
    ['cl', 10],
    ['l', 0.1],
  ])('the step of %s is %s', (unit, step) => {
    expect(quantityStep(unit)).toBe(step)
  })

  test('increasing never drifts on floats', () => {
    expect(increaseQuantity(0.2, 'kg')).toBe(0.3)
    expect(increaseQuantity(null, 'pack')).toBe(1)
  })

  test('decreasing stops at one step and never reaches zero', () => {
    expect(decreaseQuantity(3, 'pack')).toBe(2)
    expect(decreaseQuantity(1, 'pack')).toBe(1)
    expect(decreaseQuantity(150, 'g')).toBe(100)
    expect(decreaseQuantity(0.3, 'kg')).toBe(0.2)
  })

  test('canDecrease is false at the minimum or with no quantity', () => {
    expect(canDecrease(2, 'pack')).toBe(true)
    expect(canDecrease(1, 'pack')).toBe(false)
    expect(canDecrease(100, 'g')).toBe(false)
    expect(canDecrease(null, 'g')).toBe(false)
  })

  test('parseQuantity accepts positive numbers, comma or point, and refuses the rest', () => {
    expect(parseQuantity('2,5')).toBe(2.5)
    expect(parseQuantity(' 7 ')).toBe(7)
    expect(parseQuantity('')).toBeNull()
    expect(parseQuantity('0')).toBeNull()
    expect(parseQuantity('-1')).toBeNull()
    expect(parseQuantity('abc')).toBeNull()
    expect(parseQuantity('Infinity')).toBeNull()
  })

  test('units are named in French and agree with the quantity', () => {
    expect(unitLabel('pack', 1)).toBe('paquet')
    expect(unitLabel('pack', 2)).toBe('paquets')
    expect(unitLabel('jar', 1)).toBe('bocal')
    expect(unitLabel('jar', 3)).toBe('bocaux')
    expect(unitLabel('g', 500)).toBe('g')
    expect(unitLabel(undefined, 2)).toBe('')
    expect(formatQuantity(1.5)).toBe('1,5')
  })
})

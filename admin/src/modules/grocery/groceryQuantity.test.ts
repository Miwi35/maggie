import { describe, test, expect } from 'vitest'
import {
  canDecrease,
  decreaseQuantity,
  formatQuantity,
  increaseQuantity,
  isPackaged,
  lineUnit,
  packagingContent,
  parseQuantity,
  quantityHint,
  unitLabel,
} from './groceryQuantity'

describe('groceryQuantity', () => {
  const rice = { packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g' }

  test('only a product with a packaging has a quantity to manage', () => {
    expect(isPackaged(rice)).toBe(true)
    expect(isPackaged({ packagingUnit: 'jar' })).toBe(true)
    expect(isPackaged({})).toBe(false)
    expect(isPackaged({ packagingUnit: null })).toBe(false)
    expect(isPackaged(null)).toBe(false)
    expect(isPackaged(undefined)).toBe(false)
  })

  test('+ and − move by one packaging', () => {
    expect(increaseQuantity(1)).toBe(2)
    expect(increaseQuantity(null)).toBe(1)
    expect(decreaseQuantity(3)).toBe(2)
    expect(decreaseQuantity(1)).toBe(1)
  })

  test('canDecrease is false at one packaging or with no quantity', () => {
    expect(canDecrease(2)).toBe(true)
    expect(canDecrease(1)).toBe(false)
    expect(canDecrease(null)).toBe(false)
  })

  test('parseQuantity accepts a whole number of packagings and refuses the rest', () => {
    expect(parseQuantity(' 7 ')).toBe(7)
    expect(parseQuantity('2,5')).toBeNull()
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

  test('the line of a packaged product is counted in its packaging, whatever its own unit says', () => {
    expect(lineUnit(rice)).toBe('pack')
    expect(lineUnit({ packagingUnit: 'jar' })).toBe('jar')
    expect(lineUnit({})).toBeUndefined()
    expect(lineUnit(null)).toBeUndefined()
  })

  test('what one pack holds follows the count', () => {
    expect(packagingContent(rice)).toBe('(500 g)')
  })

  test('nothing to show when the packaging has no size, or there is no product', () => {
    expect(packagingContent({ packagingUnit: 'jar', packagingSize: null, packagingSizeUnit: null })).toBeNull()
    expect(packagingContent(null)).toBeNull()
    expect(packagingContent({})).toBeNull()
  })

  test('the quantity of a product without packaging is an indication, with its unit', () => {
    expect(quantityHint(300, 'g')).toBe('300 g')
    expect(quantityHint(2, 'piece')).toBe('2 pièces')
    expect(quantityHint(1, undefined)).toBe('1')
    expect(quantityHint(undefined, 'g')).toBeNull()
    expect(quantityHint(null, 'g')).toBeNull()
  })
})

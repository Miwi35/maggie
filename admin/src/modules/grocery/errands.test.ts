import { describe, expect, test } from 'vitest'
import { errandMatches, errandQuery, monthBounds, purchasedQuantity } from './errands'

describe('errands', () => {
  test('a month is the first day of it and of the next', () => {
    expect(monthBounds('2026-10')).toEqual({ start: '2026-10-01', end: '2026-11-01' })
    expect(monthBounds('2026-12')).toEqual({ start: '2026-12-01', end: '2027-01-01' })
    expect(monthBounds('2026-13')).toBeNull()
    expect(monthBounds('octobre')).toBeNull()
  })

  test('the query carries only the filters set', () => {
    expect(errandQuery({})).toEqual({})
    expect(errandQuery({ store: '/api/stores/01LIDL', month: '2026-10' })).toEqual({
      store: '/api/stores/01LIDL',
      'endedAt[after]': '2026-10-01',
      'endedAt[strictly_before]': '2026-11-01',
    })
  })

  test('an errand pushed live is kept only when it matches the filter', () => {
    const row = { id: '/api/errands/1', store: '/api/stores/01LIDL', endedAt: '2026-10-14T18:05:00+02:00' }

    expect(errandMatches({})(row)).toBe(true)
    expect(errandMatches({ store: '/api/stores/01LIDL', month: '2026-10' })(row)).toBe(true)
    expect(errandMatches({ store: '/api/stores/01AUCHAN' })(row)).toBe(false)
    expect(errandMatches({ month: '2026-09' })(row)).toBe(false)
  })

  test('a bought line says how much, and what one packaging holds when known', () => {
    expect(
      purchasedQuantity({ id: '1', label: 'Pâtes', quantity: 2, unit: 'pack', packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g' }),
    ).toBe('2 paquets · paquet de 500 g')
    expect(purchasedQuantity({ id: '2', label: 'Tomates', quantity: 3, unit: 'piece' })).toBe('3 pièces')
    expect(purchasedQuantity({ id: '3', label: 'Sel', quantity: null, unit: null })).toBe('')
  })
})

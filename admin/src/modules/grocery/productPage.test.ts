import { describe, expect, test } from 'vitest'
import { groupsByAisle, matchesPastille, matchesSearch, sortCycle, stockLook, storesLabel, usualAisle } from './productPage'
import type { ProductRow } from './productPage'

const row = (name: string, extra: Partial<ProductRow> = {}): ProductRow => ({ id: name, name, category: 'other', ...extra })

describe('productPage', () => {
  test('« De saison » trusts the API\'s inSeason, then falls back on the months', () => {
    expect(matchesPastille(row('a', { inSeason: true, seasonMonths: [1] }), 'season', 6)).toBe(true)
    expect(matchesPastille(row('a', { inSeason: false, seasonMonths: [6] }), 'season', 6)).toBe(false)
    expect(matchesPastille(row('a', { seasonMonths: [6] }), 'season', 6)).toBe(true)
    expect(matchesPastille(row('a'), 'season', 6)).toBe(false)
  })

  test('« Stock bas » keeps low and out, « Conditionnés » the packaged ones', () => {
    expect(matchesPastille(row('a', { stockState: 'low' }), 'low', 1)).toBe(true)
    expect(matchesPastille(row('a', { stockState: 'out' }), 'low', 1)).toBe(true)
    expect(matchesPastille(row('a', { stockState: 'in_stock' }), 'low', 1)).toBe(false)
    expect(matchesPastille(row('a', { packagingUnit: 'pack' }), 'packaged', 1)).toBe(true)
    expect(matchesPastille(row('a'), 'packaged', 1)).toBe(false)
    expect(matchesPastille(row('a'), 'all', 1)).toBe(true)
  })

  test('the search ignores case and accents', () => {
    expect(matchesSearch(row('Crème fraîche'), 'CREME')).toBe(true)
    expect(matchesSearch(row('Crème fraîche'), 'fraiche')).toBe(true)
    expect(matchesSearch(row('Crème fraîche'), 'lait')).toBe(false)
  })

  test('the usual aisle is the product\'s aisle in its preferred store', () => {
    const byStore = new Map([['s', [{ id: 'fresh', store: 's', name: 'Frais', position: 1 }]]])
    const base = { aisles: [{ store: '/api/stores/s', aisle: 'fresh' }] }

    expect(usualAisle(row('a', { ...base, preferredStore: '/api/stores/s' }), byStore)?.name).toBe('Frais')
    expect(usualAisle(row('a', { ...base, preferredStore: '/api/stores/t' }), byStore)).toBeUndefined()
    expect(usualAisle(row('a', base), byStore)).toBeUndefined()
  })

  test('the stores read « Super U · Biocoop », the preferred alone, or nothing', () => {
    const name = (iri?: string | null) => ({ u: 'Super U', b: 'Biocoop' })[String(iri)] ?? ''

    expect(storesLabel(row('a', { preferredStore: 'u', fallbackStore: 'b' }), name)).toBe('Super U · Biocoop')
    expect(storesLabel(row('a', { preferredStore: 'u' }), name)).toBe('Super U')
    expect(storesLabel(row('a'), name)).toBe('')
  })

  test('stock reads en stock, bas, épuisé', () => {
    expect(stockLook('in_stock').label).toBe('en stock')
    expect(stockLook('low').label).toBe('bas')
    expect(stockLook('out').label).toBe('épuisé')
    expect(stockLook('mystery').label).toBe('en stock')
    expect(stockLook(null).label).toBe('en stock')
  })

  test('the sort cycle skips rayon without a store', () => {
    expect(sortCycle(false)).toEqual(['name', 'stockState'])
    expect(sortCycle(true)).toEqual(['name', 'aisle', 'stockState'])
  })

  test('groups by aisle in the way through the store, « Sans rayon » last', () => {
    const aisles = [
      { id: 'bread', store: 's', name: 'Boulangerie', position: 1 },
      { id: 'fresh', store: 's', name: 'Frais', position: 2 },
    ]
    const groups = groupsByAisle(
      [row('a', { aisles: [{ store: '/api/stores/s', aisle: 'fresh' }] }), row('b'), row('c', { aisles: [{ store: 'other', aisle: 'bread' }] })],
      's',
      aisles,
    )

    expect(groups.map((g) => [g.title, g.rows.map((r) => r.name)])).toEqual([
      ['Frais', ['a']],
      ['Sans rayon', ['b', 'c']],
    ])
  })
})

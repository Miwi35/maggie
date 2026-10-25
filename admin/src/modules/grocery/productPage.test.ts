import { describe, expect, test } from 'vitest'
import { categoryName, groupsByAisle, groupsByCategory, matchesPastille, matchesSearch } from './productPage'
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

  test('a category is named in French, an unknown one is « Autre »', () => {
    expect(categoryName('dairy')).toBe('Produits laitiers')
    expect(categoryName('mystery')).toBe('Autre')
    expect(categoryName(null)).toBe('Autre')
  })

  test('groups by category in the order of the choices, « Autre » last, empty groups dropped', () => {
    const groups = groupsByCategory([row('a'), row('b', { category: 'grain' }), row('c', { category: 'produce' }), row('d', { category: 'mystery' })])

    expect(groups.map((g) => [g.title, g.rows.length])).toEqual([
      ['Fruits & Légumes', 1],
      ['Céréales', 1],
      ['Autre', 2],
    ])
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

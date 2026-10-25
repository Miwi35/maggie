import { describe, expect, test } from 'vitest'
import { composeGroceryState } from './groceryCardState'
import type { ApiGroceryItem, ApiRecipeNeed } from './groceryCardState'

const TODAY = '2026-10-14'

const line = (id: string, label: string, extra: Partial<ApiGroceryItem> = {}): ApiGroceryItem => ({
  id,
  label,
  checked: false,
  position: 0,
  ...extra,
})

const market = { id: 's-market', name: 'Supermarché', visitOrder: 2 }
const greengrocer = { id: 's-green', name: 'Primeur', visitOrder: 1 }

const need = (productId: string, name: string, date: string, slot: ApiRecipeNeed['slot'] = 'dinner'): ApiRecipeNeed => ({
  productId,
  name,
  date,
  slot,
})

const namesOf = (state: ReturnType<typeof composeGroceryState>) => state.stores.map((s) => s.items.map((i) => i.name))

describe('composeGroceryState', () => {
  test('counts the open lines only: not ticked, and not waiting for a later day', () => {
    const state = composeGroceryState(
      TODAY,
      [
        line('1', 'Pain'),
        line('2', 'Lait', { checked: true }),
        line('3', 'Cadeau', { buyAfter: '2026-10-15T00:00:00+00:00' }),
        line('4', 'Beurre', { buyAfter: '2026-10-14T00:00:00+00:00' }),
        line('5', 'Sel', { buyAfter: '2026-10-01' }),
      ],
      [],
    )

    expect(state.count).toBe(3)
    expect(namesOf(state)).toEqual([['Pain', 'Beurre', 'Sel']])
  })

  test('groups by store in visit order, the lines without a store last', () => {
    const state = composeGroceryState(
      TODAY,
      [line('1', 'Piles'), line('2', 'Pain', { store: market }), line('3', 'Radis', { store: greengrocer }), line('4', 'Sucre', { store: market })],
      [],
    )

    expect(state.stores.map((s) => [s.id, s.name, s.count])).toEqual([
      ['s-green', 'Primeur', 1],
      ['s-market', 'Supermarché', 2],
      [null, null, 1],
    ])
  })

  test('orders a store aisle by aisle then by position, « Sans rayon » last', () => {
    const state = composeGroceryState(
      TODAY,
      [
        line('1', 'Sans rayon', { store: market, position: 0 }),
        line('2', 'Yaourt', { store: market, position: 2, aisle: { position: 2, name: 'Frais' } }),
        line('3', 'Pommes', { store: market, position: 5, aisle: { position: 1, name: 'Fruits' } }),
        line('4', 'Poires', { store: market, position: 3, aisle: { position: 1, name: 'Fruits' } }),
        line('6', 'Cerises', { store: market, position: 9, aisle: { position: 1, name: 'Bio' } }),
      ],
      [],
    )

    expect(namesOf(state)).toEqual([['Cerises', 'Poires', 'Pommes', 'Yaourt', 'Sans rayon']])
  })

  test('shows five lines per store and tells the rest as a count', () => {
    const lines = Array.from({ length: 7 }, (_, i) => line(`m${i}`, `Article ${i}`, { store: market, position: i }))
    const state = composeGroceryState(TODAY, [...lines, line('g', 'Radis', { store: greengrocer })], [])

    const [green, supermarket] = state.stores
    expect(green).toMatchObject({ count: 1, more: 0 })
    expect(supermarket.count).toBe(7)
    expect(supermarket.items.map((i) => i.name)).toEqual(['Article 0', 'Article 1', 'Article 2', 'Article 3', 'Article 4'])
    expect(supermarket.more).toBe(2)
    expect(state.count).toBe(8)
  })

  test('tells the quantity, and drops the need of a packaged line', () => {
    const needed = [{ quantity: 300, unit: 'g' }]
    const state = composeGroceryState(
      TODAY,
      [
        line('1', 'Farine', { quantity: 2, unit: 'pack', needed, product: { packagingUnit: 'pack' }, position: 1 }),
        line('2', 'Sel', { quantity: 1, unit: 'piece', needed, product: { packagingUnit: null }, position: 2 }),
        line('3', 'Piles', { position: 3 }),
      ],
      [],
    )

    expect(state.stores[0].items).toEqual([
      { id: '1', name: 'Farine', quantity: 2, unit: 'pack', needed: null },
      { id: '2', name: 'Sel', quantity: 1, unit: 'piece', needed },
      { id: '3', name: 'Piles', quantity: null, unit: null, needed: null },
    ])
  })

  test('keeps a product once for the recipes, at its first meal, and maps the day to today or tomorrow', () => {
    const state = composeGroceryState(
      TODAY,
      [],
      [
        need('p-parmesan', 'Parmesan', '2026-10-14', 'dinner'),
        need('p-creme', 'Crème fraîche', '2026-10-15', 'lunch'),
        need('p-parmesan', 'Parmesan', '2026-10-15', 'lunch'),
      ],
    )

    expect(state.forRecipes).toEqual([
      { name: 'Parmesan', day: 'today', slot: 'dinner' },
      { name: 'Crème fraîche', day: 'tomorrow', slot: 'lunch' },
    ])
  })

  test('is empty with nothing to buy and nothing missing', () => {
    expect(composeGroceryState(TODAY, [], [])).toEqual({ count: 0, forRecipes: [], stores: [] })
  })
})

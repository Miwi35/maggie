import { describe, expect, test } from 'vitest'
import type { RecipeRecord } from '../recipeSheet'
import { cookingStores, mealNeeds, needOf, openCount, productKeyOf, shortfallOf } from './cookingGrocery'
import type { CookingGroceryItem } from './cookingGrocery'
import { neededLabel } from '../../grocery/groceryQuantity'

const TODAY = '2026-10-26'

const line = (product: string, quantity: number, unit: string) => ({ id: `l-${product}-${quantity}`, ingredient: { id: product, name: product }, ingredientName: product, quantity, unit })

/** The recipe as the API serves it for `guests`: from a base of 4. */
const recipe = (id: string, guests: number): RecipeRecord => ({
  id,
  name: id,
  servings: 4,
  ingredients: [line('PARMESAN', (200 * guests) / 4, 'g'), line('FARINE', (0.5 * guests) / 4, 'kg'), line('OEUF', (4 * guests) / 4, 'piece')],
})

const item = (id: string, product: string | null, extra: Partial<CookingGroceryItem> = {}): CookingGroceryItem => ({
  id,
  label: id,
  position: 0,
  checked: false,
  product: product ? { '@id': `/api/products/${product}` } : null,
  store: { id: 'S1', name: 'Carrefour', visitOrder: 1 },
  ...extra,
})

describe('mealNeeds', () => {
  test('sums, per product, the lines of every recipe of the queue', () => {
    const needs = mealNeeds(
      [
        { recipeId: 'A', servings: 4 },
        { recipeId: 'B', servings: 4 },
      ],
      { A: recipe('A', 4), B: recipe('B', 4) },
    )

    expect(needs.get('PARMESAN')).toEqual([{ quantity: 400, unit: 'g' }])
    expect(needs.get('OEUF')).toEqual([{ quantity: 8, unit: 'piece' }])
  })

  test('the same recipe launched for 2 instead of 4 needs half as much (the API scales, nothing is recomputed)', () => {
    const queue = [{ recipeId: 'A', servings: 2 }]
    const half = mealNeeds(queue, { A: recipe('A', 2) })
    const whole = mealNeeds([{ recipeId: 'A', servings: 4 }], { A: recipe('A', 4) })

    expect(neededLabel(half.get('PARMESAN'))).toBe('100 g')
    expect(neededLabel(whole.get('PARMESAN'))).toBe('200 g')
  })

  test('kilograms and litres count in grams and millilitres, so they add to the list\'s own units', () => {
    const needs = mealNeeds([{ recipeId: 'A', servings: 4 }], { A: recipe('A', 4) })

    expect(needs.get('FARINE')).toEqual([{ quantity: 500, unit: 'g' }])
  })

  test('units that cannot be added stay apart', () => {
    const mixed: RecipeRecord = { id: 'A', name: 'A', ingredients: [line('P', 100, 'g'), line('P', 2, 'piece')] }

    expect(mealNeeds([{ recipeId: 'A', servings: 4 }], { A: mixed }).get('P')).toEqual([
      { quantity: 100, unit: 'g' },
      { quantity: 2, unit: 'piece' },
    ])
  })

  test('a recipe not read yet and a line with no product need nothing', () => {
    const free: RecipeRecord = { id: 'A', name: 'A', ingredients: [{ id: 'x', ingredientName: 'Sel', quantity: 1, unit: 'g' }] }

    expect(mealNeeds([{ recipeId: 'A', servings: 4 }, { recipeId: 'Z', servings: 4 }], { A: free }).size).toBe(0)
  })

  test('a product is found whatever the notation of its id', () => {
    const upper = '01HZX0000000000000000000AB'
    const needs = mealNeeds([{ recipeId: 'A', servings: 4 }], { A: { id: 'A', name: 'A', ingredients: [line(upper, 1, 'g')] } })

    expect(needOf(item('x', null, { product: { id: upper.toLowerCase() } }), needs)).toEqual([{ quantity: 1, unit: 'g' }])
    expect(needOf(item('y', upper), needs)).toEqual([{ quantity: 1, unit: 'g' }])
  })
})

describe('productKeyOf', () => {
  test('reads the REST address and the Mercure id alike', () => {
    expect(productKeyOf(item('a', 'PARMESAN'))).toBe('PARMESAN')
    expect(productKeyOf(item('a', null, { product: { id: 'PARMESAN' } }))).toBe('PARMESAN')
    expect(productKeyOf(item('a', null))).toBeNull()
  })
})

describe('shortfallOf', () => {
  const need = [{ quantity: 300, unit: 'g' }]

  test('names the need when the list plans less of the same unit', () => {
    expect(shortfallOf(item('a', 'P', { quantity: 200, unit: 'g' }), need)).toEqual({ quantity: 300, unit: 'g' })
  })

  test('says nothing when the list covers it, plans as much, or counts in another unit', () => {
    expect(shortfallOf(item('a', 'P', { quantity: 300, unit: 'g' }), need)).toBeNull()
    expect(shortfallOf(item('a', 'P', { quantity: 500, unit: 'g' }), need)).toBeNull()
    expect(shortfallOf(item('a', 'P', { quantity: 1, unit: 'piece' }), need)).toBeNull()
    expect(shortfallOf(item('a', 'P'), need)).toBeNull()
  })

  test('compares a kilogram on the list with the grams of the recipe', () => {
    expect(shortfallOf(item('a', 'P', { quantity: 0.2, unit: 'kg' }), need)).toEqual({ quantity: 300, unit: 'g' })
  })

  test('a packaged line is compared on what was asked in base units, not on its packs', () => {
    const packaged = item('a', 'P', { product: { '@id': '/api/products/P', packagingUnit: 'pack', packagingSize: 100, packagingSizeUnit: 'g' }, quantity: 1, unit: 'pack', desiredQuantity: 100, desiredUnit: 'g' })

    expect(shortfallOf(packaged, need)).toEqual({ quantity: 300, unit: 'g' })
    expect(shortfallOf({ ...packaged, desiredQuantity: null }, need)).toBeNull()
  })
})

describe('openCount', () => {
  test('counts neither the ticked lines nor the ones put off to a later day', () => {
    const items = [
      item('open', 'A'),
      item('open2', null),
      item('ticked', 'B', { checked: true }),
      item('later', 'C', { buyAfter: '2026-10-30T00:00:00+00:00' }),
      item('today', 'D', { buyAfter: '2026-10-26T00:00:00+00:00' }),
    ]

    expect(openCount(items, TODAY)).toBe(3)
  })
})

describe('cookingStores', () => {
  const needs = mealNeeds([{ recipeId: 'A', servings: 4 }], { A: recipe('A', 4) })
  const aisle = (id: string, position: number) => ({ id, name: id, position })

  test('puts the lines of the meal first in each store and keeps the stores in visit order', () => {
    const lidl = { id: 'S2', name: 'Lidl', visitOrder: 0 }
    const stores = cookingStores(
      [
        item('lait', 'LAIT', { position: 1 }),
        item('parmesan', 'PARMESAN', { position: 2 }),
        item('pain', 'PAIN', { store: lidl, position: 1 }),
        item('oeuf', 'OEUF', { store: lidl, position: 2 }),
        item('beurre', 'BEURRE', { position: 3 }),
      ],
      needs,
      TODAY,
    )

    expect(stores.map((store) => store.name)).toEqual(['Lidl', 'Carrefour'])
    expect(stores[0].meal.map((entry) => entry.id)).toEqual(['oeuf'])
    expect(stores[0].sections.flatMap((section) => section.items.map((entry) => entry.id))).toEqual(['pain'])
    expect(stores[1].meal.map((entry) => entry.id)).toEqual(['parmesan'])
    expect(stores[1].sections.flatMap((section) => section.items.map((entry) => entry.id))).toEqual(['lait', 'beurre'])
  })

  test('keeps the aisles of the store for the rest, and the meal in the order of the aisles', () => {
    const stores = cookingStores(
      [
        item('lait', 'LAIT', { aisle: aisle('a2', 2) }),
        item('oeuf', 'OEUF', { aisle: aisle('a2', 2) }),
        item('parmesan', 'PARMESAN', { aisle: aisle('a1', 1) }),
        item('pain', 'PAIN', { aisle: aisle('a1', 1) }),
      ],
      needs,
      TODAY,
    )

    expect(stores[0].meal.map((entry) => entry.id)).toEqual(['parmesan', 'oeuf'])
    expect(stores[0].sections.map((section) => [section.aisle?.id, section.items.map((entry) => entry.id)])).toEqual([
      ['a1', ['pain']],
      ['a2', ['lait']],
    ])
  })

  test('leaves out the lines put off to a later day and a store left with nothing', () => {
    const stores = cookingStores([item('parmesan', 'PARMESAN', { buyAfter: '2026-11-02T00:00:00+00:00' })], needs, TODAY)

    expect(stores).toEqual([])
  })

  test('ticked lines stay where they are', () => {
    const stores = cookingStores([item('parmesan', 'PARMESAN', { checked: true })], needs, TODAY)

    expect(stores[0].meal).toHaveLength(1)
  })
})

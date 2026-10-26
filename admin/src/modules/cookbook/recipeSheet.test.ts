import { describe, expect, test } from 'vitest'
import { canonicalRecordId, nutritionPerServing, servingsLabel, shortName, stepRefLabel, stepsTitle } from './recipeSheet'
import type { ProductInfo, RecipeLine, RecipeStep } from './recipeSheet'

const step = (durationMinutes: number | null, background = false): RecipeStep => ({
  text: 'x',
  durationMinutes,
  background,
  ingredients: [],
  utensils: [],
  waitsFor: [],
})

describe('shortName', () => {
  test.each([
    ['Bœuf à braiser', 'Bœuf'],
    ['Planche à découper', 'Planche'],
    ['Bœuf (paleron)', 'Bœuf'],
    ['La cocotte', 'Cocotte'],
    ["L'oignon jaune", 'Oignon'],
    ['Oignons', 'Oignons'],
    ['du beurre', 'Beurre'],
  ])('%s reads %s', (name, short) => {
    expect(shortName(name)).toBe(short)
  })

  test('keeps a name made only of articles', () => {
    expect(shortName('Le')).toBe('Le')
  })
})

describe('stepRefLabel', () => {
  test('lists the steps with a dot between them', () => {
    expect(stepRefLabel([1, 3])).toBe('ét. 1 · 3')
    expect(stepRefLabel([2])).toBe('ét. 2')
  })

  test('is null when no step uses the ingredient', () => {
    expect(stepRefLabel([])).toBeNull()
  })
})

describe('stepsTitle', () => {
  test('adds the durations up and says how much is left to the pot', () => {
    expect(stepsTitle([step(10), step(null), step(167, true)])).toBe('Étapes · 2 h 57, dont 2 h 47 en fond')
    expect(stepsTitle([step(10), step(150, true), step(17)])).toBe('Étapes · 2 h 57, dont 2 h 30 en fond')
  })

  test('has no background part when nothing runs in the background', () => {
    expect(stepsTitle([step(10), step(20)])).toBe('Étapes · 30 min')
  })

  test('is « Étapes » alone when no step has a duration', () => {
    expect(stepsTitle([step(null)])).toBe('Étapes')
    expect(stepsTitle([])).toBe('Étapes')
  })
})

describe('servingsLabel', () => {
  test('agrees with the number', () => {
    expect(servingsLabel(1)).toBe('1 portion')
    expect(servingsLabel(4)).toBe('4 portions')
  })
})

describe('canonicalRecordId', () => {
  const ULID = '01M5PVZHGMHNHJ6VHJY6G0XWS1'

  test('spells a UUID-notation id the way the API names the record', () => {
    expect(canonicalRecordId('/api/recipes/01a16dbf-c614-8d63-236e-32f1a00ef321')).toBe('/api/recipes/01M5PVZHGMHNHJ6VHJY6G0XWS1')
  })

  test('upper-cases a lowercase ULID, bare or inside an IRI', () => {
    expect(canonicalRecordId(ULID.toLowerCase())).toBe(ULID)
    expect(canonicalRecordId(`/api/recipes/${ULID.toLowerCase()}`)).toBe(`/api/recipes/${ULID}`)
  })

  test('leaves a canonical id and anything that is not an id as it is', () => {
    expect(canonicalRecordId(`/api/recipes/${ULID}`)).toBe(`/api/recipes/${ULID}`)
    expect(canonicalRecordId('01R')).toBe('01R')
    expect(canonicalRecordId('')).toBe('')
  })
})

describe('nutritionPerServing (MAG-647)', () => {
  const line = (id: string, quantity: number, unit: string): RecipeLine => ({ ingredient: { id, name: id }, ingredientName: id, quantity, unit })
  const product = (id: string, extra: Partial<ProductInfo> = {}): [string, ProductInfo] => [id, { id, kcalPer100g: 100, proteinPer100g: 10, carbsPer100g: 0, fatPer100g: 0, ...extra }]

  test('2 pieces of 100 g count as 200 g, 75 cl as 750 g, 1 kg as 1000 g', () => {
    const products = new Map([product('onion', { pieceWeightGrams: 100 }), product('wine'), product('beef')])

    expect(nutritionPerServing([line('onion', 2, 'piece')], 1, products)?.kcal).toBe(200)
    expect(nutritionPerServing([line('wine', 75, 'cl')], 1, products)?.kcal).toBe(750)
    expect(nutritionPerServing([line('beef', 1, 'kg')], 1, products)?.kcal).toBe(1000)
  })

  test('a line with no weight or no nutrition is counted apart, the others stay right', () => {
    const products = new Map([product('beef'), product('onion', { pieceWeightGrams: null }), product('herbs', { kcalPer100g: null })])

    const result = nutritionPerServing([line('beef', 1, 'kg'), line('onion', 2, 'piece'), line('herbs', 1, 'bunch')], 4, products)

    expect(result).toMatchObject({ kcal: 250, protein: 25, skipped: 2 })
  })

  test('a bourguignon with beef in kg, onions in pieces and wine in cl brings protein and energy per person', () => {
    const products = new Map([
      product('beef', { kcalPer100g: 250, proteinPer100g: 20, fatPer100g: 17 }),
      product('onion', { kcalPer100g: 40, proteinPer100g: 1, carbsPer100g: 9, pieceWeightGrams: 100 }),
      product('wine', { kcalPer100g: 70, proteinPer100g: 0.1, carbsPer100g: 2.6 }),
      product('bunch', { kcalPer100g: null, proteinPer100g: null }),
    ])

    const result = nutritionPerServing(
      [line('beef', 1, 'kg'), line('onion', 2, 'piece'), line('wine', 75, 'cl'), line('bunch', 1, 'piece')],
      4,
      products,
    )

    expect(result).toMatchObject({ kcal: 776, protein: 51, skipped: 1 })
    expect(result!.protein).toBeGreaterThanOrEqual(25)
  })
})

import { describe, expect, test } from 'vitest'
import { isSuggestionOnlyUpdate, parseSuggestions, rhythmLabel, suggestionItem } from './purchaseSuggestions'
import type { PurchaseSuggestion } from './purchaseSuggestions'

const cafe: PurchaseSuggestion = {
  product: { id: '01CAFE', name: 'Café' },
  everyDays: 14,
  lastBoughtOn: '2026-10-01',
  dueOn: '2026-10-15',
  usualQuantity: 2,
  usualUnit: 'pack',
}

describe('purchase suggestions', () => {
  test.each([
    [1, 'tous les jours'],
    [7, 'toutes les sem.'],
    [14, 'toutes les 2 sem.'],
    [56, 'toutes les 8 sem.'],
    [3, 'tous les 3 j'],
    [10, 'tous les 10 j'],
    [27, 'tous les 27 j'],
    [28, 'toutes les 4 sem.'],
    [30, 'tous les mois'],
    [44, 'tous les mois'],
    [45, 'tous les 2 mois'],
    [63, 'tous les 2 mois'],
    [90, 'tous les 3 mois'],
  ])('%i days read « %s »', (days, label) => {
    expect(rhythmLabel(days)).toBe(label)
  })

  test('adding sends the name and the usual quantity, nothing for what is unknown', () => {
    expect(suggestionItem(cafe)).toEqual({ label: 'Café', quantity: 2, unit: 'pack' })
    expect(suggestionItem({ ...cafe, usualQuantity: null, usualUnit: null })).toEqual({ label: 'Café' })
  })

  test('only an array of suggestions is read as suggestions', () => {
    expect(parseSuggestions([cafe, { nope: true }])).toEqual([cafe])
    expect(parseSuggestions({})).toEqual([])
    expect(parseSuggestions(null)).toEqual([])
  })

  test("a product's or an errand's update concerns the suggestions only", () => {
    expect(isSuggestionOnlyUpdate(JSON.stringify({ '@id': '/api/products/01CAFE' }))).toBe(true)
    expect(isSuggestionOnlyUpdate(JSON.stringify({ '@id': '/api/errands/01E' }))).toBe(true)
    expect(isSuggestionOnlyUpdate(JSON.stringify({ '@id': '/api/grocery_lists/01L' }))).toBe(false)
    expect(isSuggestionOnlyUpdate(JSON.stringify({ action: 'check', itemId: 'x' }))).toBe(false)
    expect(isSuggestionOnlyUpdate(undefined)).toBe(false)
    expect(isSuggestionOnlyUpdate('not json')).toBe(false)
  })
})

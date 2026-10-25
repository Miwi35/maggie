import { describe, expect, test } from 'vitest'
import { servingsLabel, shortName, stepRefLabel, stepsTitle } from './recipeSheet'
import type { RecipeStep } from './recipeSheet'

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

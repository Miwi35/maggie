import { describe, expect, test } from 'vitest'
import { filterRecipes, lastMealLabel, stepsMinutes, tagPills } from './recipeTable'

const recipe = (name: string, tags: string[] = []) => ({ id: `/api/recipes/${name}`, name, tags })

describe('tagPills', () => {
  test('lists the tags the recipes carry, once each and capitalised, the mockup’s four first', () => {
    const pills = tagPills([recipe('A', ['four', 'rapide']), recipe('B', ['Rapide', 'zèbre']), recipe('C', ['végétarien', 'dessert']), recipe('D')])

    expect(pills.map((pill) => pill.label)).toEqual(['Végétarien', 'Rapide', 'Four', 'Dessert', 'Zèbre'])
    expect(pills.find((pill) => pill.label === 'Rapide')?.count).toBe(2)
  })

  test('has no pill when no recipe is tagged', () => {
    expect(tagPills([recipe('A')])).toEqual([])
  })
})

describe('filterRecipes', () => {
  const recipes = [recipe('Gratin dauphinois', ['four']), recipe('Salade express', ['Rapide', 'végétarien']), recipe('Crème brûlée', ['dessert', 'four'])]

  test('keeps everything without a tag or a search', () => {
    expect(filterRecipes(recipes, '', null)).toHaveLength(3)
  })

  test('keeps the recipes of a tag, whatever its case', () => {
    expect(filterRecipes(recipes, '', 'rapide').map((r) => r.name)).toEqual(['Salade express'])
    expect(filterRecipes(recipes, '', 'four').map((r) => r.name)).toEqual(['Gratin dauphinois', 'Crème brûlée'])
  })

  test('searches the name without caring for case or accents', () => {
    expect(filterRecipes(recipes, 'CREME', null).map((r) => r.name)).toEqual(['Crème brûlée'])
  })

  test('combines the search and the tag', () => {
    expect(filterRecipes(recipes, 'gratin', 'dessert')).toEqual([])
  })
})

describe('stepsMinutes', () => {
  test('adds up the durations of the steps, the ones run in the background too', () => {
    expect(stepsMinutes([{ durationMinutes: 10 }, { durationMinutes: 90, background: true }, { durationMinutes: null }])).toBe(100)
  })

  test('is null when no step has a duration', () => {
    expect(stepsMinutes([{ durationMinutes: null }])).toBeNull()
    expect(stepsMinutes(undefined)).toBeNull()
  })
})

describe('lastMealLabel', () => {
  test('reads the day as « 24 oct. »', () => {
    expect(lastMealLabel('2026-10-24')).toBe('24 oct.')
  })

  test('says nothing of a recipe never served', () => {
    expect(lastMealLabel(null)).toBe('—')
    expect(lastMealLabel(undefined)).toBe('—')
  })
})

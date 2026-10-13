import { describe, test, expect } from 'vitest'
import { categoryLabel, categoryTree, orderedCategories, parentChoices } from './categoryTree'

const LOISIRS = { id: '/api/categories/loisirs', name: 'Loisirs', path: 'Loisirs', depth: 1, parent: null }
const BARS = { id: '/api/categories/bars', name: 'Bars', path: 'Loisirs › Bars', depth: 2, parent: LOISIRS.id }
const OBSYDIAN = { id: '/api/categories/obsydian', name: 'Obsydian', path: 'Loisirs › Bars › Obsydian', depth: 3, parent: BARS.id }
const CINEMA = { id: '/api/categories/cinema', name: 'Cinéma', path: 'Loisirs › Cinéma', depth: 2, parent: LOISIRS.id }
const COURSES = { id: '/api/categories/courses', name: 'Courses', path: 'Courses', depth: 1, parent: null }
const ALL = [OBSYDIAN, COURSES, CINEMA, BARS, LOISIRS]

describe('categoryTree', () => {
  test('puts each category under its parent, whatever the order it arrives in', () => {
    expect(categoryTree(ALL).map((row) => [row.category.name, row.depth])).toEqual([
      ['Courses', 1],
      ['Loisirs', 1],
      ['Bars', 2],
      ['Obsydian', 3],
      ['Cinéma', 2],
    ])
  })

  test('a category whose parent is not in the list stays visible, at the top', () => {
    expect(categoryTree([OBSYDIAN]).map((row) => row.depth)).toEqual([1])
  })

  test('a cycle does not loop nor hide its rows', () => {
    const a = { id: 'a', name: 'A', parent: 'b' }
    const b = { id: 'b', name: 'B', parent: 'a' }

    expect(categoryTree([a, b]).map((row) => row.category.name)).toEqual(['A', 'B'])
  })

  test('orderedCategories gives the tree order to a picker', () => {
    expect(orderedCategories(ALL).map((c) => c.name)).toEqual(['Courses', 'Loisirs', 'Bars', 'Obsydian', 'Cinéma'])
  })
})

describe('categoryLabel', () => {
  test('is the path, and the name when the API gave no path', () => {
    expect(categoryLabel(OBSYDIAN)).toBe('Loisirs › Bars › Obsydian')
    expect(categoryLabel({ name: 'Courses' })).toBe('Courses')
    expect(categoryLabel(undefined)).toBe('')
  })
})

describe('parentChoices', () => {
  test('a new category goes under levels 1 and 2 only', () => {
    expect(parentChoices(ALL).map((c) => c.name)).toEqual(['Courses', 'Loisirs', 'Bars', 'Cinéma'])
  })

  test('an edited category is offered neither itself nor what sits under it', () => {
    expect(parentChoices(ALL, BARS.id).map((c) => c.name)).not.toContain('Obsydian')
    expect(parentChoices(ALL, BARS.id).map((c) => c.name)).not.toContain('Bars')
  })

  test('a category with children only goes where its subtree still fits in three levels', () => {
    expect(parentChoices(ALL, BARS.id).map((c) => c.name)).toEqual(['Courses', 'Loisirs'])
    expect(parentChoices(ALL, LOISIRS.id)).toEqual([])
  })

  test('a leaf can move under any level 1 or 2 category', () => {
    expect(parentChoices(ALL, OBSYDIAN.id).map((c) => c.name)).toEqual(['Courses', 'Loisirs', 'Bars', 'Cinéma'])
  })
})

import { describe, expect, test } from 'vitest'
import { groupByStoreAndAisle, hasAisles, sectionsByAisle } from './groceryAisles'
import type { AisleSummary, ListLine } from './groceryAisles'

const carrefour = { id: 'store-carrefour', name: 'Carrefour', visitOrder: 1 }
const biocoop = { id: 'store-biocoop', name: 'Biocoop', visitOrder: 2 }

const aisle = (id: string, name: string, position: number): AisleSummary => ({ id, name, position })

type Line = ListLine & { label: string }
const line = (label: string, position: number, extra: Partial<Line> = {}): Line => ({
  id: label,
  label,
  position,
  store: carrefour,
  ...extra,
})

const labels = (lines: Line[]) => lines.map((l) => l.label)

describe('groupByStoreAndAisle', () => {
  const fruits = aisle('a-fruits', 'Fruits et légumes', 1)
  const dairy = aisle('a-dairy', 'Crèmerie', 2)
  const bakery = aisle('a-bakery', 'Boulangerie', 3)

  test('a store lists its lines in the order of its aisles, not the order they were added', () => {
    const [group] = groupByStoreAndAisle([
      line('lait', 0, { aisle: dairy }),
      line('pommes', 1, { aisle: fruits }),
      line('pain', 2, { aisle: bakery }),
    ])

    expect(labels(group.items)).toEqual(['pommes', 'lait', 'pain'])
    expect(group.sections.map((s) => s.aisle?.name)).toEqual(['Fruits et légumes', 'Crèmerie', 'Boulangerie'])
    expect(hasAisles(group)).toBe(true)
  })

  test('swapping two aisles swaps their lines', () => {
    const [group] = groupByStoreAndAisle([
      line('lait', 0, { aisle: { ...dairy, position: 3 } }),
      line('pommes', 1, { aisle: fruits }),
      line('pain', 2, { aisle: { ...bakery, position: 2 } }),
    ])

    expect(labels(group.items)).toEqual(['pommes', 'pain', 'lait'])
  })

  test('the same product sits in a different aisle in each store', () => {
    const groups = groupByStoreAndAisle([
      line('lait carrefour', 0, { aisle: dairy }),
      line('pommes carrefour', 1, { aisle: fruits }),
      line('lait biocoop', 0, { store: biocoop, aisle: aisle('b-first', 'Frais', 1) }),
      line('pommes biocoop', 1, { store: biocoop, aisle: aisle('b-second', 'Vrac', 2) }),
    ])

    expect(groups.map((g) => g.storeName)).toEqual(['Carrefour', 'Biocoop'])
    expect(labels(groups[0].items)).toEqual(['pommes carrefour', 'lait carrefour'])
    expect(labels(groups[1].items)).toEqual(['lait biocoop', 'pommes biocoop'])
  })

  test('a line with no aisle comes last, under « Sans rayon »', () => {
    const [group] = groupByStoreAndAisle([
      line('piles', 0, { aisle: null }),
      line('pain', 1, { aisle: bakery }),
      line('pommes', 2, { aisle: fruits }),
    ])

    expect(labels(group.items)).toEqual(['pommes', 'pain', 'piles'])
    expect(group.sections.at(-1)?.aisle).toBeNull()
  })

  test('inside an aisle, the lines keep their own order', () => {
    const sections = sectionsByAisle([
      line('poires', 5, { aisle: fruits }),
      line('pommes', 1, { aisle: fruits }),
      line('kiwis', 3, { aisle: fruits }),
    ])

    expect(sections).toHaveLength(1)
    expect(labels(sections[0].items)).toEqual(['pommes', 'kiwis', 'poires'])
  })

  test('a store with no aisle at all is one section, by position, with no heading', () => {
    const [group] = groupByStoreAndAisle([line('b', 2), line('a', 1)])

    expect(group.sections).toHaveLength(1)
    expect(labels(group.items)).toEqual(['a', 'b'])
    expect(hasAisles(group)).toBe(false)
  })

  test('stores follow their visit order, lines with no store last', () => {
    const groups = groupByStoreAndAisle([
      line('sans magasin', 0, { store: undefined }),
      line('bio', 0, { store: biocoop }),
      line('carrefour', 0),
    ])

    expect(groups.map((g) => g.storeName)).toEqual(['Carrefour', 'Biocoop', 'Non assigné'])
  })
})

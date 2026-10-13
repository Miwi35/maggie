import { describe, test, expect } from 'vitest'
import { nestBudgetLines } from './budgetTree'
import type { BudgetLine } from './useBudgetStatus'

const line = (id: string, categoryPath: string, parentEnvelopeId: string | null): BudgetLine =>
  ({
    id,
    categoryName: categoryPath.split(' › ').pop() as string,
    categoryPath,
    parentEnvelopeId,
  }) as BudgetLine

describe('nestBudgetLines', () => {
  test('puts an envelope under its mother, named from the mother down', () => {
    const rows = nestBudgetLines([
      line('obsydian', 'Loisirs › Bars › Obsydian', 'loisirs'),
      line('courses', 'Alimentation', null),
      line('loisirs', 'Loisirs', null),
    ])

    expect(rows.map((r) => [r.line.id, r.depth, r.label])).toEqual([
      ['courses', 1, 'Alimentation'],
      ['loisirs', 1, 'Loisirs'],
      ['obsydian', 2, 'Bars › Obsydian'],
    ])
  })

  test('nests three levels deep', () => {
    const rows = nestBudgetLines([
      line('a', 'Loisirs', null),
      line('b', 'Loisirs › Bars', 'a'),
      line('c', 'Loisirs › Bars › Obsydian', 'b'),
    ])

    expect(rows.map((r) => [r.line.id, r.depth, r.label])).toEqual([
      ['a', 1, 'Loisirs'],
      ['b', 2, 'Bars'],
      ['c', 3, 'Obsydian'],
    ])
  })

  test('keeps a line whose mother is not listed at the top, under its full path', () => {
    const rows = nestBudgetLines([line('obsydian', 'Loisirs › Bars › Obsydian', 'gone')])

    expect(rows.map((r) => [r.depth, r.label])).toEqual([[1, 'Loisirs › Bars › Obsydian']])
  })
})

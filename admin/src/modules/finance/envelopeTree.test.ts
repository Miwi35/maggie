import { describe, test, expect } from 'vitest'
import {
  bareId,
  buildEnvelopeTree,
  consumptionState,
  daughtersOf,
  envelopePayload,
  findLine,
  monthlyLineOf,
  outsideEntries,
  sameSelection,
  selectionOfEnvelope,
} from './envelopeTree'
import { CATEGORIES, STATUS, line } from './envelopeTreeFixtures'

describe('buildEnvelopeTree', () => {
  test('orders the categories as a tree, each with the monthly envelope of the period', () => {
    const rows = buildEnvelopeTree(CATEGORIES, STATUS)

    expect(rows.map((r) => [r.category.name, r.depth, r.line?.id ?? null])).toEqual([
      ['Courses', 1, 'e-courses'],
      ['Loisirs', 1, 'e-loisirs'],
      ['Bars', 2, 'e-bars'],
      ['Obsydian', 3, null],
      ['Cinéma', 2, 'e-cinema'],
      ['Voyages', 1, null],
    ])
  })

  test('keeps the mother\'s cumulative consumption as the API gave it', () => {
    const loisirs = buildEnvelopeTree(CATEGORIES, STATUS).find((r) => r.category.name === 'Loisirs')

    expect(loisirs?.line?.consumedCents).toBe(4000)
    expect(loisirs?.line?.amountCents).toBe(30000)
  })

  test('draws every category without envelope while the status is not in', () => {
    const rows = buildEnvelopeTree(CATEGORIES, null)

    expect(rows).toHaveLength(CATEGORIES.length)
    expect(rows.every((r) => r.line === null)).toBe(true)
  })

  test('never draws a level past the third', () => {
    const deep = [
      ...CATEGORIES,
      { id: '/api/categories/deep', name: 'Profond', path: 'x', depth: 4, parent: '/api/categories/obsydian' },
    ]

    expect(Math.max(...buildEnvelopeTree(deep, STATUS).map((r) => r.depth))).toBe(3)
  })
})

describe('the envelopes apart from the month', () => {
  test('lists the annual ones, then the one-off ones by name', () => {
    expect(outsideEntries(STATUS).map((e) => [e.kind, e.label, e.id])).toEqual([
      ['annual', 'Voyages', 'e-voyages'],
      ['one_off', 'Hellfest 2026', 'e-hellfest'],
    ])
  })

  test('lists nothing before the status or without any', () => {
    expect(outsideEntries(null)).toEqual([])
    expect(outsideEntries({ ...STATUS, outsideMonthlyBudget: undefined })).toEqual([])
  })

  test('finds an envelope by bare id or by IRI, wherever it is listed', () => {
    expect(findLine(STATUS, 'e-voyages')?.id).toBe('e-voyages')
    expect(findLine(STATUS, '/api/envelopes/e-hellfest')?.id).toBe('e-hellfest')
    expect(findLine(STATUS, 'e-bars')?.id).toBe('e-bars')
    expect(findLine(STATUS, 'gone')).toBeNull()
  })
})

describe('daughtersOf', () => {
  test('are the envelopes directly below the mother, in the same period', () => {
    const mother = STATUS.budgets.find((l) => l.id === 'e-loisirs')!

    expect(daughtersOf(STATUS, mother).map((l) => l.id)).toEqual(['e-bars', 'e-cinema'])
    expect(daughtersOf(STATUS, STATUS.budgets.find((l) => l.id === 'e-bars')!)).toEqual([])
  })

  test('look among the annual envelopes for an annual mother', () => {
    const mother = line({ id: 'a1', mode: 'annual', month: null })
    const daughter = line({ id: 'a2', mode: 'annual', month: null, parentEnvelopeId: 'a1' })
    const status = { ...STATUS, outsideMonthlyBudget: { annual: [mother, daughter], oneOff: [] } }

    expect(daughtersOf(status, mother).map((l) => l.id)).toEqual(['a2'])
  })
})

describe('selections', () => {
  test('a category is found from its IRI or its bare id', () => {
    expect(monthlyLineOf(STATUS, '/api/categories/loisirs')?.id).toBe('e-loisirs')
    expect(monthlyLineOf(STATUS, 'obsydian')).toBeNull()
    expect(bareId('/api/categories/01ABC')).toBe('01ABC')
  })

  test('a saved monthly envelope selects its node, any other one its own entry', () => {
    expect(selectionOfEnvelope({ id: '/api/envelopes/e1', mode: 'monthly', category: '/api/categories/c1' })).toEqual({
      kind: 'category',
      categoryId: 'c1',
    })
    expect(selectionOfEnvelope({ id: '/api/envelopes/e2', mode: 'annual', category: '/api/categories/c1' })).toEqual({
      kind: 'envelope',
      envelopeId: 'e2',
    })
    expect(selectionOfEnvelope({ id: '/api/envelopes/e3', mode: 'one_off', category: null })).toEqual({
      kind: 'envelope',
      envelopeId: 'e3',
    })
  })

  test('compares selections by what they point at', () => {
    expect(sameSelection({ kind: 'category', categoryId: 'a' }, { kind: 'category', categoryId: 'a' })).toBe(true)
    expect(sameSelection({ kind: 'category', categoryId: 'a' }, { kind: 'envelope', envelopeId: 'a' })).toBe(false)
    expect(sameSelection({ kind: 'new-one-off' }, { kind: 'new-one-off' })).toBe(true)
    expect(sameSelection(null, { kind: 'new-one-off' })).toBe(false)
  })
})

describe('consumptionState', () => {
  test('overspent wins over overcommitted', () => {
    expect(consumptionState({ isOverspent: true, isOvercommitted: true })).toBe('overspent')
    expect(consumptionState({ isOverspent: false, isOvercommitted: true })).toBe('overcommitted')
    expect(consumptionState({ isOverspent: false, isOvercommitted: false })).toBe('ok')
  })
})

describe('envelopePayload', () => {
  test('takes the page\'s period for a monthly envelope', () => {
    expect(envelopePayload({ mode: 'monthly', category: '/api/categories/c', amountCents: 10000 }, 2026, 10)).toMatchObject({
      mode: 'monthly',
      year: 2026,
      month: 10,
      currency: 'EUR',
      amountCents: 10000,
    })
  })

  test('keeps the month of a stored monthly envelope', () => {
    expect(envelopePayload({ mode: 'monthly', year: 2026, month: 3 }, 2026, 10)).toMatchObject({ year: 2026, month: 3 })
  })

  test('an annual envelope carries no month nor seasons', () => {
    expect(envelopePayload({ mode: 'annual', month: 10, seasonalAmounts: [{ fromMonth: 1, toMonth: 2, amountCents: 1 }] }, 2026, 10)).toMatchObject({
      year: 2026,
      month: null,
      seasonalAmounts: [],
    })
  })

  test('an envelope switched from annual to monthly takes the page\'s month', () => {
    expect(envelopePayload({ mode: 'monthly', month: null, year: 2026 }, 2026, 10)).toMatchObject({ month: 10 })
  })

  test('a one-off envelope has neither category nor period', () => {
    expect(envelopePayload({ mode: 'one_off', name: 'Hellfest', amountCents: 40000 }, 2026, 10)).toMatchObject({
      name: 'Hellfest',
      category: null,
      year: null,
      month: null,
    })
  })
})

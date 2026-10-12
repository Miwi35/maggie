import { describe, test, expect, beforeEach, vi, afterEach } from 'vitest'
import {
  claimsOf,
  describeHistory,
  describePriceChange,
  describeReason,
  describeSchedule,
  readIgnored,
  seriesIri,
  writeIgnored,
} from './recurringOperations'
import type { RecurringProposal } from './recurringOperations'

const nbsp = (text: string) => text.replace(/\s/g, ' ')

describe('describeSchedule', () => {
  test.each([
    ['monthly', 'fixed_day', '2026-10-05', 'le 5 de chaque mois'],
    ['monthly', 'fixed_day', '2026-10-01', 'le 1er de chaque mois'],
    ['monthly', 'last_day_of_month', '2026-10-31', 'le dernier jour du mois'],
    ['weekly', 'fixed_day', '2026-10-12', 'chaque lundi'],
    ['quarterly', 'fixed_day', '2026-10-05', 'le 5, tous les trois mois'],
    ['quarterly', 'last_day_of_month', '2026-09-30', 'le dernier jour du mois, tous les trois mois'],
    ['yearly', 'fixed_day', '2026-03-05', 'le 5 mars de chaque année'],
    ['yearly', 'last_day_of_month', '2026-02-28', 'le dernier jour de février, chaque année'],
  ] as const)('%s / %s from %s reads « %s »', (period, dayRule, anchor, expected) => {
    expect(describeSchedule(period, dayRule, anchor)).toBe(expected)
  })

  test('reads an anchor sent as a full datetime', () => {
    expect(describeSchedule('monthly', 'fixed_day', '2026-10-05T00:00:00+00:00')).toBe('le 5 de chaque mois')
  })

  test('says only the period when the anchor is unknown', () => {
    expect(describeSchedule('monthly', 'fixed_day')).toBe('chaque mois')
    expect(describeSchedule('weekly', 'fixed_day')).toBe('chaque semaine')
    expect(describeSchedule('quarterly', 'fixed_day')).toBe('chaque trimestre')
    expect(describeSchedule('yearly', 'fixed_day')).toBe('chaque année')
  })
})

describe('price and reasons', () => {
  test('a price change reads « 13,49 € → 17,99 €, +33 % »', () => {
    expect(
      nbsp(describePriceChange({ previousCents: -1349, currentCents: -1799, percent: 33, since: '2026-11-12' })),
    ).toBe('13,49 € → 17,99 €, +33 %')
  })

  const proposal = (overrides: Partial<RecurringProposal>): RecurringProposal => ({
    transactionId: '01TX',
    label: 'PAIEMENT FLIXO',
    bookedAt: '2026-11-12',
    amountCents: -1799,
    reason: 'amount_up',
    recurringOperationId: '01OP',
    recurringOperationLabel: 'Abonnement Flixo',
    occurrenceOn: '2026-11-12',
    dateGapDays: 0,
    referenceAmountCents: -1349,
    amountChangePercent: 33,
    ...overrides,
  })

  test('an amount proposal names both amounts and the move', () => {
    expect(nbsp(describeReason(proposal({})))).toBe('hausse de prix 13,49 € → 17,99 €, +33 %')
    expect(nbsp(describeReason(proposal({ reason: 'amount_down', amountCents: -1000, amountChangePercent: -26 })))).toBe(
      'baisse de prix 13,49 € → 10,00 €, -26 %',
    )
  })

  test('a date proposal says how many days off the due date', () => {
    expect(describeReason(proposal({ reason: 'late', dateGapDays: 7, occurrenceOn: '2026-11-05' }))).toBe(
      "arrivée 7 jours après l'échéance du 05/11",
    )
    expect(describeReason(proposal({ reason: 'early', dateGapDays: -1, occurrenceOn: '2026-11-05' }))).toBe(
      "arrivée 1 jour avant l'échéance du 05/11",
    )
  })

  test('an ambiguous line is attachable to each of its candidates, a plain one to its own claim', () => {
    const candidates = [
      { recurringOperationId: '01A', recurringOperationLabel: 'A' },
      { recurringOperationId: '01B', recurringOperationLabel: 'B' },
    ]

    expect(claimsOf(proposal({ reason: 'ambiguous', recurringOperationId: null, candidates }))).toEqual(candidates)
    expect(claimsOf(proposal({}))).toHaveLength(1)
    expect(claimsOf(proposal({ recurringOperationId: null }))).toEqual([])
  })
})

describe('describeHistory', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-12T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('counts the lines and names the month the history starts in', () => {
    const lines = [{ bookedAt: '2026-03-12' }, { bookedAt: '2026-01-12' }, { bookedAt: '2026-02-12' }]

    expect(describeHistory('Netflix', lines)).toBe('3 transactions Netflix depuis janvier, les rattacher ?')
  })

  test('adds the year when the history starts before this one', () => {
    expect(describeHistory('Netflix', [{ bookedAt: '2025-11-12' }])).toBe(
      '1 transaction Netflix depuis novembre 2025, les rattacher ?',
    )
  })
})

describe('ignored proposals', () => {
  beforeEach(() => localStorage.clear())

  test('are remembered across reads', () => {
    expect(readIgnored()).toEqual([])
    writeIgnored(['01A', '01B'])
    expect(readIgnored()).toEqual(['01A', '01B'])
  })

  test('a corrupt store reads as empty', () => {
    localStorage.setItem('maggie.finance.recurring.ignoredProposals', '{nope')
    expect(readIgnored()).toEqual([])
  })
})

test('seriesIri accepts a bare id or an IRI', () => {
  expect(seriesIri('01OP')).toBe('/api/recurring_operations/01OP')
  expect(seriesIri('/api/recurring_operations/01OP')).toBe('/api/recurring_operations/01OP')
})

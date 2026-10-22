import { describe, expect, test } from 'vitest'
import { accountsFor, loanConditionsOf, remainder, segmentsOf, sourcesSummary, toFundingInputs, valuesOfProject } from './fundingForm'
import type { Project } from './projectsApi'

const project = (overrides: Partial<Project> = {}): Project => ({
  id: 'CAR',
  name: 'Voiture',
  amountCents: 1_000_000,
  currency: 'EUR',
  wellbeing: 'medium',
  wellbeingScore: 2,
  position: 0,
  dateMode: 'none',
  targetOn: null,
  dependsOn: [],
  note: null,
  unmetDependencies: [],
  fundingSources: [],
  salaryCents: 1_000_000,
  ...overrides,
})

const SAVINGS = { kind: 'savings' as const, amountCents: 100_000, accountId: 'A1', loanId: null }
const LOAN = { kind: 'loan' as const, amountCents: 600_000, accountId: null, loanId: 'L1' }

describe('sourcesSummary', () => {
  test('lists the kinds in a fixed order, then the salary', () => {
    expect(sourcesSummary(project({ fundingSources: [LOAN, SAVINGS], salaryCents: 300_000 }))).toBe('économies + emprunt + salaire')
  })

  test('has no salary once the sources pay everything', () => {
    expect(sourcesSummary(project({ fundingSources: [SAVINGS], salaryCents: 0 }))).toBe('économies')
  })

  test('is the salary alone without a source', () => {
    expect(sourcesSummary(project())).toBe('salaire')
  })
})

describe('remainder and segments', () => {
  test('the remainder is the amount minus the sources, never below 0', () => {
    expect(remainder(1_000_000, [SAVINGS, LOAN])).toBe(300_000)
    expect(remainder(1_000_000, [{ amountCents: 1_200_000 }])).toBe(0)
  })

  test('the segments are the sources then the salary', () => {
    const segments = segmentsOf(1_000_000, [
      { kind: 'savings', amountCents: 100_000, account: 'A1' },
      { kind: 'loan', amountCents: 600_000, account: '' },
    ])

    expect(segments.map((segment) => [segment.kind, segment.cents])).toEqual([
      ['savings', 100_000],
      ['loan', 600_000],
      ['salary', 300_000],
    ])
  })
})

describe('what is sent', () => {
  test('a source brought to 0 is taken off, a savings source names its account', () => {
    const inputs = toFundingInputs({
      sources: [
        { kind: 'savings', amountCents: 100_000, account: 'A1' },
        { kind: 'capital', amountCents: 0, account: 'A2' },
      ],
    })

    expect(inputs).toEqual([{ kind: 'savings', amountCents: 100_000, account: 'A1' }])
  })

  test('a loan source carries its conditions, without the empty ones', () => {
    const inputs = toFundingInputs({
      sources: [{ kind: 'loan', amountCents: 600_000, account: '', loan: { annualRateBasisPoints: 400, durationMonths: 24, feesCents: null, steps: [], name: 'x' } }],
    })

    expect(inputs).toEqual([{ kind: 'loan', amountCents: 600_000, loan: { annualRateBasisPoints: 400, durationMonths: 24 } }])
  })

  test('a loan whose conditions could not be read is sent without them', () => {
    expect(toFundingInputs({ sources: [{ kind: 'loan', amountCents: 600_000, account: '' }] })).toEqual([{ kind: 'loan', amountCents: 600_000 }])
  })

  test('the form starts from the stored sources and the loan conditions', () => {
    const values = valuesOfProject(project({ fundingSources: [SAVINGS, LOAN] }), { annualRateBasisPoints: 400, durationMonths: 24, initialAmountCents: 600_000 })

    expect(values.sources).toEqual([
      { kind: 'savings', amountCents: 100_000, account: 'A1' },
      { kind: 'loan', amountCents: 600_000, account: '', loan: { annualRateBasisPoints: 400, durationMonths: 24 } },
    ])
  })

  test('conditions keep only what the API takes', () => {
    expect(loanConditionsOf({ id: 'x', name: 'n', durationMonths: 12, type: 'in_fine', lender: '' })).toEqual({ durationMonths: 12, type: 'in_fine' })
  })
})

describe('accountsFor', () => {
  const accounts = [
    { id: 'A1', name: 'Livret jeune', type: 'savings', cushion: false },
    { id: 'A2', name: 'Matelas', type: 'savings', cushion: true },
    { id: 'A3', name: 'PEA', type: 'investment', cushion: false },
    { id: 'A4', name: 'Courant', type: 'checking', cushion: false },
  ]

  test('savings never come from the cushion, capital from an investment account', () => {
    expect(accountsFor('savings', accounts).map((account) => account.id)).toEqual(['A1', 'A4'])
    expect(accountsFor('capital', accounts).map((account) => account.id)).toEqual(['A3'])
    expect(accountsFor('loan', accounts)).toEqual([])
  })
})

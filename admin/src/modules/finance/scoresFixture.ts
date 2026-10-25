import type { FinanceScores, PeriodScore, Rhythm } from './useFinanceScores'

export const rhythm = (overrides: Partial<Rhythm> = {}): Rhythm => ({
  budgetCents: 100000,
  spentCents: 50000,
  expectedSpentCents: 60000,
  remainingCents: 50000,
  expectedRemainingCents: 40000,
  status: 'on_track',
  label: 'Dans le rythme',
  unclassifiedCount: 0,
  unclassifiedCents: 0,
  curve: [
    { date: '2026-10-04', spentCents: 0, expectedCents: 0 },
    { date: '2026-10-05', spentCents: 20000, expectedCents: 50000 },
    { date: '2026-10-06', spentCents: 30000, expectedCents: 52000 },
    { date: '2026-10-07', spentCents: null, expectedCents: 54000 },
  ],
  debits: [
    { date: '2026-10-05', label: 'Loyer', amountCents: 45000, recurringOperationId: 'op-1' },
  ],
  ...overrides,
})

export const period = (overrides: Partial<PeriodScore> = {}): PeriodScore => ({
  status: 'green',
  label: 'Dans le vert',
  reasons: [{ code: 'below_rhythm', amountCents: 10000 }],
  rhythm: rhythm(),
  ...overrides,
})

export const scores = (overrides: Partial<FinanceScores> = {}): FinanceScores => ({
  date: '2026-10-13',
  week: period({
    status: 'neutral',
    label: 'Dans les clous',
    reasons: [{ code: 'on_rhythm', amountCents: 2000 }],
  }),
  month: period(),
  global: { status: 'green', label: 'Dans le vert', reasons: [] },
  ...overrides,
})

import type { FinanceScores, PeriodScore, Rhythm } from './useFinanceScores'

export const rhythm = (overrides: Partial<Rhythm> = {}): Rhythm => ({
  spentCents: 50000,
  expectedSpentCents: 60000,
  plannedCents: 100000,
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
  reasons: [{ code: 'below_last_year', amountCents: 10000 }],
  rhythm: rhythm(),
  ...overrides,
})

export const scores = (overrides: Partial<FinanceScores> = {}): FinanceScores => ({
  date: '2026-10-13',
  week: period({
    status: 'neutral',
    label: 'Dans les clous',
    reasons: [],
  }),
  month: period(),
  global: { status: 'green', label: 'Dans le vert', reasons: [] },
  ...overrides,
})

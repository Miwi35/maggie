import { period } from './scoresFixture'
import type { MonthBalance } from './useMonthBalance'

export const monthBalanceFixture = (overrides: Partial<MonthBalance> = {}): MonthBalance => ({
  month: '2026-09',
  isCurrentMonth: false,
  spending: period({ status: 'red', label: 'Dépassement' }),
  envelopes: [
    { envelopeId: 'e1', name: 'Alimentation', budgetCents: 40000, consumedCents: 20000, percent: 50, status: 'held' },
    { envelopeId: 'e2', name: 'Loisirs', budgetCents: 20000, consumedCents: 17000, percent: 85, status: 'close' },
    { envelopeId: 'e3', name: 'Transport', budgetCents: 10000, consumedCents: 15000, percent: 150, status: 'over' },
  ],
  satisfaction: { judgedCents: 17000, keptCents: 12000, avoidableCents: 5000, unratedCount: 3, keptPercent: 71 },
  savings: { savedCents: 30000, plannedCents: 50000 },
  cushion: { state: 'building', targetCents: 600000, currentCents: 420000, coveragePercent: 70, monthsCovered: 2.8, isConfigured: true },
  toQualify: { count: 1, cents: 4000 },
  ...overrides,
})

import { scores } from './scoresFixture'
import type { FinanceDashboard, IndependenceCounter, NextProject, SavingCapacity } from './useFinanceDashboard'

const independence: IndependenceCounter = {
  coveragePercent: 60,
  lifestyleCents: 10000,
  passiveIncomeCents: 6000,
  gapCents: 4000,
  sampleMonths: 3,
  isMeasurable: true,
  hasPassiveIncomeCategories: true,
  isReached: false,
  loanPaymentsCents: 0,
  monthlyNeedCents: 10000,
  coverageWithDebtPercent: 60,
  nextMilestonePercent: 75,
  nextMilestoneGapCents: 1500,
  byCategory: [{ categoryId: 'c1', categoryName: 'Loyers perçus', monthlyCents: 6000, sharePercent: 100 }],
  projection: {
    startMonth: '2026-10',
    nDate: '2045-09',
    curve: [
      { month: '2026-10', coverage: 0.6 },
      { month: '2036-10', coverage: 0.8 },
      { month: '2045-09', coverage: 1 },
    ],
    milestones: { '25': '2026-10', '50': '2026-10', '75': '2033-03', '100': '2045-09' },
  },
  renteSources: {
    withdrawalRateBasisPoints: 400,
    placements: {
      count: 1,
      monthlyRentCents: 23807,
      items: [{ placementId: 'p1', name: 'PEA', monthlyRentCents: 23807, fromMonth: '2036-10', coveragePoints: 12 }],
    },
    investments: {
      count: 1,
      monthlyRentCents: 16000,
      items: [
        { projectId: 'BOX', name: 'Box à louer', monthlyRentCents: 16000, fromMonth: '2027-03', untilMonth: null, coveragePoints: 8 },
      ],
    },
  },
}

const savingCapacity: SavingCapacity = {
  monthlyNetIncomeCents: 235000,
  loanPaymentsCents: 21000,
  estimatedLifestyleCents: 16666,
  netCapacityCents: 197334,
  isIncomeKnown: true,
}

/** Always ahead of the real clock, so « dans N mois » never reads 0 or less. */
const monthsAhead = (count: number): string => {
  const date = new Date()
  date.setDate(1)
  date.setMonth(date.getMonth() + count)

  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
}

export const nextProjectFixture = (overrides: Partial<NextProject> = {}): NextProject => ({
  projectId: 'p1',
  name: 'Vélo',
  amountCents: 120000,
  gatheredCents: 74000,
  purchaseMonth: monthsAhead(4),
  following: [{ projectId: 'p2', name: 'Voyage au Japon', amountCents: 300000, purchaseMonth: monthsAhead(10) }],
  delay: {
    projectId: 'p2',
    name: 'Voyage au Japon',
    months: 1,
    overspentCents: 5800,
    envelopes: ['Restaurants', 'Obsydian'],
    newPurchaseMonth: monthsAhead(11),
  },
  ...overrides,
})

/** A whole dashboard payload; override the root fields or the tiles you care about. */
export const dashboardFixture = (overrides: Partial<FinanceDashboard> = {}): FinanceDashboard => ({
  year: 2026,
  month: 10,
  score: { referenceSalary: { monthlyCents: 300000, source: 'declared' } },
  balance: { totalCents: 0, cushionCents: 0, availableCents: 0, accounts: [] },
  monthlyFlows: [],
  budgets: [],
  topPosts: [],
  savingCapacity,
  independence,
  scores: scores(),
  tiles: {
    accounts: {
      totalCents: 604550,
      cushionCents: 420000,
      availableCents: 184550,
      accounts: [
        { id: 'a1', name: 'Compte courant', bank: 'Boursorama', balanceCents: 184550, currency: 'EUR', isCushion: false, rejectionsToSettleCount: 1 },
        { id: 'a2', name: 'Livret A', bank: null, balanceCents: 420000, currency: 'EUR', isCushion: true, rejectionsToSettleCount: 0 },
      ],
    },
    budget: {
      envelopes: [
        { envelopeId: 'e1', name: 'Courses', budgetCents: 40000, consumedCents: 25000, isOverspent: false, isOvercommitted: false },
        { envelopeId: 'e2', name: 'Loisirs', budgetCents: 10000, consumedCents: 12000, isOverspent: true, isOvercommitted: false },
        { envelopeId: 'e3', name: 'Sorties', budgetCents: 20000, consumedCents: 5000, isOverspent: false, isOvercommitted: true },
      ],
      cushion: { state: 'building', targetCents: 600000, currentCents: 420000, coveragePercent: 70, monthsCovered: 2.8, isConfigured: true },
      expectedSalary: { monthlyCents: 300000, source: 'expected', expectedOn: '2026-10-28' },
      balanceBeforeSalary: { onDate: '2026-10-28', balanceCents: 91000 },
    },
    planning: {
      independence,
      savingCapacity,
    },
  },
  nextProject: nextProjectFixture(),
  projectBeyondHorizon: null,
  todo: { toSortCount: 3, rejectionsToSettleCount: 1, reviewReady: false, advanceToRepayCents: 80000, owedToMeCents: 0, iOweCents: 0 },
  ...overrides,
})

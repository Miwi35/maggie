import type { BudgetLine, BudgetStatus, OneOffBudgetLine } from './useBudgetStatus'

/** The categories as the API serves them: IRIs for ids, a path and a depth each. */
export const CATEGORIES = [
  { id: '/api/categories/courses', name: 'Courses', path: 'Courses', depth: 1, parent: null },
  { id: '/api/categories/loisirs', name: 'Loisirs', path: 'Loisirs', depth: 1, parent: null },
  { id: '/api/categories/bars', name: 'Bars', path: 'Loisirs › Bars', depth: 2, parent: '/api/categories/loisirs' },
  { id: '/api/categories/obsydian', name: 'Obsydian', path: 'Loisirs › Bars › Obsydian', depth: 3, parent: '/api/categories/bars' },
  { id: '/api/categories/cinema', name: 'Cinéma', path: 'Loisirs › Cinéma', depth: 2, parent: '/api/categories/loisirs' },
  { id: '/api/categories/voyages', name: 'Voyages', path: 'Voyages', depth: 1, parent: null },
]

export const line = (overrides: Partial<BudgetLine>): BudgetLine => ({
  id: 'x',
  categoryId: 'c',
  categoryName: 'X',
  categoryPath: 'X',
  parentEnvelopeId: null,
  mode: 'monthly',
  amountCents: 10000,
  currency: 'EUR',
  year: 2026,
  month: 10,
  spentCents: 0,
  committedCents: 0,
  plannedCents: 0,
  toArbitrateCents: 0,
  allocatedToChildrenCents: null,
  freeCents: null,
  consumedCents: 0,
  remainingCents: 10000,
  availableCents: 10000,
  isOverspent: false,
  isOvercommitted: false,
  ...overrides,
})

export const HELLFEST: OneOffBudgetLine = {
  id: 'e-hellfest',
  name: 'Hellfest 2026',
  mode: 'one_off',
  amountCents: 40000,
  currency: 'EUR',
  spentCents: 8900,
  committedCents: 0,
  plannedCents: 0,
  toArbitrateCents: 0,
  consumedCents: 8900,
  remainingCents: 31100,
  availableCents: 31100,
  isOverspent: false,
  isOvercommitted: false,
}

/**
 * October 2026: Loisirs 300 € shared between Bars (100 €) and Cinéma (50 €),
 * 150 € left free; Courses overspent; Obsydian without an envelope; Voyages
 * and Hellfest apart from the month.
 */
export const STATUS: BudgetStatus = {
  year: 2026,
  month: 10,
  totalBudgetedCents: 0,
  totalSpentCents: 0,
  totalCommittedCents: 0,
  totalPlannedCents: 0,
  totalConsumedCents: 0,
  totalRemainingCents: 0,
  totalAvailableCents: 0,
  budgets: [
    line({
      id: 'e-courses',
      categoryId: 'courses',
      categoryName: 'Courses',
      categoryPath: 'Courses',
      amountCents: 20000,
      consumedCents: 22000,
      remainingCents: -2000,
      availableCents: -2000,
      isOverspent: true,
    }),
    line({
      id: 'e-loisirs',
      categoryId: 'loisirs',
      categoryName: 'Loisirs',
      categoryPath: 'Loisirs',
      amountCents: 30000,
      consumedCents: 4000,
      remainingCents: 26000,
      availableCents: 26000,
      allocatedToChildrenCents: 15000,
      freeCents: 15000,
    }),
    line({
      id: 'e-bars',
      categoryId: 'bars',
      categoryName: 'Bars',
      categoryPath: 'Loisirs › Bars',
      parentEnvelopeId: 'e-loisirs',
      amountCents: 10000,
      consumedCents: 4000,
      remainingCents: 6000,
      availableCents: 6000,
    }),
    line({
      id: 'e-cinema',
      categoryId: 'cinema',
      categoryName: 'Cinéma',
      categoryPath: 'Loisirs › Cinéma',
      parentEnvelopeId: 'e-loisirs',
      amountCents: 5000,
    }),
  ],
  outsideMonthlyBudget: {
    annual: [
      line({
        id: 'e-voyages',
        categoryId: 'voyages',
        categoryName: 'Voyages',
        categoryPath: 'Voyages',
        mode: 'annual',
        month: null,
        amountCents: 120000,
        consumedCents: 6000,
      }),
    ],
    oneOff: [HELLFEST],
  },
}

/** What the API stores of each envelope of `STATUS` (the status' amount may be a season's). */
export const STORED: Record<string, Record<string, unknown>> = Object.fromEntries(
  [...STATUS.budgets, ...(STATUS.outsideMonthlyBudget?.annual ?? [])].map((l) => [
    `/api/envelopes/${l.id}`,
    {
      id: `/api/envelopes/${l.id}`,
      category: `/api/categories/${l.categoryId}`,
      mode: l.mode,
      amountCents: l.amountCents,
      currency: 'EUR',
      year: 2026,
      month: l.month,
      alertThresholdPercent: null,
      seasonalAmounts: [],
    },
  ]),
)
STORED['/api/envelopes/e-hellfest'] = {
  id: '/api/envelopes/e-hellfest',
  mode: 'one_off',
  name: 'Hellfest 2026',
  amountCents: 40000,
  currency: 'EUR',
  category: null,
  year: null,
  month: null,
  seasonalAmounts: [],
}

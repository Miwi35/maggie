import type { UpcomingDay, UpcomingEnvelope, UpcomingItem, UpcomingMonth, UpcomingProjection, UpcomingWindow } from './useUpcomingProjection'

const range = (from: string, count: number): string[] => {
  const [year, month, day] = from.split('-').map(Number)

  return Array.from({ length: count }, (_, index) => {
    const date = new Date(Date.UTC(year, month - 1, day + index))

    return date.toISOString().slice(0, 10)
  })
}

const daysOf = (from: string, count: number, items: UpcomingItem[]): UpcomingDay[] =>
  range(from, count).map((date) => ({
    date,
    incomeCents: items.filter((item) => item.date === date && item.amountCents > 0).reduce((sum, item) => sum + item.amountCents, 0),
    expenseCents: items.filter((item) => item.date === date && item.amountCents < 0).reduce((sum, item) => sum - item.amountCents, 0),
  }))

const windowOf = (from: string, until: string, count: number, items: UpcomingItem[]): UpcomingWindow => {
  const incomes = items.filter((item) => item.amountCents > 0)
  const expenses = items.filter((item) => item.amountCents < 0)
  const incomeCents = incomes.reduce((sum, item) => sum + item.amountCents, 0)
  const expenseCents = expenses.reduce((sum, item) => sum - item.amountCents, 0)

  return { from, until, incomeCents, expenseCents, netCents: incomeCents - expenseCents, incomes, expenses, days: daysOf(from, count, items) }
}

export const SALARY: UpcomingItem = {
  date: '2026-11-25',
  label: 'Salaire ACME',
  amountCents: 250000,
  source: 'recurring',
  categoryId: 'c-salary',
  categoryName: 'Salaire',
}
export const RENT: UpcomingItem = {
  date: '2026-11-05',
  label: 'Loyer',
  amountCents: -90000,
  source: 'recurring',
  categoryId: 'c-housing',
  categoryName: 'Logement',
}
export const GIFT: UpcomingItem = {
  date: '2026-11-12',
  label: 'Cadeau Léa',
  amountCents: -3000,
  source: 'planned',
  categoryId: null,
  categoryName: null,
}
export const MARKET: UpcomingItem = {
  date: '2026-10-24',
  label: 'Marché',
  amountCents: -2000,
  source: 'committed',
  categoryId: 'c-groceries',
  categoryName: 'Courses',
}
const WEEK_SALARY: UpcomingItem = { ...SALARY, date: '2026-10-25' }

export const COURSES: UpcomingEnvelope = {
  envelopeId: 'e-courses',
  categoryId: 'c-groceries',
  categoryName: 'Courses',
  categoryPath: 'Courses',
  amountCents: 40000,
  committedCents: 8000,
  projectedCents: 40000,
  isOvercommitted: false,
}
export const LOGEMENT: UpcomingEnvelope = {
  envelopeId: 'e-housing',
  categoryId: 'c-housing',
  categoryName: 'Logement',
  categoryPath: 'Logement',
  amountCents: 80000,
  committedCents: 90000,
  projectedCents: 90000,
  isOvercommitted: true,
}

const monthOf = (items: UpcomingItem[], envelopes: UpcomingEnvelope[], unbudgetedCents: number): UpcomingMonth => {
  const window = windowOf('2026-11-01', '2026-12-01', 30, items)
  const envelopesCents = envelopes.reduce((sum, envelope) => sum + envelope.projectedCents, 0)

  return {
    ...window,
    month: '2026-11',
    envelopes,
    envelopesCents,
    unbudgetedCents,
    projectedExpenseCents: envelopesCents + unbudgetedCents,
    remainingCents: window.incomeCents - envelopesCents - unbudgetedCents,
  }
}

/** Read on Friday 2026-10-16: the week is 19 → 25 Oct, the month November 2026. */
export const upcomingFixture = (overrides: Partial<UpcomingProjection> = {}): UpcomingProjection => ({
  today: '2026-10-16',
  week: windowOf('2026-10-19', '2026-10-26', 7, [MARKET, WEEK_SALARY]),
  // 2 500 € in, 900 € + 400 € of envelopes, 30 € outside: 1 170 € remain.
  month: monthOf([SALARY, RENT, GIFT], [COURSES, LOGEMENT], 3000),
  ...overrides,
})

/** A month that spends more than it earns. */
export const shortMonth = (): UpcomingMonth => monthOf([{ ...SALARY, amountCents: 100000 }, RENT], [COURSES, LOGEMENT], 3000)

export const emptyProjection = (): UpcomingProjection => ({
  today: '2026-10-16',
  week: windowOf('2026-10-19', '2026-10-26', 7, []),
  month: monthOf([], [], 0),
})

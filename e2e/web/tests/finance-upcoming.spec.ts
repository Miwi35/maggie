import { test, expect, seedAnchorDate, seedId } from '../fixtures/index.js'
import { euros } from '../helpers/money.js'
import { FinanceOverviewPage } from '../pages/FinanceOverviewPage.js'

/**
 * Dashboard › À venir (MAG-463, extends MAG-102): the week and the month after
 * the current ones, read from the series and the planned lines, against the
 * envelopes of the month after as they stand.
 *
 * An account of its own, `e2e-upcoming@maggie.local` (58-finance-upcoming.yaml):
 * a salary of 2 500 € on the 25th, a rent of 700 € on the 5th and a market of
 * 20 € every week, with envelopes of the month after — Logement 800 €, adjusted
 * up from the 700 € it has now, and Courses 300 €. Every date is an offset from
 * the seed anchor, so what is asserted holds whatever the day:
 *
 *   remaining = 2 500 − 800 − 300 = 1 400 €   (left at 700 €, the rent would leave 1 500 €)
 */

const SALARY_CENTS = 250000
const RENT_CENTS = 70000
const MARKET_CENTS = 2000
const HOUSING_NEXT_CENTS = 80000
const GROCERIES_NEXT_CENTS = 30000
const REMAINING_CENTS = SALARY_CENTS - HOUSING_NEXT_CENTS - GROCERIES_NEXT_CENTS

const [ANCHOR_YEAR, ANCHOR_MONTH] = seedAnchorDate().split('-').map(Number)

/** The `day` of the month after the anchor's, as `YYYY-MM-DD`. */
const nextMonthDay = (day: number): string =>
  new Date(Date.UTC(ANCHOR_YEAR, ANCHOR_MONTH, day)).toISOString().slice(0, 10)

const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json' }

/**
 * Given a rent and a salary expected next month and an envelope adjusted for
 * it, When I open Dashboard › À venir, Then I see the recurring operations on
 * their dates and the remainder takes the adjusted envelope into account; and
 * when that envelope is raised, the remainder drops without a reload.
 *
 * One test and not two: the second step writes the envelope the first reads,
 * and the journeys of a file run in parallel.
 */
test('À venir shows the recurring operations on their dates and the remainder after the adjusted envelopes', async ({
  upcomingUser,
}) => {
  const overview = new FinanceOverviewPage(upcomingUser.page)
  await overview.open()

  // « État courant » is the default and « À venir » is one click away.
  await expect(overview.currentTab).toHaveAttribute('aria-selected', 'true')
  await overview.upcomingTab.click()
  await expect(overview.upcomingRemaining).toBeVisible()
  await expect(overview.accountsTile).toHaveCount(0)

  // The month after: the rent on the 5th, the salary on the 25th, each marked as a series.
  const month = overview.upcomingMonth
  const rent = overview.upcomingItem(month, 'Loyer')
  await expect(rent).toHaveAttribute('data-date', nextMonthDay(5))
  await expect(rent).toContainText('récurrente')
  await expect(rent).toContainText(euros(-RENT_CENTS))
  const salary = overview.upcomingItem(month, 'Salaire ACME')
  await expect(salary).toHaveAttribute('data-date', nextMonthDay(25))
  await expect(salary).toContainText(euros(SALARY_CENTS))
  await expect(month.getByTestId('upcoming-month-income')).toContainText(euros(SALARY_CENTS))
  await expect(month.getByTestId('upcoming-days')).toBeVisible()

  // The week after: the weekly market is in it, whatever the day of the week.
  const week = overview.upcomingWeek
  await expect(overview.upcomingItem(week, 'Marché')).toContainText(euros(-MARKET_CENTS))
  await expect(week.getByTestId('upcoming-day')).toHaveCount(7)

  // The envelopes as adjusted: Logement is 800 €, not the 700 € of this month.
  const housing = overview.upcomingEnvelope('Logement')
  await expect(housing).toContainText(euros(HOUSING_NEXT_CENTS))
  await expect(housing).toHaveAttribute('data-overcommitted', 'false')
  await expect(overview.upcomingEnvelope('Courses')).toContainText(euros(GROCERIES_NEXT_CENTS))
  await expect(overview.upcomingUnbudgeted).toContainText(euros(0))

  // What is left toward the projects, and nothing says the month is short.
  await expect(overview.upcomingRemaining.getByTestId('upcoming-remaining-amount')).toContainText(euros(REMAINING_CENTS))
  await expect(overview.upcomingRemaining).toHaveAttribute('data-short', 'false')

  // Raising an envelope of the month after lowers what is left, on screen, without a reload.
  const amount = overview.upcomingRemaining.getByTestId('upcoming-remaining-amount')
  const envelope = `/api/envelopes/${seedId('e2e_upcoming_food_next')}`
  try {
    const raised = await upcomingUser.api.patch(envelope, {
      data: { amountCents: GROCERIES_NEXT_CENTS + 20000 },
      headers: MERGE_PATCH,
    })
    expect(raised.ok(), await raised.text()).toBe(true)

    await expect(amount).toContainText(euros(REMAINING_CENTS - 20000))
    await expect(overview.upcomingEnvelope('Courses')).toContainText(euros(GROCERIES_NEXT_CENTS + 20000))
  } finally {
    await upcomingUser.api.patch(envelope, { data: { amountCents: GROCERIES_NEXT_CENTS }, headers: MERGE_PATCH })
  }

  // Back on « État courant », the home is as it was.
  await overview.currentTab.click()
  await expect(overview.accountsTile).toBeVisible()
  await expect(overview.upcomingRemaining).toHaveCount(0)
})

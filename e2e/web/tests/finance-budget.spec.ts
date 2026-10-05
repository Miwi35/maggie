import { test, expect, seedAnchorDate, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { euros, overBudget } from '../helpers/money.js'
import { MONTH_LABELS, SCORE_LABELS, periodLabel } from '../pages/financeLabels.js'
import { FinanceBudgetPage } from '../pages/FinanceBudgetPage.js'

/**
 * Envelopes, the day's score, and carrying a budget into the next month
 * (MAG-102).
 *
 * What this file asserts of the current month is deliberately limited to the
 * **annual** envelope and to the score's wording. The monthly "Courses" figure
 * belongs to `finance-rules.spec.ts`, which moves it by filing an
 * uncategorised operation — and the two files can land on the same shard, so
 * anything read here has to hold before and after that pass. "Loisirs" is
 * safe: the only rule that could reach it is seeded inactive.
 *
 * The roll-over writes, and it writes into the **next** month, where nothing
 * else looks.
 */

interface StoredEnvelope {
  id?: string
  category?: string
  mode?: string
  amountCents?: number
  year?: number
  month?: number | null
}

const COURSES_BUDGET_CENTS = 20000
const LEISURE_BUDGET_CENTS = 120000
/** 24,00 € this month and 120,00 € last month, both in "Loisirs", both spent. */
const LEISURE_SPENT_CENTS = 14400

/** The anchor's own month, and the one after it — the roll-over's target. */
const [ANCHOR_YEAR, ANCHOR_MONTH] = seedAnchorDate().split('-').map(Number)
const NEXT = 12 === ANCHOR_MONTH
  ? { year: ANCHOR_YEAR + 1, month: 1 }
  : { year: ANCHOR_YEAR, month: ANCHOR_MONTH + 1 }

test('the annual envelope is consumed by the whole year, the monthly one by its month', async ({
  page,
}) => {
  const budget = new FinanceBudgetPage(page)
  await budget.open()

  // Both modes, side by side, annual first — that ordering is the API's
  // (`findCoveringPeriod` sorts by mode), and it is what puts a yearly budget
  // and a monthly one on the same screen without them being confused.
  expect(await budget.gaugedCategories()).toEqual(['Loisirs', 'Courses'])

  const leisure = budget.gauge('Loisirs')
  await expect(leisure).toContainText(periodLabel('annual', ANCHOR_YEAR))
  await expect(
    leisure,
    'the cinema this month and the FNAC last month both fall inside the year',
  ).toContainText(overBudget(LEISURE_SPENT_CENTS, LEISURE_BUDGET_CENTS))
  await expect(leisure).toContainText(
    `Reste ${euros(LEISURE_BUDGET_CENTS - LEISURE_SPENT_CENTS)}`,
  )

  // The list below the gauges says what each envelope budgets, not what it
  // has consumed — the two are different screens' jobs. Addressed by category
  // *and* period: the list is not filtered by the picker, so a category holds
  // one row per period it budgets.
  const courses = budget.listRow('Courses', periodLabel('monthly', ANCHOR_YEAR, ANCHOR_MONTH))
  await expect(courses).toContainText('Mensuel')
  await expect(courses).toContainText(euros(COURSES_BUDGET_CENTS))

  const leisureRow = budget.listRow('Loisirs', periodLabel('annual', ANCHOR_YEAR))
  await expect(leisureRow).toContainText('Annuel')
  await expect(leisureRow).toContainText(euros(LEISURE_BUDGET_CENTS))
})

/**
 * The score, and the reason it is not green.
 *
 * `GetDailyScore` only ever turns red or orange on the budget; an incomplete
 * safety net cannot make the month bad, it can only hold green back. The
 * seeded net covers 1,8 months out of 3, so the month lands on "Dans les
 * clous" with the cushion named as the reason — and a banner that showed the
 * score without its cause would be a verdict, which is the one thing
 * `DailyScoreBanner` was written not to be.
 *
 * The deficit's amount is asserted in `finance-cushion.spec.ts`, which owns
 * the target it is computed from.
 */
test('the day score is held back by the safety net, and says so', async ({ page }) => {
  const budget = new FinanceBudgetPage(page)
  await budget.open()

  await expect(budget.scoreBanner).toContainText(SCORE_LABELS.neutral)
  await expect(budget.scoreBanner).toContainText('Matelas incomplet')
  await expect(
    budget.scoreBanner,
    'no envelope is overspent, so nothing is reported as exceeded',
  ).not.toContainText('dépassée')
})

test('a monthly envelope is carried into the next month, once and no more', async ({
  page,
  api,
}) => {
  const budget = new FinanceBudgetPage(page)
  await budget.open()
  await budget.selectPeriod(NEXT.year, NEXT.month)

  // No "and the month is empty to start with": the button only ever rolls the
  // month *before* the one on screen, so the target cannot be varied per
  // attempt the way a label can — and CI retries once without reseeding, so a
  // replay starts on the envelope the first attempt carried over. Every
  // assertion below is written to hold either way, which is also exactly what
  // the roll-over promises: running it twice changes nothing.
  await budget.rollOver()

  // The gauge is computed by the API from the envelope that is now there, so
  // it is the proof the write landed: the same 200,00 € budget, and a month
  // with nothing spent in it yet.
  await expect(budget.gauge('Courses')).toContainText(overBudget(0, COURSES_BUDGET_CENTS))
  await expect(budget.gauge('Courses')).toContainText(`Reste ${euros(COURSES_BUDGET_CENTS)}`)
  await expect(budget.gauge('Courses')).toContainText(
    periodLabel('monthly', NEXT.year, NEXT.month),
  )

  const carried = await waitForIndexed<StoredEnvelope>(
    api,
    '/api/envelopes',
    (envelope) =>
      envelope.year === NEXT.year && envelope.month === NEXT.month && 'monthly' === envelope.mode,
    { what: `The envelope carried into ${MONTH_LABELS[NEXT.month]} ${NEXT.year}` },
  )

  expect(carried.category).toBe(`/api/categories/${seedId('e2e_category_groceries')}`)
  expect(carried.amountCents).toBe(COURSES_BUDGET_CENTS)

  // Running it again is safe, and that is the whole contract: an envelope
  // already set on the target period is left alone rather than duplicated.
  await budget.rollOver()
  await expect(budget.gauge('Courses')).toHaveCount(1)

  const envelopes = await getCollection<StoredEnvelope>(api, '/api/envelopes')
  expect(
    envelopes.filter(
      (envelope) => envelope.year === NEXT.year && envelope.month === NEXT.month,
    ),
  ).toHaveLength(1)
})

import { test, expect, e2eNow, parisDay, seedId } from '../fixtures/index.js'
import { FinanceReviewActivityPage } from '../pages/FinanceReviewActivityPage.js'

/**
 * The review that closes a quarter (MAG-462, extends MAG-102): two steps more than
 * a month's — the balance of the period, then the planning of the next one — and
 * « Appliquer le plan » writes the proposed envelopes in one gesture.
 *
 * An account of its own, `e2e-period@maggie.local` (61-finance-period.yaml): a
 * current account and « Loisirs ». The quarter's spend is the journey's, dated in
 * the quarter the browser clock closes — the last March, June, September or
 * December before the stack's day, which the seed cannot name — so the plan
 * proposes « Loisirs » at a third of it for each month of the next quarter.
 */

interface PlanCategory {
  categoryName: string
  envelopeCents: number | null
}

interface Plan {
  categories: PlanCategory[]
}

const SPENT_CENTS = 30_000

/** The last month, today's included, that ends a quarter, as `YYYY-MM`. */
const closingMonth = (today: string): string => {
  const year = Number(today.slice(0, 4))
  const month = Number(today.slice(5, 7))
  const closing = month - (month % 3)

  return closing === 0 ? `${year - 1}-12` : `${year}-${String(closing).padStart(2, '0')}`
}

test('the review of a quarter plans the next one in one gesture', async ({ periodUser }) => {
  const { api, page } = periodUser
  const month = closingMonth(parisDay(e2eNow()))
  const json = { Accept: 'application/json' }

  // The browser lives on the 25th of that month: the review it launches closes a quarter.
  await page.context().clock.setFixedTime(new Date(`${month}-25T10:00:00Z`))

  // Given the quarter's spend, in « Loisirs »
  const spent = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      account: `/api/accounts/${seedId('e2e_period_checking')}`,
      category: `/api/categories/${seedId('e2e_period_leisure')}`,
      label: 'LOISIRS DU TRIMESTRE 3333',
      amountCents: -SPENT_CENTS,
      currency: 'EUR',
      bookedAt: `${month}-10`,
      status: 'spent',
    },
  })
  expect(spent.status(), await spent.text()).toBe(201)

  const progress = await api.get(`/api/finance/review/progress?month=${month}`, { headers: json })
  expect(progress.status(), await progress.text()).toBe(200)
  const { level, nextPeriod } = (await progress.json()) as { level: string; nextPeriod: { key: string } }
  expect(level).toBe('quarter')

  const planUrl = `/api/finance/annual-plan?period=${nextPeriod.key}`
  const read = async (): Promise<number | null> => {
    const res = await api.get(planUrl, { headers: json })
    expect(res.status(), await res.text()).toBe(200)

    return ((await res.json()) as Plan).categories.find((c) => c.categoryName === 'Loisirs')?.envelopeCents ?? null
  }
  const before = await read()

  // A plan refused on its last line leaves nothing behind.
  const refused = await api.post('/api/finance/annual-plan', {
    headers: { ...json, 'Content-Type': 'application/json' },
    data: {
      period: nextPeriod.key,
      items: [
        { kind: 'monthly', categoryId: seedId('e2e_period_leisure'), amountCents: 99_900, currency: 'EUR' },
        { kind: 'monthly', categoryId: '00000000000000000000000000', amountCents: 1_000, currency: 'EUR' },
      ],
    },
  })
  expect(refused.status()).toBe(400)
  expect(((await refused.json()) as { index: number }).index).toBe(1)
  expect(await read(), 'the first line was not written').toBe(before)

  // When I walk the review of the quarter to its planning
  const activity = new FinanceReviewActivityPage(page)
  await activity.open()
  await expect(activity.root.locator('[data-testid^="review-segment-"]')).toHaveCount(7)
  await expect(activity.root.getByTestId('review-segment-period_plan')).toBeVisible()
  for (let step = 0; step < 8; step++) {
    if ((await activity.root.getAttribute('data-step')) === 'period_balance') break
    await activity.next.click()
  }
  await expect(activity.root).toHaveAttribute('data-step', 'period_balance')
  await expect(activity.root.getByTestId('review-period-balance')).toBeVisible()
  await activity.next.click()
  await expect(activity.root).toHaveAttribute('data-step', 'period_plan')

  // … and apply the plan proposed
  const row = activity.root.getByTestId('plan-row').filter({ hasText: 'Loisirs' })
  await expect(row).toBeVisible()
  await activity.root.getByTestId('plan-apply').click()
  await expect(activity.root.getByTestId('plan-applied')).toBeVisible()

  // Then the envelopes of the next quarter exist: a third of the spend, three months over
  await expect.poll(read, { message: 'the envelopes of the next quarter' }).toBe(before ?? SPENT_CENTS)
})

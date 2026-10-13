import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate, seedDate, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { euros, overBudget } from '../helpers/money.js'
import { MONTH_LABELS, SCORE_LABELS, periodLabel } from '../pages/financeLabels.js'
import { FinanceAccountsPage } from '../pages/FinanceAccountsPage.js'
import { FinanceBudgetPage } from '../pages/FinanceBudgetPage.js'
import { FinanceCategoriesPage } from '../pages/FinanceCategoriesPage.js'

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

/**
 * Categories on three levels (MAG-411): Obsydian is an ordinary sub-category
 * of Bars, itself under Loisirs — nothing about it is specific to a merchant.
 *
 * As the **neighbour**, with categories of its own: the owner's figures
 * (gauges, score) must not move because a sub-category appears on a shard.
 */
const TREE_NAMES = ['Obsydian', 'Bars', 'Loisirs'] as const
const OBSYDIAN_PATH = 'Loisirs › Bars › Obsydian'
const treeAccountIds: string[] = []
const treeEnvelopeIds: string[] = []

/** Deepest first: the API refuses to delete a category that still has children. */
async function removeTree(api: APIRequestContext): Promise<void> {
  const categories = await getCollection<{ id?: string; name?: string }>(api, '/api/categories?itemsPerPage=200')
  for (const name of TREE_NAMES) {
    for (const category of categories.filter((candidate) => candidate.name === name)) {
      await api.delete(`/api/categories/${category.id}`)
    }
  }
}

async function createdId(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<string> {
  const response = await api.post(path, { headers: { 'Content-Type': 'application/ld+json' }, data })
  expect(response.status(), await response.text()).toBe(201)

  return String(((await response.json()) as { id?: string }).id ?? '')
}

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

test.describe('categories on three levels', () => {
  // Scoped to this test: a file-level hook would also run after the other tests, on the
  // other worker, and delete the tree while this one is still using it.
  test.afterEach(async ({ otherUser }) => {
    for (const id of treeEnvelopeIds.splice(0)) {
      await otherUser.api.delete(`/api/envelopes/${id}`)
    }
    for (const id of treeAccountIds.splice(0)) {
      await otherUser.api.delete(`/api/accounts/${id}`)
    }
    await removeTree(otherUser.api)
  })

  test('a category created under Bars sits on a third level, and a transaction can be filed in it', async ({
    otherUser,
  }) => {
    const { api, page } = otherUser
    await removeTree(api)

    // Given Loisirs › Bars.
    const loisirsId = await createdId(api, '/api/categories', { name: 'Loisirs', obligation: 'optional' })
    await createdId(api, '/api/categories', {
      name: 'Bars',
      obligation: 'optional',
      parent: `/api/categories/${loisirsId}`,
    })
    await waitForIndexed<{ path?: string }>(api, '/api/categories', (c) => c.path === 'Loisirs › Bars', {
      what: 'The category Loisirs › Bars',
    })

    // When I create « Obsydian » under Bars.
    const categories = new FinanceCategoriesPage(page)
    await categories.open()
    await expect(categories.addUnder('Loisirs')).toBeVisible()
    await categories.addUnder('Bars').click()
    await expect(
      categories.content.getByLabel('Rattacher à une catégorie'),
      'the form opens with the parent already chosen',
    ).toHaveValue('Loisirs › Bars')
    await categories.content.getByLabel('Nom').fill('Obsydian')
    await categories.content.getByLabel('Obligation').click()
    await page.getByRole('option', { name: 'Non-obligatoire' }).click()
    await categories.content.getByRole('button', { name: 'Enregistrer' }).click()
    await page.waitForURL((url) => !url.hash.includes('/create'))

    // Then the tree shows Loisirs › Bars › Obsydian, with nothing to add under the third level.
    const stored = await waitForIndexed<{ id?: string; path?: string; depth?: number }>(
      api,
      '/api/categories',
      (c) => c.path === OBSYDIAN_PATH,
      { what: `The category ${OBSYDIAN_PATH}` },
    )
    expect(stored.depth).toBe(3)

    await categories.open()
    // The neighbour's other categories may sit on the page: only the three of this journey count.
    await expect
      .poll(async () => (await categories.tree()).filter((row) => (TREE_NAMES as readonly string[]).includes(row.name)))
      .toEqual([
        { name: 'Loisirs', depth: 1 },
        { name: 'Bars', depth: 2 },
        { name: 'Obsydian', depth: 3 },
      ])
    await expect(categories.addUnder('Bars')).toBeVisible()
    await expect(categories.addUnder('Obsydian')).toHaveCount(0)

    // And a transaction can be filed in Obsydian, which the list names by its path.
    const accountId = await createdId(api, '/api/accounts', {
      name: 'Compte MAG-411',
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    treeAccountIds.push(accountId)

    const label = 'PAIEMENT PAR CARTE OBSYDIAN MAG-411'
    const accounts = new FinanceAccountsPage(page)
    await accounts.openTransactions(accountId)
    await accounts.createTransaction({
      label,
      amountEuros: 12,
      date: seedDate(),
      category: OBSYDIAN_PATH,
    })

    const transaction = await waitForIndexed<{ category?: string }>(
      api,
      '/api/transactions',
      (t: { label?: string; category?: string }) => t.label === label,
      { what: 'The transaction filed in Obsydian' },
    )
    expect(transaction.category).toBe(`/api/categories/${stored.id}`)

    await accounts.openTransactions(accountId)
    await expect(accounts.row(label)).toContainText(OBSYDIAN_PATH)
  })

  test('a spend counts in the envelope of its category and in its mother\'s, and the total counts it once', async ({
    otherUser,
  }) => {
    const { api, page } = otherUser
    await removeTree(api)

    // Given Loisirs at 300 € and Obsydian at 100 € this month, and no envelope on Bars.
    const loisirsId = await createdId(api, '/api/categories', { name: 'Loisirs', obligation: 'optional' })
    const barsId = await createdId(api, '/api/categories', {
      name: 'Bars',
      obligation: 'optional',
      parent: `/api/categories/${loisirsId}`,
    })
    const obsydianId = await createdId(api, '/api/categories', {
      name: 'Obsydian',
      obligation: 'optional',
      parent: `/api/categories/${barsId}`,
    })
    for (const [categoryId, amountCents] of [[loisirsId, 30000], [obsydianId, 10000]] as const) {
      treeEnvelopeIds.push(
        await createdId(api, '/api/envelopes', {
          category: `/api/categories/${categoryId}`,
          mode: 'monthly',
          amountCents,
          currency: 'EUR',
          year: ANCHOR_YEAR,
          month: ANCHOR_MONTH,
        }),
      )
    }
    const accountId = await createdId(api, '/api/accounts', {
      name: 'Compte MAG-412',
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    treeAccountIds.push(accountId)

    // When a 40 € spend is filed in Obsydian.
    await createdId(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      category: `/api/categories/${obsydianId}`,
      label: 'OBSYDIAN MAG-412',
      amountCents: -4000,
      currency: 'EUR',
      bookedAt: seedDate(),
      status: 'spent',
    })

    // Then Obsydian reads 40 / 100 € and Loisirs 40 / 300 €, Obsydian nested under it.
    const budget = new FinanceBudgetPage(page)
    await budget.open()
    await expect(budget.gauge('Obsydian')).toContainText(overBudget(4000, 10000))
    await expect(budget.gauge('Loisirs')).toContainText(overBudget(4000, 30000))
    expect(await budget.gaugedCategories()).toEqual(['Loisirs', 'Obsydian'])
    await expect(budget.gauge('Obsydian')).toHaveAttribute('data-depth', '2')
    await expect(budget.gauge('Obsydian')).toContainText('Bars › Obsydian')
    await expect(budget.gauge('Bars'), 'a category with no envelope is not shown as one').toHaveCount(0)
    await expect(budget.totalLine, 'the total is Loisirs\' 300 €, not 400 €').toContainText(
      `${euros(4000)} consommés sur ${euros(30000)}`,
    )
  })
})

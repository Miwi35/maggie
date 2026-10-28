import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate, seedDate, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { euros, overBudget } from '../helpers/money.js'
import { MONTH_LABELS, periodLabel } from '../pages/financeLabels.js'
import { FinanceAccountsPage } from '../pages/FinanceAccountsPage.js'
import { FinanceBudgetPage } from '../pages/FinanceBudgetPage.js'
import { FinanceCategoriesPage } from '../pages/FinanceCategoriesPage.js'

/**
 * Envelopes, the day's score, and carrying a budget into the next month
 * (MAG-102).
 *
 * What this file asserts of the current month is deliberately limited to the
 * **annual** envelope and to the page's shape. The monthly "Courses" figure
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
const TREE_NAMES = ['Obsydian', 'Bars', 'Loisirs', 'Cinéma'] as const
const HELLFEST = 'Hellfest 2026'
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

  // « Mois » first: the month's budget, as the tree of its envelopes (MAG-632).
  expect(
    await budget.gaugedCategories(),
    'Loisirs has an annual envelope only: it is on « Hors budget », not in the month',
  ).toEqual(['Courses'])
  await expect(budget.node('Courses')).toHaveAttribute('data-has-envelope', 'true')
  await expect(budget.node('Courses')).toContainText(`/ ${euros(COURSES_BUDGET_CENTS)}`)
  await expect(
    budget.content.getByTestId('envelope-outside'),
    'the annual envelope is not on the month: it has a tab of its own',
  ).toHaveCount(0)

  // Then « Hors budget » (MAG-414): a yearly budget and a monthly one without
  // them being confused, nor added up.
  await budget.openOutside()
  const leisure = budget.outsideNode('Loisirs')
  await expect(leisure).toHaveAttribute('data-kind', 'annual')
  await expect(leisure).toContainText(periodLabel('annual', ANCHOR_YEAR))
  await expect(
    leisure,
    'the cinema this month and the FNAC last month both fall inside the year',
  ).toContainText(overBudget(LEISURE_SPENT_CENTS, LEISURE_BUDGET_CENTS))
  await expect(leisure).toContainText(`/ ${euros(LEISURE_BUDGET_CENTS)}`)
})

/**
 * The shape of Budget (MAG-632): two tabs, « Mois » then « Hors budget », and
 * nothing of the Dashboard repeated — no ring, no « Budgets » title, no total.
 * The score and what holds it back are asserted on the Dashboard, in
 * `finance-overview.spec.ts`.
 */
test('Budget has two tabs, Mois and Hors budget, and repeats nothing of the Dashboard', async ({ page }) => {
  const budget = new FinanceBudgetPage(page)
  await budget.goto('/finance/summary/budget')

  await expect(budget.tabs()).toHaveText(['Mois', 'Hors budget'])
  await expect(budget.content.getByRole('tab', { name: 'Mois' })).toHaveAttribute('aria-selected', 'true')
  await expect(budget.tree()).toBeVisible()
  await expect(budget.content.getByTestId('score-ring')).toHaveCount(0)
  await expect(budget.content.getByRole('heading', { name: 'Budgets' })).toHaveCount(0)
  await expect(budget.content.getByText(/^Total :/)).toHaveCount(0)
  await expect(budget.content.getByText('Choisissez une catégorie')).toHaveCount(0)
  await expect(budget.editor(), 'the detail opens once a node is selected').toHaveCount(0)

  await budget.content.getByRole('tab', { name: 'Hors budget' }).click()
  await expect(budget.content.getByTestId('envelope-outside')).toBeVisible()
  await expect(budget.tree()).toHaveCount(0)
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

  // The node is computed by the API from the envelope that is now there, so
  // it is the proof the write landed: the same 200,00 € budget, and a month
  // with nothing spent in it yet.
  await expect(budget.gauge('Courses')).toContainText(overBudget(0, COURSES_BUDGET_CENTS))

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
  // In order, on one worker: every journey here reads the second user's own totals, which
  // another one's envelopes would move, and the clean-up removes the tree they all share.
  test.describe.configure({ mode: 'default' })

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

  test('a spend counts in the envelope of its category and in its mother\'s, nested in the tree', async ({
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
    await expect(budget.gauge('Obsydian')).toHaveAttribute('data-depth', '3')
    await expect(budget.node('Bars'), 'a category with no envelope is not shown as one').toHaveAttribute(
      'data-has-envelope',
      'false',
    )
  })

  test('a spend attributed to a one-off envelope leaves its category for « Hors budget » (MAG-414)', async ({
    otherUser,
  }) => {
    const { api, page } = otherUser
    await removeTree(api)

    // Given Loisirs at 300 € this month, a one-off « Hellfest 2026 » of 400 €, and an 89 € ticket filed in Loisirs.
    const loisirsId = await createdId(api, '/api/categories', { name: 'Loisirs', obligation: 'optional' })
    treeEnvelopeIds.push(
      await createdId(api, '/api/envelopes', {
        category: `/api/categories/${loisirsId}`,
        mode: 'monthly',
        amountCents: 30000,
        currency: 'EUR',
        year: ANCHOR_YEAR,
        month: ANCHOR_MONTH,
      }),
    )
    treeEnvelopeIds.push(
      await createdId(api, '/api/envelopes', { mode: 'one_off', name: HELLFEST, amountCents: 40000, currency: 'EUR' }),
    )
    const accountId = await createdId(api, '/api/accounts', {
      name: 'Compte MAG-414',
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    treeAccountIds.push(accountId)
    const label = 'BILLET HELLFEST MAG-414'
    await createdId(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      category: `/api/categories/${loisirsId}`,
      label,
      amountCents: -8900,
      currency: 'EUR',
      bookedAt: seedDate(),
      status: 'spent',
    })
    // The list and the sheet's choice are both served from Elasticsearch.
    await waitForIndexed<{ label?: string }>(api, '/api/transactions', (t) => t.label === label, { what: 'The Hellfest ticket' })
    await waitForIndexed<{ name?: string }>(api, '/api/envelopes', (e) => e.name === HELLFEST, { what: `The envelope ${HELLFEST}` })

    // When I attribute it to Hellfest from the transaction.
    const accounts = new FinanceAccountsPage(page)
    await accounts.openTransactions(accountId)
    await accounts.openTransaction(label)
    await accounts.content.getByLabel('Attribuer à une enveloppe ponctuelle').click()
    await page.getByRole('option', { name: HELLFEST }).click()
    const [saved] = await Promise.all([
      page.waitForResponse((response) => response.url().includes('/api/transactions/') && 'PATCH' === response.request().method()),
      accounts.content.getByRole('button', { name: 'Enregistrer' }).click(),
    ])
    expect(saved.status(), await saved.text()).toBe(200)

    // Then « Hors budget » shows Hellfest 89 / 400 €, and Loisirs no longer counts these 89 €.
    const budget = new FinanceBudgetPage(page)
    await budget.openOutside()
    await expect(budget.outsideNode(HELLFEST)).toContainText(overBudget(8900, 40000))
    await expect(budget.outsideNode(HELLFEST)).toContainText('Ponctuelle')
    await budget.open()
    await expect(budget.gauge('Loisirs'), 'the event counts in none of the month\'s figures').toContainText(
      overBudget(0, 30000),
    )
  })

  test('a monthly envelope rolled into its season is budgeted at the season\'s amount (MAG-415)', async ({
    otherUser,
  }) => {
    const { api, page } = otherUser
    await removeTree(api)

    // Given Cinéma at 20 € this month, with a season at 45 € from next month on, for two months
    // (the anchor moves with the seed: « novembre–février » is whatever month comes next).
    const season = { fromMonth: NEXT.month, toMonth: (NEXT.month % 12) + 1, amountCents: 4500 }
    const cinemaId = await createdId(api, '/api/categories', { name: 'Cinéma', obligation: 'optional' })
    treeEnvelopeIds.push(
      await createdId(api, '/api/envelopes', {
        category: `/api/categories/${cinemaId}`,
        mode: 'monthly',
        amountCents: 2000,
        currency: 'EUR',
        year: ANCHOR_YEAR,
        month: ANCHOR_MONTH,
        seasonalAmounts: [season],
      }),
    )

    // When I roll this month over into the next one.
    const budget = new FinanceBudgetPage(page)
    await budget.open()
    await expect(budget.gauge('Cinéma')).toContainText(overBudget(0, 2000))
    await budget.selectPeriod(NEXT.year, NEXT.month)
    await budget.rollOver()

    // Then next month's envelope is worth 45 €, and says which season sets it.
    await expect(budget.gauge('Cinéma')).toContainText(overBudget(0, 4500))
    await budget.selectNode('Cinéma')
    await expect(budget.editor()).toContainText(`${periodLabel('monthly', NEXT.year, NEXT.month)} · saison`)
    const carried = await waitForIndexed<StoredEnvelope & { seasonalAmounts?: unknown[] }>(
      api,
      '/api/envelopes',
      (envelope) =>
        envelope.category === `/api/categories/${cinemaId}` && envelope.year === NEXT.year && envelope.month === NEXT.month,
      { what: `Cinéma carried into ${MONTH_LABELS[NEXT.month]} ${NEXT.year}` },
    )
    if (carried.id) treeEnvelopeIds.push(carried.id)
    expect(carried.amountCents, 'the 20 € outside the season is kept').toBe(2000)
    expect(carried.seasonalAmounts).toEqual([season])
  })

  test('moving the Bars|Cinéma boundary by 20 € shares Loisirs again, and survives a reload', async ({
    otherUser,
  }) => {
    const { api, page } = otherUser
    await removeTree(api)

    // Given Loisirs at 300 €, Bars at 100 € and Cinéma at 50 € this month.
    const loisirsId = await createdId(api, '/api/categories', { name: 'Loisirs', obligation: 'optional' })
    const daughters: Record<string, string> = {}
    for (const name of ['Bars', 'Cinéma']) {
      daughters[name] = await createdId(api, '/api/categories', {
        name,
        obligation: 'optional',
        parent: `/api/categories/${loisirsId}`,
      })
    }
    for (const [categoryId, amountCents] of [
      [loisirsId, 30000],
      [daughters['Bars'], 10000],
      [daughters['Cinéma'], 5000],
    ] as const) {
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

    // When I open Loisirs' ruler and move the Bars|Cinéma boundary by 20 €.
    const budget = new FinanceBudgetPage(page)
    await budget.open()
    const ruler = await budget.openRuler('Loisirs')
    await expect(budget.rulerAmount(ruler, 'libre')).toContainText(`Libre ${euros(15000)}`)
    await budget.moveBoundary(ruler, 'Bars', 'Cinéma', 20)

    // Then, after a reload, Bars holds 120 €, Cinéma 30 € and 150 € stay free.
    await page.reload()
    await budget.open()
    await expect(budget.gauge('Bars')).toContainText(overBudget(0, 12000))
    await expect(budget.gauge('Cinéma')).toContainText(overBudget(0, 3000))
    const reloaded = await budget.openRuler('Loisirs')
    await expect(budget.rulerAmount(reloaded, 'Bars')).toContainText(euros(12000))
    await expect(budget.rulerAmount(reloaded, 'Cinéma')).toContainText(euros(3000))
    await expect(budget.rulerAmount(reloaded, 'libre')).toContainText(euros(15000))
    await expect(budget.gauge('Loisirs'), 'the mother itself did not move').toContainText(
      overBudget(0, 30000),
    )

    // And Loisirs' own form carries the same ruler, with a handle, without tapping "Répartir".
    const formRuler = await budget.openEnvelopeForm(treeEnvelopeIds[0])
    await expect(budget.rulerAmount(formRuler, 'Bars')).toContainText(euros(12000))
    await expect(budget.rulerAmount(formRuler, 'Cinéma')).toContainText(euros(3000))
    await expect(budget.rulerAmount(formRuler, 'libre')).toContainText(euros(15000))
    await expect(formRuler.getByRole('slider', { name: 'Frontière entre Bars et Cinéma' })).toBeVisible()
  })

  // MAG-419: the tree of envelopes. A category without an envelope gets one from its node, and
  // a setting changed there is the API's, still there after a reload.
  test('a category with no envelope gets one from its node in the tree', async ({ otherUser }) => {
    const { api, page } = otherUser
    await removeTree(api)

    // Given Loisirs › Bars › Obsydian, with an envelope on Loisirs only.
    const loisirsId = await createdId(api, '/api/categories', { name: 'Loisirs', obligation: 'optional' })
    const barsId = await createdId(api, '/api/categories', {
      name: 'Bars',
      obligation: 'optional',
      parent: `/api/categories/${loisirsId}`,
    })
    await createdId(api, '/api/categories', {
      name: 'Obsydian',
      obligation: 'optional',
      parent: `/api/categories/${barsId}`,
    })
    treeEnvelopeIds.push(
      await createdId(api, '/api/envelopes', {
        category: `/api/categories/${loisirsId}`,
        mode: 'monthly',
        amountCents: 30000,
        currency: 'EUR',
        year: ANCHOR_YEAR,
        month: ANCHOR_MONTH,
      }),
    )
    await waitForIndexed<{ path?: string }>(api, '/api/categories', (c) => c.path === OBSYDIAN_PATH, {
      what: `The category ${OBSYDIAN_PATH}`,
    })

    const budget = new FinanceBudgetPage(page)
    await budget.open()
    await expect(budget.node('Obsydian')).toHaveAttribute('data-depth', '3')
    await expect(budget.node('Obsydian')).toHaveAttribute('data-has-envelope', 'false')
    await expect(budget.node('Loisirs')).toContainText(`${euros(0)} / ${euros(30000)}`)

    // When I select Obsydian and create a monthly envelope of 100 €.
    await budget.selectNode('Obsydian')
    await budget.createEnvelope({ type: 'Mensuelle', amountEuros: 100 })

    // Then the tree shows Obsydian at 0 / 100 €, and Loisirs still shows its consumption.
    await expect(budget.node('Obsydian')).toHaveAttribute('data-has-envelope', 'true')
    await expect(budget.node('Obsydian')).toContainText(`${euros(0)} / ${euros(10000)}`)
    await expect(budget.node('Loisirs')).toContainText(`${euros(0)} / ${euros(30000)}`)

    const stored = await waitForIndexed<StoredEnvelope>(
      api,
      '/api/envelopes',
      (envelope) => envelope.amountCents === 10000 && 'monthly' === envelope.mode,
      { what: 'The envelope created on Obsydian' },
    )
    if (stored.id) treeEnvelopeIds.push(stored.id)
    expect(stored.year).toBe(ANCHOR_YEAR)
    expect(stored.month).toBe(ANCHOR_MONTH)
  })

  test('the alert threshold set on a node is still there after a reload', async ({ otherUser }) => {
    const { api, page } = otherUser
    await removeTree(api)

    // Given Loisirs at 300 € this month.
    const loisirsId = await createdId(api, '/api/categories', { name: 'Loisirs', obligation: 'optional' })
    treeEnvelopeIds.push(
      await createdId(api, '/api/envelopes', {
        category: `/api/categories/${loisirsId}`,
        mode: 'monthly',
        amountCents: 30000,
        currency: 'EUR',
        year: ANCHOR_YEAR,
        month: ANCHOR_MONTH,
      }),
    )

    await waitForIndexed<{ name?: string }>(api, '/api/categories', (c) => c.name === 'Loisirs', {
      what: 'The category Loisirs',
    })

    // When I set its threshold to 90 %.
    const budget = new FinanceBudgetPage(page)
    await budget.open()
    await budget.selectNode('Loisirs')
    const threshold = budget.editor().getByLabel("Seuil d'alerte (%)")
    await expect(threshold).toBeVisible()
    await threshold.fill('90')
    await budget.saveEnvelope()

    // Then, after a reload, it still reads 90.
    await page.reload()
    await budget.open()
    await budget.selectNode('Loisirs')
    await expect(budget.editor().getByLabel("Seuil d'alerte (%)")).toHaveValue('90')
  })
})

// MAG-461: next month's envelopes set against what was really spent. A suggestion only exists for
// the envelope that does not fit, and nothing is written before « Appliquer ».
//
// Its own account: it spends in the three months up to the clock's day, two of them complete, and
// on the neighbour that moves the lifestyle `finance-independence.spec.ts` divides by.
test('Loisirs exceeded three months running: « Ajuster » proposes a higher amount, applied on request', async ({
  adjustUser,
}) => {
  const { api, page } = adjustUser
  const envelopeIds: string[] = []
  const accountIds: string[] = []
  let loisirsId = ''

  try {
    // Given Loisirs budgeted 200 € and spent 250 € this month and the two before.
    loisirsId = await createdId(api, '/api/categories', { name: 'Loisirs', obligation: 'optional' })
    const accountId = await createdId(api, '/api/accounts', {
      name: 'Compte MAG-461',
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    accountIds.push(accountId)
    for (const back of [0, 1, 2]) {
      const index = ANCHOR_YEAR * 12 + (ANCHOR_MONTH - 1) - back
      const year = Math.floor(index / 12)
      const month = (index % 12) + 1
      envelopeIds.push(
        await createdId(api, '/api/envelopes', {
          category: `/api/categories/${loisirsId}`,
          mode: 'monthly',
          amountCents: 20000,
          currency: 'EUR',
          year,
          month,
        }),
      )
      await createdId(api, '/api/transactions', {
        account: `/api/accounts/${accountId}`,
        category: `/api/categories/${loisirsId}`,
        label: `LOISIRS MAG-461 ${year}-${month}`,
        amountCents: -25000,
        currency: 'EUR',
        bookedAt: `${year}-${String(month).padStart(2, '0')}-01`,
        status: 'spent',
      })
    }

    // When I open Budget › Ajuster and apply the suggestion.
    const budget = new FinanceBudgetPage(page)
    await budget.open()
    const row = await budget.openAdjustments('Loisirs')
    await expect(row.getByTestId('adjustment-actual')).toContainText(euros(25000))
    await expect(row.getByTestId('adjustment-suggestion')).toContainText(`Relever à ${euros(25000)}`)
    await expect(row.getByTestId('adjustment-amount'), 'nothing is written before the gesture').toContainText(euros(20000))
    const applied = await budget.applySuggestion('Loisirs')

    // Then Loisirs is worth 250 € next month.
    expect(applied.applied).toEqual([expect.objectContaining({ categoryId: loisirsId, amountCents: 25000 })])
    envelopeIds.push(applied.applied[0].envelopeId)
    await expect(row.getByTestId('adjustment-suggestion'), 'the row is read again: it now fits').toHaveCount(0)
    await expect(row.getByTestId('adjustment-amount')).toContainText(euros(25000))
    const stored = await waitForIndexed<StoredEnvelope>(
      api,
      '/api/envelopes',
      (envelope) =>
        envelope.category === `/api/categories/${loisirsId}` && envelope.year === NEXT.year && envelope.month === NEXT.month,
      { what: `Loisirs set in ${MONTH_LABELS[NEXT.month]} ${NEXT.year}` },
    )
    expect(stored.amountCents).toBe(25000)
  } finally {
    for (const id of envelopeIds) await api.delete(`/api/envelopes/${id}`)
    for (const id of accountIds) await api.delete(`/api/accounts/${id}`)
    if (loisirsId) await api.delete(`/api/categories/${loisirsId}`)
  }
})

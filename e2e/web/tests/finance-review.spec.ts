import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { FinanceReviewPage } from '../pages/FinanceReviewPage.js'

/**
 * Finance › Revue (MAG-539, extends MAG-102): one list of transactions — a state, a period, a
 * search — with the fiche of the line beside it. It replaces « À qualifier », « Reconnues à
 * contrôler » and « Revue mensuelle » (MAG-423, MAG-458, MAG-460, MAG-507).
 *
 * The list is the owner's whole month, and other files keep lines of the seed on purpose (DARTY,
 * the suggestions' FROMAGERIE): a journey therefore never expects an exact count of the list, it
 * finds *its* lines through the search box. Their labels start with a unique word made of letters
 * only, so the rule the « Toujours » switch writes (the leading words up to the first digit)
 * claims its own lines and nothing else — and a retry does not meet the previous attempt's rule.
 * They are filed under a category of their own, never under « Courses »: that envelope is read by
 * the budget and rules journeys.
 */

interface StoredTransaction {
  id?: string
  label?: string
  category?: string | null
  retrospect?: string
}

const letters = (length: number): string =>
  Array.from({ length }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join('')

const createCategory = async (
  api: APIRequestContext,
  name: string,
): Promise<string> => {
  const created = await api.post('/api/categories', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name, obligation: 'optional' },
  })
  expect(created.status(), await created.text()).toBe(201)
  const categoryId = ((await created.json()) as { id: string }).id
  await waitForIndexed(api, '/api/categories', (candidate: { name?: string }) => candidate.name === name, {
    what: `The category ${name}`,
  })

  return categoryId
}

/** One spend of the month on `account`; returns its id. */
const createSpend = async (
  api: APIRequestContext,
  label: string,
  cents: number,
  options: { account?: string; day?: number } = {},
): Promise<string> => {
  const response = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      account: `/api/accounts/${seedId(options.account ?? 'e2e_account_checking')}`,
      label,
      amountCents: cents,
      currency: 'EUR',
      bookedAt: seedDate(options.day ?? 0),
      status: 'spent',
    },
  })
  expect(response.status(), await response.text()).toBe(201)

  return ((await response.json()) as { id: string }).id.split('/').pop() as string
}

/**
 * Given 2 lines to qualify and 1 a rule recognised
 * When I open Revue and filter « À qualifier »
 * Then I see the 2 lines; when I validate the first, it becomes « Reconnue » and stays on the
 *   list, and « Suivant » opens the second.
 */
test('filter À qualifier, validate the first line: it becomes Reconnue, Suivant opens the second', async ({
  page,
  api,
}) => {
  const tag = `REVUE ${letters(8)}`
  const categoryName = `Revue ${letters(8)}`
  const categoryId = await createCategory(api, categoryName)
  const unrecognised = [`${tag} ALPHA`, `${tag} BETA`]
  const ruled = [`${tag} REGLE 1111`, `${tag} REGLE 2222`]

  const ids: string[] = []
  for (const label of [...unrecognised, ...ruled]) ids.push(await createSpend(api, label, -1900))

  // Filing one of the two « REGLE » lines with « always » writes a rule that recognises the other.
  const sorted = await api.post(`/api/finance/transactions/${ids[2]}/sort`, {
    data: { categoryId, alwaysForMerchant: true },
  })
  expect(sorted.status(), await sorted.text()).toBe(200)
  expect(((await sorted.json()) as { alsoSorted: number }).alsoSorted).toBe(1)

  // A rule for the whole tag, written *after*, so it only suggests the category for the two lines
  // still to qualify — « Valider » needs a suggestion.
  const rule = await api.post('/api/categorization_rules', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { labelPattern: tag, matchType: 'contains', category: `/api/categories/${categoryId}`, isActive: true },
  })
  expect(rule.status(), await rule.text()).toBe(201)

  const review = new FinanceReviewPage(page)
  await review.open()
  await review.search.fill(tag)

  // Reconnues: the line the rule filed.
  await review.statePill('Reconnues').click()
  await expect(review.rows).toHaveCount(1)
  await expect(review.row(ruled[1])).toBeVisible()
  await expect(review.rowState(ruled[1])).toHaveText('Reconnue')

  // À qualifier: the two lines nobody filed.
  await review.statePill('À qualifier').click()
  await expect(review.rows).toHaveCount(2)
  await expect(page).toHaveURL(/state=unrecognised/)
  const first = (await review.rows.first().getAttribute('aria-label')) as string
  const second = (await review.rows.nth(1).getAttribute('aria-label')) as string
  expect(unrecognised).toContain(first)
  expect(unrecognised).toContain(second)

  await review.select(first)
  await expect(review.sheet).toContainText(categoryName)
  await review.validate.click()

  // Reconnue now, still on its row and on the fiche; the other line is not touched.
  await expect(review.rowState(first)).toHaveText('Reconnue')
  await review.expectSheet(first)
  await expect(review.rowState(second)).toHaveText('À qualifier')
  await expect(review.rows, 'the line stays until the list is read again').toHaveCount(2)

  await review.step('Suivant')
  await review.expectSheet(second)

  const filed = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === first && Boolean(candidate.category),
    { what: `The transaction ${first}, once validated` },
  )
  expect(filed.category).toBe(`/api/categories/${categoryId}`)

  // A reload keeps the filters (they are the URL), and the validated line is now Reconnue for good.
  await page.reload()
  await expect(review.search).toHaveValue(tag)
  await expect(review.rows).toHaveCount(1)
  await expect(review.row(second)).toBeVisible()
})

test('« Toujours pour ce marchand » files the line and the other one of the same merchant', async ({ page, api }) => {
  const merchant = `TRIAGE ${letters(8)}`
  const labels = [`${merchant} 1234`, `${merchant} 5678`]
  const categoryName = `Triage ${letters(8)}`
  const categoryId = await createCategory(api, categoryName)
  for (const label of labels) await createSpend(api, label, -2350)

  const review = new FinanceReviewPage(page)
  await review.open({ state: 'unrecognised', period: 'all', q: merchant })
  await expect(review.rows).toHaveCount(2)

  await review.select(labels[0])
  await review.always.check()
  await review.chooseCategory(categoryName)

  await expect(
    review.sheet.getByText('Règle créée · 1 autre ligne classée'),
    'the rule filed the second line of the merchant as well',
  ).toBeVisible()
  // The lines stay where they were, now Reconnue: nothing vanishes under the owner's hand.
  await review.expectSheet(labels[0])
  await expect(review.rowState(labels[0])).toHaveText('Reconnue')
  await expect(review.rows).toHaveCount(2)

  for (const label of labels) {
    const filed = await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) => candidate.label === label && Boolean(candidate.category),
      { what: `The transaction ${label}, once filed` },
    )
    expect(filed.category).toBe(`/api/categories/${categoryId}`)
  }
})

test('an unrecognised spend is qualified and judged « Superflue », and its row says both', async ({ page, api }) => {
  const label = `QUALIF ${letters(8)}`
  const categoryName = `Loisirs ${letters(8)}`
  const categoryId = await createCategory(api, categoryName)
  await createSpend(api, label, -3100)

  const review = new FinanceReviewPage(page)
  await review.open({ state: 'unrecognised', period: 'all', q: label })
  await review.select(label)

  await review.judge('Superflue')
  await review.chooseCategory(categoryName)
  await expect(review.rowState(label)).toHaveText('Reconnue')
  await expect(review.row(label).getByText('Superflue')).toBeVisible()

  const filed = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === label && Boolean(candidate.category),
    { what: `The transaction ${label}, once qualified` },
  )
  expect(filed.category).toBe(`/api/categories/${categoryId}`)
  expect(filed.retrospect).toBe('avoidable')
})

test('Précédent and Suivant walk the filtered list, with their position (MAG-507)', async ({ page, api }) => {
  const merchant = `PARCOURS ${letters(8)}`
  const labels = [1, 2, 3].map((n) => `${merchant} ${n}`)

  // Oldest first: three consecutive days far before the seed's, so the order is the one written here.
  const firstDay = -(3000 + Math.floor(Math.random() * 2000))
  for (const [index, label] of labels.entries()) {
    await createSpend(api, label, -1400, { account: 'e2e_account_savings', day: firstDay + index })
  }

  const review = new FinanceReviewPage(page)
  await review.open({ state: 'unrecognised', period: 'all', q: merchant })
  await expect(review.rows).toHaveCount(3)
  expect(await review.rows.evaluateAll((rows) => rows.map((row) => row.getAttribute('aria-label')))).toEqual(labels)

  await review.select(labels[1])
  await expect(review.content.getByText('2 / 3')).toBeVisible()
  await review.step('Suivant')
  await review.expectSheet(labels[2])
  await review.step('Précédent')
  await review.expectSheet(labels[1])
  await page.keyboard.press('ArrowLeft')
  await review.expectSheet(labels[0])
})

test('« Correct » checks a line a rule recognised, and « À conserver » judges the other', async ({ page, api }) => {
  const merchant = `CONTROLE ${letters(8)}`
  const labels = [1, 2, 3].map((n) => `${merchant} ${n}${n}${n}${n}`)
  const categoryName = `Controle ${letters(8)}`
  const categoryId = await createCategory(api, categoryName)

  // On the savings account: the checking account's first page of lines is read by finance-rules' NETFLIX journey.
  const ids: string[] = []
  for (const label of labels) ids.push(await createSpend(api, label, -1800, { account: 'e2e_account_savings' }))

  // The owner files the first line and asks « always »: the rule it creates recognises the two others.
  const sorted = await api.post(`/api/finance/transactions/${ids[0]}/sort`, {
    data: { categoryId, alwaysForMerchant: true },
  })
  expect(sorted.status(), await sorted.text()).toBe(200)
  expect(((await sorted.json()) as { alsoSorted: number }).alsoSorted).toBe(2)

  const review = new FinanceReviewPage(page)
  await review.open({ state: 'recognised', q: merchant })
  await expect(review.rows).toHaveCount(2)
  await expect(review.row(labels[0]), 'the line filed by hand is not the rule\'s').toHaveCount(0)

  await review.select(labels[1])
  await expect(review.sheet.getByTestId('sheet-operation')).toBeVisible()
  await review.correct.click()
  await expect(review.sheet.getByText('Contrôlée')).toBeVisible()
  await expect(review.row(labels[1]).getByTestId('review-controlled')).toBeVisible()

  await review.select(labels[2])
  await review.judge('À conserver')
  const judged = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === labels[2] && candidate.retrospect === 'keep',
    { what: `The transaction ${labels[2]}, once judged` },
  )
  expect(judged.category).toBe(`/api/categories/${categoryId}`)
})

test('the three old addresses open the list filtered as those pages were', async ({ page }) => {
  const review = new FinanceReviewPage(page)

  await review.goto('/finance/to-sort')
  await expect(page).toHaveURL(/#\/finance\/review\?state=unrecognised&period=all$/)

  await review.goto('/finance/to-control')
  await expect(page).toHaveURL(/#\/finance\/review\?state=recognised&period=since_review$/)

  await review.goto('/finance/monthly-review')
  await expect(page).toHaveURL(/#\/finance\/review\?state=all&period=month$/)
})

import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { FinanceToControlPage } from '../pages/FinanceToControlPage.js'
import { FinanceToSortPage } from '../pages/FinanceToSortPage.js'

/**
 * The « À qualifier » pile: two lines of one merchant are filed with one gesture
 * (MAG-423), and an unrecognised spend is qualified then judged (MAG-458).
 *
 * The lines a rule then recognised wait in « Reconnues à contrôler », where one
 * gesture judges them (MAG-460).
 *
 * The pile is the owner's whole list of uncategorised operations, and other
 * files keep some of the seed's on purpose (DARTY, the suggestions' FROMAGERIE):
 * the journey therefore never expects an empty pile, it checks that *its* two
 * lines left it. Their merchant is made of letters only and unique per attempt,
 * so the rule the switch writes (the leading words up to the first digit) claims
 * these two lines and nothing else — and a retry does not meet the previous
 * attempt's rule. It files them under a category of its own, never under
 * « Courses »: that envelope is read by the budget and rules journeys.
 */

interface StoredTransaction {
  id?: string
  label?: string
  category?: string | null
  categorySource?: string
  retrospect?: string
}

const letters = (length: number): string =>
  Array.from({ length }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join('')

test('« Toujours pour ce marchand » files the line and the other one of the same merchant', async ({
  page,
  api,
}) => {
  const merchant = `TRIAGE ${letters(8)}`
  const labels = [`${merchant} 1234`, `${merchant} 5678`]
  const categoryName = `Triage ${letters(8)}`

  const created = await api.post('/api/categories', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name: categoryName, obligation: 'optional' },
  })
  expect(created.status(), await created.text()).toBe(201)
  const categoryId = ((await created.json()) as { id: string }).id
  await waitForIndexed(
    api,
    '/api/categories',
    (candidate: { name?: string }) => candidate.name === categoryName,
    { what: `The category ${categoryName}` },
  )

  for (const label of labels) {
    const response = await api.post('/api/transactions', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: {
        account: `/api/accounts/${seedId('e2e_account_checking')}`,
        label,
        amountCents: -2350,
        currency: 'EUR',
        bookedAt: seedDate(0),
        status: 'spent',
      },
    })
    expect(response.status(), await response.text()).toBe(201)
  }

  const toSort = new FinanceToSortPage(page)
  await toSort.open()
  await expect(toSort.pile.getByRole('button', { name: labels[0] })).toBeVisible()
  await expect(toSort.pile.getByRole('button', { name: labels[1] })).toBeVisible()

  await toSort.select(labels[0])
  await toSort.always.check()
  await toSort.chooseCategory(categoryName)

  await expect(
    toSort.content.getByText('Règle créée · 1 autre ligne classée'),
    'the rule filed the second line of the merchant as well',
  ).toBeVisible()
  for (const label of labels) {
    await expect(toSort.pile.getByRole('button', { name: label })).toHaveCount(0)
  }

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

test('an unrecognised spend is qualified, judged « Superflue » and leaves À qualifier', async ({ page, api }) => {
  const label = `QUALIF ${letters(8)}`
  const categoryName = `Loisirs ${letters(8)}`

  const created = await api.post('/api/categories', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name: categoryName, obligation: 'optional' },
  })
  expect(created.status(), await created.text()).toBe(201)
  const categoryId = ((await created.json()) as { id: string }).id
  await waitForIndexed(
    api,
    '/api/categories',
    (candidate: { name?: string }) => candidate.name === categoryName,
    { what: `The category ${categoryName}` },
  )

  const response = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      account: `/api/accounts/${seedId('e2e_account_checking')}`,
      label,
      amountCents: -3100,
      currency: 'EUR',
      bookedAt: seedDate(0),
      status: 'spent',
    },
  })
  expect(response.status(), await response.text()).toBe(201)

  const toQualify = new FinanceToSortPage(page)
  await toQualify.open()
  await toQualify.select(label)

  await toQualify.judge('Superflue')
  await toQualify.chooseCategory(categoryName)
  await expect(toQualify.pile.getByRole('button', { name: label })).toHaveCount(0)

  const filed = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === label && Boolean(candidate.category),
    { what: `The transaction ${label}, once qualified` },
  )
  expect(filed.category).toBe(`/api/categories/${categoryId}`)
  expect(filed.retrospect).toBe('avoidable')
})

test('« Tout à conserver » judges the lines a rule recognised and the group is checked', async ({ page, api }) => {
  const merchant = `CONTROLE ${letters(8)}`
  const labels = [1, 2, 3, 4].map((n) => `${merchant} ${n}${n}${n}${n}`)
  const categoryName = `Controle ${letters(8)}`

  const created = await api.post('/api/categories', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name: categoryName, obligation: 'optional' },
  })
  expect(created.status(), await created.text()).toBe(201)
  const categoryId = ((await created.json()) as { id: string }).id
  await waitForIndexed(
    api,
    '/api/categories',
    (candidate: { name?: string }) => candidate.name === categoryName,
    { what: `The category ${categoryName}` },
  )

  // On the savings account: the checking account's first page of lines is read by finance-rules' NETFLIX journey.
  const ids: string[] = []
  for (const label of labels) {
    const response = await api.post('/api/transactions', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: {
        account: `/api/accounts/${seedId('e2e_account_savings')}`,
        label,
        amountCents: -1800,
        currency: 'EUR',
        bookedAt: seedDate(0),
        status: 'spent',
      },
    })
    expect(response.status(), await response.text()).toBe(201)
    ids.push(((await response.json()) as { id: string }).id.split('/').pop() as string)
  }

  // The owner files the first line and asks « always »: the rule it creates recognises the three others.
  const sorted = await api.post(`/api/finance/transactions/${ids[0]}/sort`, {
    data: { categoryId, alwaysForMerchant: true },
  })
  expect(sorted.status(), await sorted.text()).toBe(200)
  expect(((await sorted.json()) as { alsoSorted: number }).alsoSorted).toBe(3)

  const toControl = new FinanceToControlPage(page)
  await toControl.open()
  const group = toControl.group(merchant)
  await expect(group.getByRole('heading', { name: /· 3$/ })).toBeVisible()
  for (const label of labels.slice(1)) {
    await expect(group.getByText(label, { exact: true })).toBeVisible()
  }
  await expect(group.getByText(labels[0], { exact: true }), 'the line filed by hand is not the rule\'s').toHaveCount(0)

  await toControl.control(group, 'Tout à conserver')

  await expect(group, 'a checked group does not come back').toHaveCount(0)
  for (const label of labels.slice(1)) {
    const judged = await waitForIndexed<StoredTransaction>(
      api,
      '/api/transactions?itemsPerPage=100',
      (candidate) => candidate.label === label && candidate.retrospect === 'keep',
      { what: `The transaction ${label}, once judged` },
    )
    expect(judged.category).toBe(`/api/categories/${categoryId}`)
  }

  await toControl.open()
  await expect(toControl.group(merchant)).toHaveCount(0)
})

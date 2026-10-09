import { test, expect } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'

/**
 * « Montre-moi mon dernier salaire » (MAG-364, extends MAG-99).
 *
 * Maggie lists the transactions of a category — `manage_transactions` `list`
 * with `categoryId` and `limit: 1` — instead of guessing from every income,
 * rejected direct debits included.
 *
 * What the journey asserts is the tool that ran and the data the filter reads,
 * never her wording: the fake does not read tool results. What the tool returns
 * for a category, its sub-categories, a foreign or unknown id is
 * `TransactionToolsTest`'s business. The category id is in what the owner says
 * because a ULID differs on every run (see agent/fixtures/fake-llm/README.md).
 *
 * Signs in as the fourth account, which owns its categories, accounts and
 * transactions; `retries: 0` because a retry would start on what the first
 * attempt left.
 */

test.describe.configure({ retries: 0 })

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface StoredTransaction {
  label?: string
}

test('« montre-moi mon dernier salaire » lists the category, not every income', async ({ stockUser }) => {
  const { api, page } = stockUser

  const category = await api.post('/api/categories', { headers: LD, data: { name: 'Salaire MAG-364', obligation: 'income' } })
  expect(category.status(), await category.text()).toBe(201)
  const salaryId = ((await category.json()) as { id: string }).id

  const account = await api.post('/api/accounts', {
    headers: LD,
    data: { name: 'Compte salaire MAG-364', type: 'checking', currency: 'EUR', balanceCents: 0 },
  })
  expect(account.status(), await account.text()).toBe(201)
  const accountIri = ((await account.json()) as { '@id': string })['@id']

  // Given two salaries, and a more recent credit that is not one — a rejected
  // direct debit, the line a guess from `direction: income` would have cited.
  const lines: Array<[string, number, string, string | null]> = [
    ['VIR SALAIRE MAG-364 AOUT', 250000, '2026-08-05', salaryId],
    ['VIR SALAIRE MAG-364 SEPTEMBRE', 260000, '2026-09-05', salaryId],
    ['REJET PRLV MAG-364', 3000, '2026-10-02', null],
  ]
  for (const [label, amountCents, bookedAt, categoryId] of lines) {
    const response = await api.post('/api/transactions', {
      headers: LD,
      data: {
        account: accountIri,
        label,
        amountCents,
        currency: 'EUR',
        bookedAt,
        status: 'spent',
        ...(categoryId ? { category: `/api/categories/${categoryId}` } : {}),
      },
    })
    expect(response.status(), await response.text()).toBe(201)
  }

  // When I ask for my last salary.
  const chat = new ChatPanel(page)
  await page.goto('/')
  const events = await chat.send(`Montre-moi mon dernier salaire, catégorie ${salaryId}`)

  // Then Maggie called `manage_transactions` and it went through.
  expect(calledTools(events)).toContain('manage_transactions')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'manage_transactions', status: 'success' })]),
  )

  // And the same filter on the collection leaves the rejected debit out.
  await expect
    .poll(
      async () =>
        (
          await getCollection<StoredTransaction>(
            api,
            `/api/transactions?category=${encodeURIComponent(`/api/categories/${salaryId}`)}&order[bookedAt]=desc`,
          )
        ).map((row) => row.label),
      { message: 'the category filter never narrowed the collection to the two salaries', timeout: 30_000 },
    )
    .toEqual(['VIR SALAIRE MAG-364 SEPTEMBRE', 'VIR SALAIRE MAG-364 AOUT'])
})

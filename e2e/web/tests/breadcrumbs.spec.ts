import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The breadcrumb: where the page sits, and the way back up (MAG-352, on MAG-97).
 *
 * Written as the **neighbour**, like the other finance journeys: the owner's
 * balances are asserted elsewhere and an account created here would move them.
 * Deleting the account takes its transaction with it.
 *
 * Above `md` the trail shows every level and the journey climbs it by name.
 * Below, only the parent is shown ("‹ Transactions"), so the same two clicks
 * go through the parent of each page instead.
 */

const NARROW_BELOW = 900

const createdAccountIds: string[] = []

test.afterEach(async ({ otherUser }) => {
  for (const id of createdAccountIds.splice(0)) {
    await otherUser.api.delete(`/api/accounts/${id}`)
  }
})

async function createAccountWithTransaction(api: APIRequestContext, label: string): Promise<string> {
  const account = await api.post('/api/accounts', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: { name: 'Courant MAG-352', type: 'checking', currency: 'EUR', balanceCents: 0 },
  })
  expect(account.status(), await account.text()).toBe(201)
  const accountId = ((await account.json()) as { id: string }).id
  createdAccountIds.push(accountId)

  const transaction = await api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      account: `/api/accounts/${accountId}`,
      label,
      amountCents: -4_200,
      currency: 'EUR',
      bookedAt: seedAnchorDate(),
      status: 'spent',
    },
  })
  expect(transaction.status(), await transaction.text()).toBe(201)

  return ((await transaction.json()) as { id: string }).id
}

test('from a transaction, the trail climbs back to the list, then to the module dashboard @responsive', async ({
  otherUser,
}) => {
  const { api, page } = otherUser
  const label = `Prélèvement EDF MAG-352, essai ${test.info().retry}`
  const transactionId = await createAccountWithTransaction(api, label)
  await waitForIndexed<{ label?: string }>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === label,
    { what: `The transaction "${label}"` },
  )

  const shell = new AdminShell(page)
  const trail = page.getByRole('navigation', { name: "Fil d'Ariane" })
  const narrow = (page.viewportSize()?.width ?? NARROW_BELOW) < NARROW_BELOW

  await shell.goto(`/transactions/${encodeURIComponent(`/api/transactions/${transactionId}`)}`)

  // The record's own name ends the trail, the action sits after it.
  if (narrow) {
    await expect(trail.getByRole('link')).toHaveCount(1)
    await expect(trail.getByRole('link', { name: label })).toBeVisible()
    await trail.getByRole('link', { name: label }).click()
    await expect(trail.getByText('Modifier')).toHaveCount(0)
    await expect(trail.getByRole('link', { name: 'Transactions' })).toBeVisible()
  } else {
    await expect(trail.getByText(label)).toBeVisible()
    await expect(trail.getByText('Modifier')).toHaveAttribute('aria-current', 'page')
    await expect(trail.getByRole('link', { name: 'Finance' })).toBeVisible()
  }

  await trail.getByRole('link', { name: /Transactions/ }).click()
  await expect(page).toHaveURL(/#\/transactions$/)
  await expect(trail.getByText(narrow ? 'Finance' : 'Transactions')).toBeVisible()

  await trail.getByRole('link', { name: /Finance/ }).click()
  await expect(page).toHaveURL(/#\/finance\/dashboard$/)
  await expect(trail.getByText('Finance')).toHaveAttribute('aria-current', 'page')
})

test('a settings page and the dashboard carry their trail @responsive', async ({ page }) => {
  const shell = new AdminShell(page)
  const trail = page.getByRole('navigation', { name: "Fil d'Ariane" })

  await shell.goto(ROUTES.dashboard)
  await expect(trail.getByText('Accueil')).toHaveAttribute('aria-current', 'page')

  await shell.goto(ROUTES.preferences)
  await expect(trail).toContainText('Préférences')
})

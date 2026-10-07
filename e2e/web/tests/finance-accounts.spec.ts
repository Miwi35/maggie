import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { euros, signedEuros } from '../helpers/money.js'
import { FinanceAccountsPage } from '../pages/FinanceAccountsPage.js'
import { FinanceBanksPage } from '../pages/FinanceBanksPage.js'
import { FinanceBudgetPage } from '../pages/FinanceBudgetPage.js'
import { FinanceCategoriesPage } from '../pages/FinanceCategoriesPage.js'

/**
 * Accounts, and the transactions that hang off them (MAG-102).
 *
 * The first two tests only read the owner's seeded world. Everything that
 * writes does so as the **neighbour**, for the same reason MAG-149's
 * `agenda-default.spec.ts` does: an account's balance is summed into the
 * owner's dashboard total and into their safety net, so an account created
 * here would move the figure `finance-overview.spec.ts` and
 * `finance-cushion.spec.ts` assert, on whichever shard happens to run both.
 *
 * It buys two things the owner's account could not: an identity whose finance
 * module is empty, which is where the one past regression MAG-93 found here
 * (`e05e52f`, period controls and empty states) would show up; and a journey
 * that finds the owner's rows in the neighbour's list failing here rather than
 * in production.
 */

/**
 * Only the fields the journey reads, and spelled the way the API spells them.
 *
 * `cushion`, not `isCushion`: Symfony serialises `isCushion()` without its
 * prefix, and a client declaring the getter's name silently reads its own
 * default instead — the trap `DtoContractTest` guards on the mobile side.
 */
interface StoredAccount {
  /** The bare ULID. `@id` is the IRI; a relation elsewhere points at that. */
  id?: string
  name?: string
  bank?: string
  type?: string
  balanceCents?: number
  cushion?: boolean
}

interface StoredTransaction {
  id?: string
  label?: string
  amountCents?: number
  bookedAt?: string
  account?: string
}

test('the seeded accounts show the balance and the type they were given', async ({ page }) => {
  const accounts = new FinanceAccountsPage(page)
  await accounts.open()

  const checking = accounts.row('Compte courant')
  await expect(checking).toContainText('Crédit Mutuel')
  await expect(checking).toContainText(euros(184550))

  const savings = accounts.row('Livret A')
  await expect(savings).toContainText('Épargne')
  await expect(savings).toContainText(euros(420000))
})

/**
 * The account-scoped list really is scoped.
 *
 * `AccountTransactionsView` filters on `account` with the IRI, and its own
 * comment records why: it used to filter on `accountId`, which narrowed
 * nothing once the collection fell back to Doctrine — a filter that is
 * declared nowhere still *looked* like it worked while Elasticsearch turned
 * any unrecognised string into a term query. So the assertion is not only
 * "the checking account's transactions are here" but "the savings account's
 * are not".
 */
test("an account's transactions are its own, and not another account's", async ({ page }) => {
  const accounts = new FinanceAccountsPage(page)
  await accounts.openTransactions(seedId('e2e_account_checking'))

  await expect(accounts.row('LECLERC RENNES').first()).toBeVisible()
  await expect(accounts.row('VIREMENT SALAIRE').first()).toContainText(signedEuros(235000))
  // Booked on the savings account, so it has no business being on this list.
  await expect(accounts.row('VIREMENT EPARGNE')).toHaveCount(0)
})

/**
 * What the module looks like with nothing in it.
 *
 * The one past regression MAG-93 found here (`e05e52f`) was about period
 * controls and empty states, and an empty finance screen is the easiest one to
 * get wrong: every figure on it is computed, so "no data" is a division by
 * zero away from a blank card or a crash. A user with no finance data must get
 * an invitation on every screen, and none of the owner's rows.
 *
 * Read-only, and deliberately on the three screens no journey writes to for
 * this identity — the account list is written by the two tests below.
 */
test('a finance module with nothing in it invites rather than breaks', async ({ otherUser }) => {
  const budget = new FinanceBudgetPage(otherUser.page)
  await budget.open()
  await expect(budget.content.getByText("Aucune enveloppe pour l'instant")).toBeVisible()
  await expect(
    budget.content.getByTestId('budget-gauge'),
    'no envelope, no gauge — and no gauge of somebody else either',
  ).toHaveCount(0)
  await expect(
    budget.scoreBanner,
    'the day score has nothing to compare against, and says so',
  ).toContainText('Aucune enveloppe sur cette période')

  const categories = new FinanceCategoriesPage(otherUser.page)
  await categories.open()
  await categories.openTab('Règles de catégorisation')
  await expect(categories.content.getByText("Aucune règle pour l'instant")).toBeVisible()

  const banks = new FinanceBanksPage(otherUser.page)
  await banks.open()
  await expect(banks.content.getByText('Aucune banque connectée')).toBeVisible()
  await expect(banks.content.getByText('Mock Bank')).toHaveCount(0)
})

test('a new account and its first transaction are created from the screens that own them', async ({
  otherUser,
}) => {
  const accounts = new FinanceAccountsPage(otherUser.page)
  // Suffixed with the attempt, unconditionally: CI retries once and nothing
  // reseeds in between, so a second attempt would find the first one's row and
  // read it as a duplicate.
  const accountName = `Compte joint MAG-102, essai ${test.info().retry}`
  const label = `ACHAT TEST MAG-102, essai ${test.info().retry}`

  await accounts.open()

  // Nothing of the owner's leaks in. Asserted on names the owner alone uses —
  // "Compte courant" would not do, it is also the label of the `checking`
  // type, which any account of the neighbour's own may carry.
  //
  // The list is **not** asserted empty: the empty-account test below
  // creates an account of its own on the same identity, and the two run side
  // by side. What a finance module with no data looks like is asserted in the
  // test above, on the screens no journey writes to.
  await expect(accounts.content.getByText('Livret A')).toHaveCount(0)
  await expect(accounts.content.getByText('Crédit Mutuel')).toHaveCount(0)

  await accounts.createAccountNamed(accountName, {
    bank: 'Banque de test',
    type: 'Espèces',
    balanceEuros: 1234.5,
  })

  const stored = await waitForIndexed<StoredAccount>(
    otherUser.api,
    '/api/accounts',
    (account) => account.name === accountName,
    { what: 'The account created from the form' },
  )

  // The form takes euros and the API stores cents: the one conversion every
  // amount on every finance screen depends on.
  expect(stored.balanceCents).toBe(123450)
  expect(stored.type).toBe('cash')
  expect(stored.bank).toBe('Banque de test')

  await accounts.expectAccountEventually(accountName)
  await expect(accounts.row(accountName)).toContainText(euros(123450))

  // Through the account, which is the only way in: the transaction resource is
  // deliberately absent from the menu.
  const accountId = stored.id ?? ''
  await accounts.openTransactions(accountId)
  await expect(
    accounts.content.getByText('Aucune transaction sur ce compte'),
    'a brand new account has no movement, and says so',
  ).toBeVisible()

  // The empty state carries its own way in, with the account already chosen
  // (MAG-245): the first transaction is typed in by hand, not slipped in by API.
  await accounts.createTransaction({
    label,
    amountEuros: 42.5,
    date: seedDate(),
  })

  const transaction = await waitForIndexed<StoredTransaction>(
    otherUser.api,
    '/api/transactions',
    (candidate) => candidate.label === label,
    { what: 'The transaction created from the empty state' },
  )

  expect(transaction.amountCents).toBe(-4250)
  expect(transaction.bookedAt?.slice(0, 10)).toBe(seedDate())
  // A relation comes back as the IRI, not as the bare id the row above carries.
  expect(transaction.account).toBe(`/api/accounts/${accountId}`)

  await accounts.openTransactions(accountId)
  await expect(accounts.row(label)).toContainText(euros(-4250))
  // "Dépensée" is the form's default status, and it is what makes the
  // transaction weigh on its category's envelope.
  await expect(accounts.row(label)).toContainText('Dépensée')

  // The neighbour's own list, and nothing of the owner's: the writes above went
  // through their identity, so this is also the user filter's check.
  //
  // Phrased as "this attempt's label, and none of the owner's" rather
  // than as an equality over the whole collection: CI retries once without
  // reseeding, and `otherUser` is one stable seeded identity — a replay sees
  // the first attempt's row as well, and an equality could never hold.
  const theirs = (await getCollection<StoredTransaction>(otherUser.api, '/api/transactions')).map(
    (candidate) => candidate.label,
  )
  expect(theirs).toContain(label)
  expect(theirs.filter((seen) => seen === 'LECLERC RENNES' || seen === 'VIREMENT SALAIRE')).toEqual(
    [],
  )
})

/**
 * An account with no transaction is not a dead end (MAG-245).
 *
 * React-admin renders a list's `empty` *instead of* the list — the toolbar
 * with it — so `AccountTransactionsView` gives the placeholder its own button.
 * Builds its own account rather than reusing the journey's above, so the test
 * does not depend on another one's state.
 */
test('an account with no transaction offers a way to add one', async ({ otherUser }) => {
  const created = await otherUser.api.post('/api/accounts', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      name: `Compte vide MAG-245, essai ${test.info().retry}`,
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    },
  })
  expect(created.status(), await created.text()).toBe(201)
  const account = (await created.json()) as StoredAccount

  const accounts = new FinanceAccountsPage(otherUser.page)
  await accounts.openTransactions(account.id ?? '')

  await expect(
    accounts.content.getByText('Aucune transaction sur ce compte'),
    'the screen that asks for a transaction',
  ).toBeVisible()
  await expect(
    accounts.addTransaction,
    'the empty state asks for a transaction and carries the button that adds one',
  ).toBeVisible()
})

/**
 * A recette is chosen first, and the form follows (MAG-301).
 *
 * Categories of its own, created through the API as the neighbour: the seeded
 * "Salaire" belongs to the owner, and the neighbour's finance module starts
 * empty. Suffixed with the attempt like the account of the first journey.
 */
test('choosing Recette offers income categories only, and files a positive transaction', async ({
  otherUser,
}) => {
  const attempt = test.info().retry
  const salary = `Salaire MAG-301, essai ${attempt}`
  const subscription = `Netflix MAG-301, essai ${attempt}`
  const label = `PAIE MAG-301, essai ${attempt}`

  for (const [name, obligation] of [
    [salary, 'income'],
    [subscription, 'optional'],
  ]) {
    const category = await otherUser.api.post('/api/categories', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: { name, obligation },
    })
    expect(category.status(), await category.text()).toBe(201)
  }

  const account = await otherUser.api.post('/api/accounts', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      name: `Compte recette MAG-301, essai ${attempt}`,
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    },
  })
  expect(account.status(), await account.text()).toBe(201)
  const accountId = String(((await account.json()) as StoredAccount).id ?? '')

  const accounts = new FinanceAccountsPage(otherUser.page)
  await accounts.openTransactions(accountId)
  await accounts.addTransaction.click()
  await accounts.chooseNature('Recette')

  const offered = await accounts.offeredCategories()
  expect(offered, 'an income category is offered on a recette').toContain(salary)
  expect(offered, 'an optional category is not').not.toContain(subscription)
  await otherUser.page.keyboard.press('Escape')

  // The form is already open, so it is filled here rather than through
  // `createTransaction`, which opens it from the list.
  await accounts.content.getByLabel('Libellé').fill(label)
  await accounts.content.getByLabel('Montant (€)').fill('1500')
  await accounts.content.getByLabel('Date').fill(seedDate())
  await accounts.content.getByLabel('Catégorie').fill(salary)
  await otherUser.page.getByRole('option', { name: salary, exact: true }).click()
  await expect(
    accounts.content.getByLabel('Statut'),
    'a recette is received by default',
  ).toHaveText('Reçue')
  await accounts.save()

  const stored = await waitForIndexed<StoredTransaction>(
    otherUser.api,
    '/api/transactions',
    (candidate) => candidate.label === label,
    { what: 'The recette created from the form' },
  )
  expect(stored.amountCents, 'typed without a sign, sent as an income').toBe(150000)

  await accounts.openTransactions(accountId)
  await expect(accounts.row(label)).toContainText(signedEuros(150000))
  await expect(accounts.row(label)).toContainText('Reçue')
  await expect(accounts.row(label)).not.toContainText('Dépensée')
})

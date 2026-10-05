import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { euros, signedEuros } from '../helpers/money.js'
import { FinanceAccountsPage } from '../pages/FinanceAccountsPage.js'
import { ROUTES, adminUrl } from '../pages/routes.js'

/**
 * Accounts, and the operations that hang off them (MAG-102).
 *
 * The first two tests only read the owner's seeded world. The third one
 * writes, and it writes as the **neighbour** — for the same reason MAG-149's
 * `agenda-default.spec.ts` does: an account's balance is summed into the
 * owner's dashboard total and into their safety net, so an account created
 * here would move the figure `finance-overview.spec.ts` and
 * `finance-cushion.spec.ts` assert, on whichever shard happens to run both.
 *
 * Writing as the neighbour buys two things the owner's account could not: the
 * finance screens are asserted **empty** first — the one past regression
 * MAG-93 found in this module (`e05e52f`) was about period controls and empty
 * states — and a journey that finds the owner's rows in the neighbour's list
 * fails here rather than in production.
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
test("an account's operations are its own, and not another account's", async ({ page }) => {
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
  const page = otherUser.page
  const content = page.getByTestId('page-content')

  await page.goto(adminUrl(ROUTES.envelopes))
  await expect(content.getByText("Aucune enveloppe pour l'instant")).toBeVisible()
  await expect(
    content.getByTestId('budget-gauge'),
    'no envelope, no gauge — and no gauge of somebody else either',
  ).toHaveCount(0)
  await expect(
    content.getByRole('alert'),
    'the day score has nothing to compare against, and says so',
  ).toContainText('Aucune enveloppe sur cette période')

  await page.goto(adminUrl(ROUTES.categories))
  await content.getByRole('tab', { name: 'Règles de catégorisation' }).click()
  await expect(content.getByText("Aucune règle pour l'instant")).toBeVisible()

  await page.goto(adminUrl(ROUTES.financeBanks))
  await expect(content.getByText('Aucune banque connectée')).toBeVisible()
  await expect(content.getByText('Mock Bank')).toHaveCount(0)
})

test('a new account and its first operation are created from the screens that own them', async ({
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
  // The list is **not** asserted empty: the expected-to-fail test below
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
    accounts.content.getByText('Aucune opération sur ce compte'),
    'a brand new account has no movement, and says so',
  ).toBeVisible()

  // The first movement is written through the API, and that is not a shortcut
  // taken for speed: while the list is empty the screen offers no way in at
  // all (MAG-245, reproduced below). The placeholder's own wording expects
  // this one — "attendez la prochaine synchronisation".
  const arrived = await otherUser.api.post('/api/transactions', {
    headers: { 'Content-Type': 'application/ld+json' },
    data: {
      account: `/api/accounts/${accountId}`,
      label: 'VIREMENT RECU',
      amountCents: 50000,
      currency: 'EUR',
      bookedAt: seedDate(-1),
      status: 'spent',
    },
  })
  expect(arrived.status(), await arrived.text()).toBe(201)

  await waitForIndexed<StoredTransaction>(
    otherUser.api,
    '/api/transactions',
    (candidate) => candidate.label === 'VIREMENT RECU',
    { what: 'The movement the account was opened with' },
  )

  // Now that the grid is there, so is its toolbar — and the owner's own way
  // of adding a movement by hand.
  await accounts.openTransactions(accountId)
  await accounts.createTransaction({
    label,
    amountEuros: -42.5,
    date: seedDate(),
  })

  const transaction = await waitForIndexed<StoredTransaction>(
    otherUser.api,
    '/api/transactions',
    (candidate) => candidate.label === label,
    { what: 'The operation created from the form' },
  )

  expect(transaction.amountCents).toBe(-4250)
  expect(transaction.bookedAt?.slice(0, 10)).toBe(seedDate())
  // A relation comes back as the IRI, not as the bare id the row above carries.
  expect(transaction.account).toBe(`/api/accounts/${accountId}`)

  await accounts.openTransactions(accountId)
  await expect(accounts.row(label)).toContainText(euros(-4250))
  // "Dépensée" is the form's default status, and it is what makes the
  // operation weigh on its category's envelope.
  await expect(accounts.row(label)).toContainText('Dépensée')

  // The neighbour's own list, and nothing more: the writes above went through
  // their identity, so this is also the user filter's check.
  const theirs = await getCollection<StoredTransaction>(otherUser.api, '/api/transactions')
  expect(theirs.map((candidate) => candidate.label).sort()).toEqual(
    [label, 'VIREMENT RECU'].sort(),
  )
})

/**
 * An account with no operation is a dead end (MAG-245).
 *
 * React-admin renders a list's `empty` *instead of* the list — the toolbar
 * with it — so `AccountTransactionsView` loses the "Ajouter une opération"
 * button on exactly the screen whose text asks for one. The only way out is
 * to know the `#/transactions/create` URL, which nothing on screen leads to.
 *
 * Expected to fail, naming its ticket rather than quietly asserting the
 * behaviour nobody wants: this is the fix's reproduction, already written. It
 * builds its own account rather than reusing the one above — a marked test
 * absorbs everything that goes wrong in it, including another test's state
 * not being there.
 *
 * Paired with the journey above, which drives the same screen unmarked: a view
 * that failed to load at all cannot hide behind this marker.
 */
test.fail('an account with no operation offers a way to add one — MAG-245', async ({
  otherUser,
}) => {
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
    accounts.content.getByText('Aucune opération sur ce compte'),
    'the screen that asks for an operation',
  ).toBeVisible()
  await expect(
    accounts.addTransaction,
    'asks for one and takes the only button that adds one away',
  ).toBeVisible()
})

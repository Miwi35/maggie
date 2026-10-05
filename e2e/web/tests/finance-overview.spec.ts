import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { euros } from '../helpers/money.js'
import { FinanceOverviewPage } from '../pages/FinanceOverviewPage.js'

/**
 * The finance dashboard (MAG-102).
 *
 * Every figure on this screen is computed on the fly by
 * `GetFinanceDashboard`, which composes the other use cases rather than
 * recomputing anything — so what is checked here is arithmetic over the
 * seeded data, and nothing else:
 *
 *   balance       184550 + 420000 = 604550, of which 420000 is the safety net
 *   capacity      235000 income − 21000 of loan payments − 16666 of measured
 *                 lifestyle (the three months before this one, 50000 / 3)
 *   top posts     this month's spending by category, against last month's
 *
 * The journey reads **Loisirs** among the top posts and leaves "Courses"
 * alone: `finance-rules.spec.ts` moves that one by filing an uncategorised
 * operation into it, and the two files can share a shard. Loisirs is spent by
 * hand in the seed and no active rule can reach it.
 *
 * It only reads. The owner's balances are summed here *and* in
 * `finance-cushion.spec.ts`, so an account written from this file would break
 * both — which is why `finance-accounts.spec.ts` creates its account as the
 * neighbour.
 */

const CHECKING_CENTS = 184550
const SAVINGS_CENTS = 420000
const INCOME_CENTS = 235000
const LOAN_PAYMENT_CENTS = 21000
/** 380,00 € + 120,00 € spent last month, averaged over the three-month sample. */
const LIFESTYLE_CENTS = 16666

const [ANCHOR_YEAR, ANCHOR_MONTH] = seedAnchorDate().split('-').map(Number)

test('the balance card adds the accounts up and sets the safety net aside', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const balance = overview.card('Solde de tous les comptes')
  await expect(balance).toContainText(euros(CHECKING_CENTS + SAVINGS_CENTS))
  await expect(
    balance,
    'the cushion is part of the total and not part of what is available',
  ).toContainText(`dont ${euros(SAVINGS_CENTS)} de matelas`)
  await expect(balance).toContainText(`${euros(CHECKING_CENTS)} disponibles`)

  // Each account on its own line, and the net one marked as such.
  await expect(balance).toContainText('Compte courant')
  await expect(balance).toContainText('Livret A · matelas')
})

test('the saving capacity is income minus the loans minus a measured lifestyle', async ({
  page,
}) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const capacity = overview.card("Capacité d'épargne nette")
  await expect(capacity).toContainText(
    euros(INCOME_CENTS - LOAN_PAYMENT_CENTS - LIFESTYLE_CENTS),
  )
  // The three terms, so a wrong total can be told from a wrong term.
  await expect(capacity).toContainText(`${euros(INCOME_CENTS)} de revenu`)
  await expect(capacity).toContainText(`${euros(LOAN_PAYMENT_CENTS)} de mensualités`)
  await expect(capacity).toContainText(`${euros(LIFESTYLE_CENTS)} de train de vie`)
})

/**
 * A post is this month against the same post last month.
 *
 * "Loisirs" is 24,00 € this month — the cinema — against 120,00 € last month,
 * the FNAC: a change of −96,00 €, which is the figure a reader actually uses.
 * A post shown without its change says nothing about whether the month is
 * going well.
 */
test('a spending post is shown against the same post last month', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const leisure = overview.topPost('Loisirs')
  await expect(leisure).toContainText(euros(2400))
  await expect(leisure).toContainText(euros(-9600))
})

/**
 * Moving the period controls is the one past regression MAG-93 found here
 * (`e05e52f`): the picker and the empty states. A month the seed never touched
 * must come back empty and say so, rather than keeping the month before it on
 * screen.
 */
test('a month with nothing in it is shown as empty, not as the month before', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  await expect(overview.card('Principaux postes du mois')).toContainText('Loisirs')

  // Two years back: outside every window the dashboard reads, and outside what
  // any other journey writes into.
  await overview.selectPeriod(ANCHOR_YEAR - 2, ANCHOR_MONTH)

  await expect(overview.card('Principaux postes du mois')).toContainText(
    'Aucune dépense sur ce mois.',
  )
  await expect(overview.card('Enveloppes')).toContainText('Aucune enveloppe sur ce mois.')
  // The balance is not a period figure: it is what the accounts hold today,
  // and it must not empty itself along with the month.
  await expect(overview.card('Solde de tous les comptes')).toContainText(
    euros(CHECKING_CENTS + SAVINGS_CENTS),
  )
})

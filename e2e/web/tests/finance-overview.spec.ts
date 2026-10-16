import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { euros } from '../helpers/money.js'
import { FinanceOverviewPage } from '../pages/FinanceOverviewPage.js'

/**
 * The finance home (MAG-102, rebuilt by MAG-420).
 *
 * Top to bottom: the two rings, the month's curve against its rhythm, the
 * « Prochain projet » example card, then three tiles — Comptes, Budget,
 * Planification — each a card that opens its menu.
 *
 * Every figure on this screen is computed on the fly by
 * `GetFinanceDashboard`, which composes the other use cases rather than
 * recomputing anything — so what is checked here is arithmetic over the
 * seeded data, and nothing else:
 *
 *   Comptes       184550 + 420000 = 604550, of which 420000 is the safety net
 *   Budget        the declared salary of the cushion (235000), no pay day set
 *                 in the treasury settings, so no balance expected before it
 *   Planification 235000 income − 21000 of loan payments − 16666 of measured
 *                 lifestyle (the three months before this one, 50000 / 3)
 *
 * The Budget tile reads the **Courses** envelope's budget and leaves its
 * consumption alone: `finance-rules.spec.ts` moves that one by filing an
 * uncategorised operation into it, and the two files can share a shard.
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
/** `50-finance.yaml`: the monthly Courses envelope. */
const GROCERIES_BUDGET_CENTS = 20000

const [ANCHOR_YEAR, ANCHOR_MONTH] = seedAnchorDate().split('-').map(Number)

/**
 * Given two accounts, one of them a matelas, a reference salary and envelopes,
 * When I open the finance home, Then I see the two rings, the curve, and the
 * Comptes, Budget and Planification tiles.
 */
test('the home shows the rings, the curve, the next project and the three tiles', async ({
  page,
}) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  await expect(overview.scoreRing('week')).toBeVisible()
  await expect(overview.scoreRing('month')).toBeVisible()
  await expect(overview.rhythmCurve).toBeVisible()
  await expect(overview.nextProject).toBeVisible()
  await expect(overview.budgetTile).toBeVisible()
  await expect(overview.planningTile).toBeVisible()

  // The blocks the tiles replaced are gone.
  await expect(overview.content.getByText('Solde de tous les comptes')).toHaveCount(0)
  await expect(overview.content.getByText('Principaux postes du mois')).toHaveCount(0)
  await expect(overview.content.getByText('Recettes et dépenses sur douze mois')).toHaveCount(0)
})

test('the Comptes tile adds the accounts up and sets the safety net aside', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const accounts = overview.accountsTile
  await expect(accounts).toContainText(euros(CHECKING_CENTS + SAVINGS_CENTS))
  await expect(
    accounts,
    'the cushion is part of the total and not part of what is available',
  ).toContainText(`dont ${euros(SAVINGS_CENTS)} de matelas`)
  await expect(accounts).toContainText(`${euros(CHECKING_CENTS)} disponibles`)

  // Each account on its own line, and the net one marked as such.
  await expect(accounts).toContainText('Compte courant')
  await expect(accounts).toContainText('Livret A · matelas')
})

/**
 * The seeded owner declares a net salary on the cushion and has no « Salaire »
 * series, so the reference is the declared one; the treasury settings carry no
 * pay day, so neither the pay day nor the balance before it can be known — and
 * the tile says so rather than showing 0 €.
 */
test('the Budget tile shows the expected salary and says the balance before it is unknown', async ({
  page,
}) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const salary = overview.expectedSalary()
  await expect(salary).toContainText('Salaire attendu')
  await expect(salary).toContainText(euros(INCOME_CENTS))
  await expect(salary).toContainText('déclaré sur le matelas')

  const balance = overview.balanceBeforeSalary()
  await expect(balance).toContainText('Solde attendu avant le salaire')
  await expect(balance).toContainText('solde attendu inconnu')

  // The monthly envelope, with its budget (its consumption belongs to finance-rules).
  await expect(overview.envelopeBar('Courses')).toContainText(`/ ${euros(GROCERIES_BUDGET_CENTS)}`)
})

test('the Planification tile gives the saving capacity as income minus the loans minus a measured lifestyle', async ({
  page,
}) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const capacity = overview.savingCapacity()
  await expect(capacity).toContainText("Capacité d'épargne nette")
  await expect(capacity).toContainText(euros(INCOME_CENTS - LOAN_PAYMENT_CENTS - LIFESTYLE_CENTS))
  // The three terms, so a wrong total can be told from a wrong term.
  await expect(capacity).toContainText(`${euros(INCOME_CENTS)} de revenu`)
  await expect(capacity).toContainText(`${euros(LOAN_PAYMENT_CENTS)} de mensualités`)
  await expect(capacity).toContainText(`${euros(LIFESTYLE_CENTS)} de train de vie`)

  await expect(overview.independence()).toBeVisible()
})

/**
 * The card keeps its place in the layout with an example (owner decision of
 * 13 Oct); MAG-425 replaces it with the real projects. It is not a link.
 */
test('the Prochain projet card shows its static example, and is not clickable', async ({
  page,
}) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const card = overview.nextProject
  await expect(card).toContainText('Vélo électrique')
  await expect(card).toContainText(`${euros(74000)} sur ${euros(120000)}`)
  await expect(card).toContainText('dans 4 mois')
  await expect(card.getByTestId('next-project-negative')).toContainText(euros(-46000))
  await expect(card.getByRole('link')).toHaveCount(0)
})

test('each tile opens its menu', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)

  await overview.open()
  // A click anywhere on the card, not only on its title.
  await overview.accountsTile.click()
  await expect(page).toHaveURL(/#\/accounts$/)

  await overview.open()
  await overview.tileLink(overview.budgetTile, 'Budget').click()
  await expect(page).toHaveURL(/#\/envelopes$/)

  await overview.open()
  await overview.tileLink(overview.planningTile, 'Planification').click()
  await expect(page).toHaveURL(/#\/finance\/treasury$/)
})

/**
 * A spending post's `categoryId` is the one `/api/categories/{id}` answers to
 * (MAG-261): the dashboard used to hand back the column's raw RFC 4122
 * spelling, which no other entry point accepts. The root field stays in the
 * payload, so this is still read at the API.
 */
test('a spending post links to its category resource', async ({ api }) => {
  const response = await api.get(
    `/api/finance/dashboard?year=${ANCHOR_YEAR}&month=${ANCHOR_MONTH}`,
  )
  expect(response.ok()).toBe(true)

  const { topPosts } = (await response.json()) as {
    topPosts: Array<{ categoryId: string | null; categoryName: string | null }>
  }
  const post = topPosts.find((p) => p.categoryId !== null)
  expect(post, 'the seed spends in at least one category this month').toBeDefined()

  const category = await api.get(`/api/categories/${post!.categoryId}`, {
    headers: { Accept: 'application/ld+json' },
  })
  expect(category.status()).toBe(200)
  expect(((await category.json()) as { name: string }).name).toBe(post!.categoryName)
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

  await expect(overview.envelopeBar('Courses')).toBeVisible()

  // Two years back: outside every window the dashboard reads, and outside what
  // any other journey writes into.
  await overview.selectPeriod(ANCHOR_YEAR - 2, ANCHOR_MONTH)

  await expect(overview.budgetTile).toContainText('Aucune enveloppe sur ce mois.')
  await expect(overview.envelopeBar('Courses')).toHaveCount(0)
  // The balance is not a period figure: it is what the accounts hold today,
  // and it must not empty itself along with the month.
  await expect(overview.accountsTile).toContainText(euros(CHECKING_CENTS + SAVINGS_CENTS))
})

/**
 * The rings and the rhythm curve (MAG-417, extends MAG-102 — they replace the
 * daily score).
 *
 * An account of its own, `e2e-scores@maggie.local`: a rent of 700 € debited on
 * the 5th and 300 € of groceries, against 700 € + 720 € of budget — 1 000 €
 * spent, 420 € left. CI pins the day to the 13th, where the pace says rent
 * and a share of the groceries are due: the remainder is exact whatever the
 * day, the status is read as a word and never as a colour alone, and the rent
 * is marked on the curve on the 5th (see 57-finance-scores.yaml).
 */
test('the rings say what is left and in which state, and the curve marks the rent on the 5th', async ({
  scoresUser,
}) => {
  const overview = new FinanceOverviewPage(scoresUser.page)
  await overview.open()

  const month = overview.scoreRing('month')
  await expect(month).toBeVisible()
  await expect(month.getByTestId('ring-remaining')).toContainText(euros(42000))
  await expect(month.getByTestId('ring-expected')).toContainText('Devrait rester')
  await expect(month.getByTestId('rhythm-tick')).toBeVisible()
  // Nothing is overspent: red is for a budget gone, and this one is not.
  expect(['green', 'neutral', 'orange']).toContain(await month.getAttribute('data-status'))
  await expect(month).toContainText(/Dans le vert|Dans les clous|Attention/)

  // A touch gives the reasons, and another one hides them.
  const reasons = await overview.scoreReasons('month')
  await expect(reasons).toBeVisible()
  await expect(reasons.getByRole('listitem').first()).toBeVisible()
  await month.getByRole('button').click()
  await expect(reasons).toBeHidden()

  await expect(overview.scoreRing('week')).toBeVisible()

  const [year, monthNumber] = seedAnchorDate().split('-')
  const rent = overview.debitMarker('Loyer')
  await expect(rent).toHaveCount(1)
  await expect(rent).toHaveAttribute('data-date', `${year}-${monthNumber}-05`)
  await expect(overview.rhythmCurve.getByTestId('spent-line')).toBeVisible()
})

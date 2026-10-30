import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate, seedDate } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { euros } from '../helpers/money.js'
import { FinanceBanksPage } from '../pages/FinanceBanksPage.js'
import { FinanceLoansPage } from '../pages/FinanceLoansPage.js'
import { FinanceOverviewPage } from '../pages/FinanceOverviewPage.js'
import { FinanceProjectsPage } from '../pages/FinanceProjectsPage.js'
import { FinanceReviewPage } from '../pages/FinanceReviewPage.js'
import { FinanceTransfersPage } from '../pages/FinanceTransfersPage.js'

/**
 * The finance home (MAG-102, rebuilt by MAG-420, drawn to the mockup by MAG-631).
 *
 * Top to bottom: the dated header, the two rings beside the « Prochain
 * projet » card, the month's spending against its rhythm, then three tiles —
 * Comptes, Budget, Planification — each a card that opens its menu with
 * « Ouvrir → ». No month or year picker: the home is the current month.
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
 * It only reads as the owner (the « what is to do » pills are the exception,
 * and run as an account of their own, see the last describe). The owner's balances are summed here *and* in
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

/** « oct. 2026 »: the month `offset` months after the anchor's, as the card spells it. */
const monthLabel = (offset: number): string =>
  new Intl.DateTimeFormat('fr-FR', { month: 'short', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(Date.UTC(ANCHOR_YEAR, ANCHOR_MONTH - 1 + offset, 1)),
  )

/**
 * Given two accounts, one of them a matelas, a reference salary and envelopes,
 * When I open the finance home, Then I see the two rings, the curve, and the
 * Comptes, Budget and Planification tiles.
 */
test('the home shows the rings, the month spending, the next project and the three tiles', async ({
  page,
}) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  // The dated header, and no period picker.
  await expect(overview.header).toContainText(/, jour \d+ sur \d+/)
  await expect(overview.content.getByLabel('Mois', { exact: true })).toHaveCount(0)
  await expect(overview.content.getByLabel('Année', { exact: true })).toHaveCount(0)
  await expect(overview.content.getByRole('tab', { name: "Vue d'ensemble" })).toHaveCount(0)
  await expect(overview.currentTab).toHaveAttribute('aria-selected', 'true')

  await expect(overview.scoreRing('week')).toBeVisible()
  await expect(overview.scoreRing('month')).toBeVisible()
  await expect(overview.nextProject).toBeVisible()
  await expect(overview.monthSpendingCard).toContainText('Dépenses d')
  await expect(overview.monthSpendingCard.getByTestId('rhythm-curve')).toBeVisible()
  await expect(overview.budgetTile).toBeVisible()
  await expect(overview.planningTile).toBeVisible()
  for (const tile of [overview.accountsTile, overview.budgetTile, overview.planningTile]) {
    await expect(tile).toContainText('Ouvrir →')
  }

  // The blocks the tiles replaced are gone.
  await expect(overview.content.getByText('Solde de tous les comptes')).toHaveCount(0)
  await expect(overview.content.getByText('Principaux postes du mois')).toHaveCount(0)
  await expect(overview.content.getByText('Recettes et dépenses sur douze mois')).toHaveCount(0)
})

/**
 * Given a month just ended that is not reviewed and a project under way, When
 * I open the finance home, Then the review's action card « Revue d'… · ce soir »
 * stands above the rings (and no « Revue ce soir » pill), the « Prochain projet »
 * card shows the project, and « Lancer » opens the review activity (MAG-631).
 * `e2e-balance@maggie.local` (58-finance-month-balance.yaml): a line of last
 * month nobody rated, a 1 000 € « Vélo » and a complete matelas.
 */
test('a review due draws its action card beside the next project, and Lancer opens the review activity', async ({
  balanceUser,
}) => {
  const { page } = balanceUser
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  const card = overview.reviewActionCard
  await expect(card).toBeVisible()
  await expect(card).toContainText(/Revue d/)
  await expect(card).toContainText('ce soir')
  await expect(overview.content.getByText('Revue ce soir', { exact: true })).toHaveCount(0)

  await expect(overview.nextProject).toContainText('Vélo')
  await expect(overview.scoreRing('week')).toBeVisible()
  await expect(overview.scoreRing('month')).toBeVisible()

  await card.getByRole('link', { name: 'Lancer' }).click()
  await expect(page).toHaveURL(/#\/finance\/review\/activity$/)
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
 * Given two projects and 58 € of overspending this month that push the second
 * one back, When I open the finance home, Then « Prochain projet » shows the
 * first project of the planning, what is gathered, the one after it, and the
 * sentence that says what the overspending costs, and the Planification tile
 * draws the planning as a compact Gantt. An account of its own
 * (62-finance-next-project.yaml): 740 € gathered on the 1 000 € « Vélo », bought
 * this month, then the 3 190 € trip six months on, pushed back to the seventh.
 * The owner's projects are not read here: `finance-projects.spec.ts` reorders them.
 */
test('the Prochain projet card shows the first project of the planning and what the overspending pushes back', async ({
  nextProjectUser,
}) => {
  const overview = new FinanceOverviewPage(nextProjectUser.page)
  await overview.open()

  const card = overview.nextProject
  await expect(card).toContainText('Vélo')
  await expect(card).toContainText(`${euros(74000)} sur ${euros(100000)}`)
  await expect(card.getByTestId('next-project-date')).toContainText(`ce mois-ci · ${monthLabel(0)}`)

  const following = card.getByTestId('next-project-following')
  await expect(following).toHaveCount(1)
  await expect(following).toContainText(`Voyage au Japon · ${euros(319000)} · ${monthLabel(6)}`)

  await expect(card.getByTestId('next-project-negative')).toContainText(
    `Les dépassements de ce mois (Restaurants et Obsydian, +${euros(5800)}) repoussent Voyage au Japon d'un mois.`,
  )
  await expect(card.getByRole('link')).toHaveCount(0)

  // The Planification tile draws the same planning as a compact Gantt.
  await expect(overview.planningTile.getByTestId('planning-gantt')).toBeVisible()
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
 * The rings and the rhythm curve (MAG-417, extends MAG-102 — they replace the
 * daily score; MAG-643 — « Dépensé » counts every debit).
 *
 * An account of its own, `e2e-scores@maggie.local`: a rent of 700 € debited on
 * the 5th (the only recurring operation), 300 € of groceries and 250 € debited
 * and filed nowhere. CI pins the day to the 13th: 1 250 € spent, whatever the
 * envelopes say, against 700 € expected — more spent than expected is normal,
 * so the ring gives no verdict, no word and no colour of one. The rent is
 * marked on the curve on the 5th (see 57-finance-scores.yaml), in a label of the mockup's size (MAG-697).
 */
test('the rings count every debit and expect the recurring ones, and the curve marks the rent on the 5th', async ({
  scoresUser,
}) => {
  const overview = new FinanceOverviewPage(scoresUser.page)
  await overview.open()

  const month = overview.scoreRing('month')
  await expect(month).toBeVisible()
  await expect(month.getByTestId('ring-spent')).toContainText(euros(125000))
  await expect(month.getByTestId('ring-expected')).toContainText(`Attendu ${euros(70000)}`)
  await expect(month.getByTestId('rhythm-tick')).toBeVisible()
  await expect(month).not.toContainText(/rythme|restants|Dans le vert|Dans les clous|Attention|Dépassement/)

  // A touch gives the reasons, and another one hides them.
  const reasons = await overview.scoreReasons('month')
  await expect(reasons).toBeVisible()
  await expect(reasons.getByRole('listitem').first()).toBeVisible()
  await month.getByRole('button').click()
  await expect(reasons).toBeHidden()

  // Nothing was booked in the pinned day's week (the rent is on the 5th, the
  // groceries on the 1st, the unclassified line on the 2nd): nothing spent,
  // nothing expected, so no tick.
  const week = overview.scoreRing('week')
  await expect(week).toBeVisible()
  await expect(week.getByTestId('ring-spent')).toContainText(euros(0))
  await expect(week.getByTestId('rhythm-tick')).toHaveCount(0)
  await expect(week).not.toContainText(/rythme|restants|Dans le vert|Dans les clous|Attention|Dépassement/)

  // What is not classed is said under the ring, sober, and one tap from the
  // review of those very lines (MAG-643). The week holds none of them.
  const unclassified = month.getByTestId('ring-unclassified')
  await expect(unclassified).toContainText(`${euros(25000)} non classés`)
  await expect(week.getByTestId('ring-unclassified')).toHaveCount(0)
  await month.getByTestId('ring-unclassified-link').click()
  await expect(scoresUser.page).toHaveURL(/\/finance\/review\?.*state=unrecognised/)
  const review = new FinanceReviewPage(scoresUser.page)
  await expect(review.row('CARTE INCONNUE')).toBeVisible()
  await expect(review.row('LOYER')).toHaveCount(0)

  await overview.open()
  const [year, monthNumber] = seedAnchorDate().split('-')
  const rent = overview.debitMarker('Loyer')
  await expect(rent).toHaveCount(1)
  await expect(rent).toHaveAttribute('data-date', `${year}-${monthNumber}-05`)
  // MAG-697: its label keeps the mockup's size (11 px text), whatever the width of the curve.
  expect((await rent.locator('text').boundingBox())!.height).toBeLessThan(16)
  await expect(overview.rhythmCurve.getByTestId('spent-line')).toBeVisible()
})

/**
 * What waits for the owner, in the tiles (MAG-421, extends MAG-102): « N à
 * trier » and « N rejet(s) » in Comptes, each one tap from the screen that
 * settles it, and an example « On te doit » that is shown and opens nothing.
 *
 * An exact count needs an account nobody else writes to. The owner's pile is
 * shared (other files keep lines to sort on purpose) and the neighbour's is
 * written by the recurring and independence journeys, so this runs as the
 * seasonality account (`seasonUser`), which holds no finance data and whose own
 * journey never reads any. Its two tests are serial, and the account the first
 * one creates is deleted after each (that takes its lines with it).
 *
 * The lines are dated in the two days up to the anchor (the 13th in CI): a line
 * of last month would raise « Revue ce soir », and the rejection pair is only
 * detected inside a window of recent days. The pill opens the incidents of the
 * account it counts for, whose id the test does not compare (the dashboard's
 * spelling of an id is not the account list's, see MAG-261).
 */
test.describe('what is to do', () => {
  test.describe.configure({ mode: 'serial' })

  const createdAccountIds: string[] = []

  test.afterEach(async ({ seasonUser }) => {
    for (const id of createdAccountIds.splice(0)) {
      await seasonUser.api.delete(`/api/accounts/${id}`)
    }
  })

  async function createAccount(api: APIRequestContext, name: string): Promise<string> {
    const response = await api.post('/api/accounts', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: { name, type: 'checking', currency: 'EUR', balanceCents: 0 },
    })
    expect(response.status(), await response.text()).toBe(201)

    return ((await response.json()) as { id: string }).id
  }

  async function createTransaction(
    api: APIRequestContext,
    accountId: string,
    label: string,
    amountCents: number,
    bookedAt: string,
  ): Promise<void> {
    const response = await api.post('/api/transactions', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: {
        account: `/api/accounts/${accountId}`,
        label,
        amountCents,
        currency: 'EUR',
        bookedAt,
        status: 'spent',
      },
    })
    expect(response.status(), await response.text()).toBe(201)
  }

  /**
   * Given an account with nothing to sort, no rejection, no advance and no
   * month to review, When I open the finance home, Then no pill and no
   * review card is drawn, and the account's tile is not an empty block either.
   */
  test('nothing to do draws no pill and no review card', async ({ seasonUser }) => {
    const overview = new FinanceOverviewPage(seasonUser.page)
    await overview.open()

    await expect(overview.toSortPill).toHaveCount(0)
    await expect(overview.rejectionsPill).toHaveCount(0)
    await expect(overview.advancePill).toHaveCount(0)
    await expect(overview.reviewActionCard).toHaveCount(0)
  })

  /**
   * Given 3 lines to sort and 1 rejected payment, When I open the finance
   * home, Then Comptes says « 3 à trier » and « 1 rejet »; When I touch
   * « 3 à trier », Then À qualifier opens on the oldest line. (« On te doit /
   * Tu dois » has its journey in `finance-peer-debts.spec.ts`.)
   */
  test('the Comptes tile says what is to sort and to settle, and opens the screen that does it', async ({
    seasonUser,
  }) => {
    const { api, page } = seasonUser
    const attempt = test.info().retry
    const accountName = `Courant MAG-421, essai ${attempt}`
    const sorted = [-2, -1, 0].map((offset, index) => ({
      label: `A TRIER MAG-421 ESSAI ${attempt} LIGNE ${'ABC'[index]}`,
      cents: -(1200 + 100 * index),
      day: seedDate(offset),
    }))
    const debit = `PRELEVEMENT EDF MAG-421 ESSAI ${attempt}`
    const credit = `REJET PRLV EDF MAG-421 ESSAI ${attempt}`

    const accountId = await createAccount(api, accountName)
    createdAccountIds.push(accountId)
    for (const line of sorted) {
      await createTransaction(api, accountId, line.label, line.cents, line.day)
    }
    // Created spent, the credit is paired as it lands: what a sync does.
    await createTransaction(api, accountId, debit, -20_600, seedDate(0))
    await createTransaction(api, accountId, credit, 20_600, seedDate(0))
    for (const label of [debit, credit]) {
      await waitForIndexed<{ label?: string; transferKind?: string }>(
        api,
        '/api/transactions?transferKind=rejected&itemsPerPage=100',
        (candidate) => candidate.label === label && candidate.transferKind === 'rejected',
        { what: `The line "${label}", recognised as a rejection` },
      )
    }

    const overview = new FinanceOverviewPage(page)
    await overview.open()

    // The rejection pair is no line to sort: three, not five.
    await expect(overview.toSortPill).toHaveText('3 à trier')
    await expect(overview.rejectionsPill).toHaveText('1 rejet')
    await expect(overview.advancePill).toHaveCount(0)
    await expect(overview.reviewActionCard).toHaveCount(0)

    // « 1 rejet » opens the incidents of the account that holds it.
    await overview.rejectionsPill.click()
    await expect(page).toHaveURL(/#\/accounts\/[^/]+\/transactions\?tab=incidents$/)
    await expect(new FinanceTransfersPage(page).incidentsTab(1)).toHaveAttribute('aria-selected', 'true')

    // « 3 à trier » opens Revue on the lines to qualify, oldest first; the line opens its fiche (MAG-539).
    await overview.open()
    await overview.toSortPill.click()
    await expect(page).toHaveURL(/#\/finance\/review\?state=unrecognised&period=all$/)
    const review = new FinanceReviewPage(page)
    await expect(review.rows.first()).toContainText(sorted[0].label)
    await review.select(sorted[0].label)
    for (const line of sorted) {
      await expect(review.row(line.label)).toBeVisible()
    }
    await expect(review.rows).toHaveCount(3)
  })
})

/**
 * The seven Finance menus (MAG-427, MAG-518, extends MAG-102).
 *
 * A menu page is the content of its aspects, one tab each — never a summary and
 * never a list of links. Configuration holds two tabs, and the banks tab is the
 * banks page itself.
 */
test('Finance › Configuration: one tab per aspect, the banks tab is the banks page', async ({ page }) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  await overview.navigateTo('Configuration', 'Finance')

  // The aspect tabs only: the categories page carries its own tabs (rules, suggestions) below them.
  await expect(overview.content.locator('[data-testid^="aspect-tab-"]')).toHaveText(['Catégories', 'Banques'])
  await expect(overview.content.getByTestId('summary-block')).toHaveCount(0)

  await overview.content.getByTestId('aspect-tab-banks').click()

  await expect(page).toHaveURL(/#\/finance\/summary\/settings\/banks$/)
  await expect(new FinanceBanksPage(page).connectionsCard).toBeVisible()
})

/**
 * Given projects, a loan declared and rentes measured, When I open Finance ›
 * Planification, Then it opens on the projects, its first tab (MAG-519);
 * When I click the Prêts tab, Then I see the loan and its timeline; When I
 * click the Indépendance tab, Then the counter is shown (MAG-518).
 */
test('Finance › Planification opens on the projects, the Prêts tab shows the loans, the Indépendance tab the counter', async ({
  page,
}) => {
  const overview = new FinanceOverviewPage(page)
  await overview.open()

  await overview.navigateTo('Planification', 'Finance')

  const projects = new FinanceProjectsPage(page)
  await expect(overview.content.getByRole('tab', { name: 'Projets' })).toHaveAttribute('aria-selected', 'true')
  await expect(projects.row('Déménagement')).toBeVisible()

  await overview.content.getByRole('tab', { name: 'Prêts' }).click()

  const loans = new FinanceLoansPage(page)
  await expect(loans.timeline).toBeVisible()
  await expect(loans.row('Crédit voiture')).toBeVisible()

  await overview.content.getByRole('tab', { name: 'Indépendance' }).click()

  await expect(page).toHaveURL(/#\/finance\/summary\/planning\/independence$/)
  await expect(overview.content.getByTestId('independence-card')).toContainText('60 %')
  await expect(loans.timeline).toHaveCount(0)

  // The tab is in the URL: the back button returns to the loans.
  await page.goBack()
  await expect(loans.timeline).toBeVisible()
})

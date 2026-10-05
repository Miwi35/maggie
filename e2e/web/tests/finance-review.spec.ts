import { test, expect, seedAnchorDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { euros } from '../helpers/money.js'
import { FinanceReviewPage } from '../pages/FinanceReviewPage.js'

/**
 * The monthly look back (MAG-102).
 *
 * Nothing of the review is stored: the verdict on each transaction is the
 * durable data, and the comparison, the optimisation score and what is left to
 * qualify are all read back from it. So the journey judges two spends and
 * watches the score follow — which is the only way to prove the read side is
 * computed rather than remembered.
 *
 * Only the non-obligatory debits are offered: a month's groceries are not a
 * choice anyone made, and putting them in front of the owner would be asking
 * them to regret eating.
 *
 * The second test writes, and it writes **three years back**, where no other
 * journey and no other window looks — `finance-overview.spec.ts` uses two
 * years back for its empty month, and every figure the dashboard, the score
 * and the safety net read stops at twelve months.
 */

interface StoredTransaction {
  id?: string
  label?: string
  bookedAt?: string
  retrospect?: string
}

/**
 * One judged spend, in the month this attempt wrote into.
 *
 * The month is part of the match because only the month varies per attempt:
 * CI retries once without reseeding, and matching on the label alone would let
 * a replay read the previous attempt's row and call it proof.
 */
const judged = (label: string, verdict: string, monthPrefix: string) =>
  (candidate: StoredTransaction): boolean =>
    candidate.label === label
    && candidate.retrospect === verdict
    && true === candidate.bookedAt?.startsWith(monthPrefix)

const [ANCHOR_YEAR, ANCHOR_MONTH] = seedAnchorDate().split('-').map(Number)

/** Last month, which is what the page opens on — this one is not over. */
const LAST = 1 === ANCHOR_MONTH
  ? { year: ANCHOR_YEAR - 1, month: 12 }
  : { year: ANCHOR_YEAR, month: ANCHOR_MONTH - 1 }

test('last month is already qualified, and the comparison is spelled out', async ({ page }) => {
  const review = new FinanceReviewPage(page)
  await review.open()

  // The page opens on last month on its own: nothing is set here.
  await expect(review.summary.getByLabel('Année')).toHaveValue(String(LAST.year))

  // 380,00 € of groceries and 120,00 € at the FNAC, and nothing in the three
  // months before them or a year earlier — so every comparison is the whole
  // 500,00 €.
  await expect(review.summary).toContainText(`${euros(50000)} dépensés ce mois-ci`)
  await expect(review.summary).toContainText(
    `mois précédent : ${euros(0)} (+${euros(50000)})`,
  )
  await expect(review.summary).toContainText(
    `moyenne 3 mois : ${euros(0)} (+${euros(50000)})`,
  )

  // The FNAC is the month's only reviewable spend — the groceries are
  // mandatory — and the seed already judged it avoidable: nothing kept out of
  // everything judged is a score of 0 %.
  await expect(review.summary).toContainText("Score d'optimisation : 0 %")
  await expect(review.summary).toContainText(`${euros(12000)} jugés évitables`)
  await expect(review.pendingCard).toContainText('Tout est qualifié pour ce mois.')
})

test('judging two spends fills the optimisation score from them', async ({ page, api }) => {
  // A month of its own per attempt: CI retries once without reseeding, and a
  // replay that found the first attempt's verdicts in the same month would
  // compute its score from four spends instead of two.
  const period = { year: ANCHOR_YEAR - 3, month: 1 + test.info().retry }
  const month = String(period.month).padStart(2, '0')
  const kept = { label: 'RESTAURANT MAG-102', cents: -6000, day: `${period.year}-${month}-08` }
  const regretted = { label: 'CINEMA MAG-102', cents: -2500, day: `${period.year}-${month}-19` }

  // Written through the API rather than through the form: what is under test
  // is the review, and the two spends are its fixture. Left uncategorised on
  // purpose — an uncategorised debit is reviewable, and it is the case most
  // worth a second look.
  for (const spend of [kept, regretted]) {
    const response = await api.post('/api/transactions', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: {
        account: `/api/accounts/${seedId('e2e_account_checking')}`,
        label: spend.label,
        amountCents: spend.cents,
        currency: 'EUR',
        bookedAt: spend.day,
        status: 'spent',
      },
    })
    expect(response.status(), await response.text()).toBe(201)
  }

  const review = new FinanceReviewPage(page)
  await review.open()
  await review.selectPeriod(period.year, period.month)

  // The order is read in one shot, so wait for the period's own lines first:
  // the card keeps the previous month's on screen while the new ones are
  // being fetched, and an empty list would read as "nothing to qualify".
  await expect(review.pending(kept.label)).toContainText(euros(6000))
  // Biggest first, which is the order the card promises — the spend worth
  // thinking about is the expensive one.
  expect(await review.pendingLabels()).toEqual([kept.label, regretted.label])
  await expect(
    review.summary,
    'a month nobody has looked at has no score, which is not a zero',
  ).toContainText("Aucune dépense qualifiée pour l'instant")

  await review.rate(kept.label, 'À conserver')
  // Everything judged so far was worth keeping.
  await expect(review.summary).toContainText("Score d'optimisation : 100 %")

  await review.rate(regretted.label, "J'aurais pu m'en passer")
  // 60,00 € kept out of 85,00 € judged — 71 %, rounded.
  await expect(review.summary).toContainText("Score d'optimisation : 71 %")
  await expect(review.summary).toContainText(`${euros(2500)} jugés évitables`)
  await expect(review.pendingCard).toContainText('Tout est qualifié pour ce mois.')

  // The verdicts are the data; the score above is read from them.
  const monthPrefix = `${period.year}-${month}-`

  await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=200',
    judged(kept.label, 'keep', monthPrefix),
    { what: 'The spend judged worth keeping' },
  )

  await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=200',
    judged(regretted.label, 'avoidable', monthPrefix),
    { what: 'The spend judged avoidable' },
  )
})

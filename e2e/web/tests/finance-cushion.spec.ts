import { test, expect } from '../fixtures/index.js'
import { euros } from '../helpers/money.js'
import { CUSHION_STATE_LABELS } from '../pages/financeLabels.js'
import { FinanceCushionPage } from '../pages/FinanceCushionPage.js'
import { FinanceLoansPage } from '../pages/FinanceLoansPage.js'

/**
 * The safety net and the debts (MAG-102).
 *
 * Both screens show figures that exist nowhere in the database. The net is the
 * balance of the accounts flagged "matelas" against a target that is a number
 * of months of net income; the relief schedule amortises each loan month by
 * month the way a bank does. Nothing is stored, so nothing can be asserted
 * except the arithmetic — which is what this file does, against the seed:
 *
 *   target        3 months × 2 350,00 € = 7 050,00 €, of which 4 200,00 € held
 *   recharge      2 850,00 € missing, capped at 150,00 € a month → 19 months
 *   loan          4 800,00 € at 3,20 %, 210,00 € a month → 24 months, 159,05 €
 *                 of interest over the 60-month horizon
 *   capacity      2 350,00 € − 210,00 € − 166,66 € of measured lifestyle
 *
 * **This file owns the cushion's target**, because the second test changes it.
 * `finance-budget.spec.ts` therefore asserts that the score is held back by an
 * incomplete net without naming the deficit.
 */

const INCOME_CENTS = 235000
const HELD_CENTS = 420000
const RECHARGE_CAP_CENTS = 15000

/**
 * One test, not two, and it sets the target before reading it.
 *
 * The target is what every other figure on the screen comes from, so a test
 * asserting the seeded three months and a test raising it to four cannot run
 * beside each other — and a serial pair would not help either: CI retries
 * once without reseeding, and a replayed group would start on the target the
 * first attempt left behind. Writing the target first makes the whole journey
 * idempotent.
 */
test('the net is read off the accounts, the recharge is capped, and the target drives both', async ({
  page,
}) => {
  const cushion = new FinanceCushionPage(page)
  await cushion.open()

  await expect(cushion.stateChip).toHaveText(CUSHION_STATE_LABELS.building)

  await cushion.setTargetMonths(3)

  const target = 3 * INCOME_CENTS
  await expect(cushion.summary).toContainText(`${euros(HELD_CENTS)} sur ${euros(target)}`)
  await expect(
    cushion.summary,
    '4 200,00 € is 1,8 months of a 2 350,00 € income',
  ).toContainText('1.8 mois de revenu couverts sur 3')

  // The cap wins over the wished-for six-month horizon: 2 850,00 € at
  // 150,00 € a month takes 19 months, and the page says so rather than
  // quietly asking for 475,00 € a month.
  const deficit = target - HELD_CENTS
  await expect(cushion.summary).toContainText(`Il manque ${euros(deficit)}`)
  await expect(cushion.summary).toContainText(
    `à ${euros(RECHARGE_CAP_CENTS)} par mois, le matelas est reconstitué en 19 mois`,
  )
  await expect(cushion.summary).toContainText('le plafond mensuel allonge la durée')

  // Why the day's score cannot be green — the same rule `finance-budget.spec.ts`
  // reads from the other side.
  await expect(cushion.summary).toContainText(
    "Tant que le matelas n'est pas complet, le score global ne peut pas passer au vert.",
  )

  // And which accounts the net is made of: the savings one is flagged, the
  // current account is not.
  await expect(cushion.summary).toContainText(`Livret A — ${euros(HELD_CENTS)}`)
  await expect(cushion.summary).not.toContainText('Compte courant')

  // Raising the target recomputes everything downstream of it. `CushionSettings`
  // is keyed on the stored values, so it is remounted from the server's answer:
  // what the page shows now is what the API computed, not what was typed.
  await cushion.setTargetMonths(4)

  const raised = 4 * INCOME_CENTS
  await expect(cushion.summary).toContainText(`${euros(HELD_CENTS)} sur ${euros(raised)}`)
  await expect(cushion.summary).toContainText('mois de revenu couverts sur 4')

  // 5 200,00 € missing at the same 150,00 € cap: 35 months, not the six the
  // horizon asks for.
  await expect(cushion.summary).toContainText(`Il manque ${euros(raised - HELD_CENTS)}`)
  await expect(cushion.summary).toContainText(
    `à ${euros(RECHARGE_CAP_CENTS)} par mois, le matelas est reconstitué en 35 mois`,
  )
})

test('the relief schedule says when the loan frees its payment, and what it costs until then', async ({
  page,
}) => {
  const loans = new FinanceLoansPage(page)
  await loans.open()

  await expect(loans.timeline).toContainText(`${euros(480000)} restant dû`)
  await expect(loans.timeline).toContainText(`${euros(21000)} par mois`)
  // Amortised month by month: interest on what is still owed, the rest off the
  // capital. 4 800,00 € at 3,20 % paid 210,00 € a month costs 159,05 € of
  // interest and is clear in 24 months.
  await expect(loans.timeline).toContainText(`dont ${euros(15905)} d'intérêts sur 60 mois`)
  await expect(loans.timeline).toContainText('Prochaine libération : Crédit voiture dans 2 ans')

  await expect(loans.relief('Crédit voiture')).toContainText(
    `+${euros(21000)}/mois (cumul ${euros(21000)})`,
  )

  await expect(loans.timeline).toContainText(
    `Capacité d'épargne nette : ${euros(235000 - 21000 - 16666)}`,
  )

  // The list below the panel: what the owner typed in, rate included.
  const row = loans.row('Crédit voiture')
  await expect(row).toContainText('Crédit Mutuel')
  await expect(row).toContainText(euros(480000))
  await expect(row).toContainText(euros(21000))
  await expect(row, '320 basis points read as a rate').toContainText('3,20 %')
})

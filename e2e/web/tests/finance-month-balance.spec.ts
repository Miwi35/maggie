import { test, expect, seedAnchorDate } from '../fixtures/index.js'
import { euros } from '../helpers/money.js'
import { FinanceMonthBalancePage } from '../pages/FinanceMonthBalancePage.js'

/**
 * Dashboard › État courant (MAG-457, extends MAG-102).
 *
 * An account of its own, `e2e-balance@maggie.local` (58-finance-month-balance.yaml):
 * « Alimentation » 100 € spent on 300 €, « Transport » 150 € spent on 100 € —
 * 400 € of budget, 250 € spent, 150 € left, whatever the month.
 *
 * It only reads. The status is spelled as a word and never as a colour alone.
 */

const [ANCHOR_YEAR, ANCHOR_MONTH] = seedAnchorDate().split('-').map(Number)

/**
 * Given a month with an overspent envelope, When I open Dashboard › État
 * courant on it, Then the envelope bar is overrun and the month ring shows its
 * total.
 */
test('an overspent envelope is overrun on its bar, and the month ring shows everything spent', async ({
  balanceUser,
}) => {
  const balance = new FinanceMonthBalancePage(balanceUser.page)
  await balance.open()

  const transport = balance.envelopeBar('Transport')
  await expect(transport).toHaveAttribute('data-status', 'over')
  await expect(transport).toContainText('Dépassée')
  await expect(transport).toContainText(`${euros(15000)} sur ${euros(10000)}`)

  const food = balance.envelopeBar('Alimentation')
  await expect(food).toHaveAttribute('data-status', 'held')
  await expect(food).toContainText('Tenue')

  // 100 € + 150 € spent; the envelopes sort the money, they do not set the ring's scale.
  await expect(balance.monthRing.getByTestId('ring-spent')).toContainText(euros(25000))
})

test('a month with nothing in it is drawn empty, not as the month before', async ({ balanceUser }) => {
  const balance = new FinanceMonthBalancePage(balanceUser.page)
  await balance.open()
  await expect(balance.envelopeBar('Transport')).toBeVisible()

  // Two years back: outside everything this account holds.
  await balance.selectPeriod(ANCHOR_YEAR - 2, ANCHOR_MONTH)

  await expect(balance.envelopeBar('Transport')).toHaveCount(0)
  await expect(balance.content.getByTestId('envelope-bars-empty')).toBeVisible()
})

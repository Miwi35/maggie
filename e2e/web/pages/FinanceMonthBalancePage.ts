import { expect } from '@playwright/test'
import type { Locator } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { MONTH_LABELS } from './financeLabels.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Dashboard › État courant (`/finance/dashboard/current`, MAG-457).
 *
 * One read of the month balance, drawn as the month ring and its curve, the
 * envelope bars, the satisfaction ring, the savings and the cushion. Nothing
 * here is stored, so the journeys read the figures and check them against the
 * fixtures. The envelope bars carry their status in `data-status`.
 */
export class FinanceMonthBalancePage extends AdminShell {
  async open(): Promise<void> {
    await this.goto(ROUTES.financeMonthBalance)
    // Not the heading: it renders before the fetch answers.
    await expect(this.balance).toBeVisible()
  }

  get balance(): Locator {
    return this.content.getByTestId('month-balance')
  }

  /** The month ring: what is left of the month's budget and in which state. */
  get monthRing(): Locator {
    return this.content.getByTestId('balance-spending').getByTestId('score-ring')
  }

  /** An envelope's bar, by the category it carries. */
  envelopeBar(name: string): Locator {
    return this.content.locator(`[data-testid="envelope-bar"][data-name="${name}"]`)
  }

  async selectPeriod(year: number, month: number): Promise<void> {
    await this.content.getByLabel('Année', { exact: true }).fill(String(year))
    await this.content.getByLabel('Mois', { exact: true }).click()
    await this.page.getByRole('option', { name: MONTH_LABELS[month] }).click()
    await expect(this.content.getByLabel('Mois', { exact: true })).toHaveText(MONTH_LABELS[month])
  }
}

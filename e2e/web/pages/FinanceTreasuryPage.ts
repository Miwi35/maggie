import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Trésorerie (`/finance/treasury`, MAG-280).
 *
 * The treasury until the salary comes: the delay, the projected balance with its
 * overdraft zone, the advance to make and the two settings that shape them.
 * Nothing here is stored; every figure is the outlook of the day.
 */
export class FinanceTreasuryPage extends AdminShell {
  readonly delay: Locator
  readonly overdraft: Locator
  readonly chart: Locator
  readonly overdraftZone: Locator
  readonly days: Locator
  readonly advice: Locator
  readonly prepareAdvance: Locator
  readonly limitField: Locator
  readonly saveLimit: Locator

  constructor(page: Page) {
    super(page)
    this.delay = this.content.getByTestId('salary-delay')
    this.overdraft = this.content.getByTestId('overdraft-forecast')
    this.chart = this.content.getByTestId('treasury-chart')
    this.overdraftZone = this.content.getByTestId('treasury-overdraft-zone')
    this.days = this.content.getByTestId('treasury-day')
    this.advice = this.content.getByTestId('advice')
    this.prepareAdvance = this.content.getByRole('link', { name: "Préparer l'avance" })
    this.limitField = this.content.getByLabel('Découvert autorisé (€)')
    this.saveLimit = this.content.getByRole('button', { name: 'Enregistrer le découvert' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeTreasury)
    await expect(this.chart).toBeVisible()
  }

  /** Types the limit in euros and saves; resolves once the API has answered. */
  async setLimit(euros: string): Promise<void> {
    await this.limitField.fill(euros)
    await Promise.all([
      this.page.waitForResponse(
        (response) =>
          response.url().includes('/api/finance/treasury-settings') && 'PATCH' === response.request().method(),
      ),
      this.saveLimit.click(),
    ])
  }
}

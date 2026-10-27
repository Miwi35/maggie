import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Trésorerie (`/finance/treasury`, MAG-280).
 *
 * One page, no tabs (MAG-632): the projected balance over the horizon chosen (MAG-506) with its
 * delay, overdraft zone and advice, the cushion, the savings advances, the créances and dettes,
 * then the day-by-day movements. The settings that shape them are behind « Régler », in a
 * dialog. Nothing here is stored; every figure is the outlook of the day.
 */
export class FinanceTreasuryPage extends AdminShell {
  readonly delay: Locator
  readonly overdraft: Locator
  readonly chart: Locator
  readonly overdraftZone: Locator
  readonly flowsChart: Locator
  readonly flowsDetail: Locator
  readonly days: Locator
  readonly advice: Locator
  readonly prepareAdvance: Locator
  readonly settingsButton: Locator
  /** « Réglages »: a dialog, mounted outside the page content. */
  readonly settingsDialog: Locator
  readonly limitField: Locator
  readonly saveLimit: Locator
  readonly tabs: Locator
  readonly chartSection: Locator
  readonly cushionSection: Locator
  readonly advancesSection: Locator
  readonly debtsSection: Locator
  readonly movementsSection: Locator

  constructor(page: Page) {
    super(page)
    this.delay = this.content.getByTestId('salary-delay')
    this.overdraft = this.content.getByTestId('overdraft-forecast')
    this.chart = this.content.getByTestId('treasury-chart')
    this.overdraftZone = this.content.getByTestId('treasury-overdraft-zone')
    this.flowsChart = this.content.getByTestId('treasury-flows-chart')
    this.flowsDetail = this.content.getByTestId('treasury-flows-detail')
    this.days = this.content.getByTestId('treasury-day')
    this.advice = this.content.getByTestId('advice')
    this.prepareAdvance = this.content.getByRole('link', { name: "Préparer l'avance" })
    this.settingsButton = this.content.getByRole('button', { name: 'Régler' })
    this.settingsDialog = page.getByRole('dialog', { name: 'Réglages' })
    this.limitField = this.settingsDialog.getByLabel('Découvert autorisé (€)')
    this.saveLimit = this.settingsDialog.getByRole('button', { name: 'Enregistrer le découvert' })
    this.tabs = this.content.getByRole('tab')
    this.chartSection = this.content.getByTestId('treasury-section-chart')
    this.cushionSection = this.content.getByTestId('treasury-section-cushion')
    this.advancesSection = this.content.getByTestId('treasury-section-advances')
    this.debtsSection = this.content.getByTestId('treasury-section-debts')
    this.movementsSection = this.content.getByTestId('treasury-section-movements')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeTreasury)
    await expect(this.chart).toBeVisible()
  }

  /** Picks a horizon (« 1 an »); resolves once the API has answered for it. */
  async chooseHorizon(label: string, months: number): Promise<void> {
    await Promise.all([
      this.page.waitForResponse((response) => response.url().includes(`/api/finance/treasury-outlook?months=${months}`)),
      this.content.getByRole('button', { name: label, exact: true }).click(),
    ])
  }

  /** The open advance of a savings account, by the account's name. */
  advance(sourceName: string): Locator {
    return this.advancesSection.getByTestId('savings-advance').filter({ hasText: sourceName })
  }

  /** Opens « Régler ». */
  async openSettings(): Promise<void> {
    await this.settingsButton.click()
    await expect(this.settingsDialog).toBeVisible()
  }

  /**
   * Opens « Régler », types the limit in euros and saves; resolves once the API has answered
   * and the dialog, which closes on a successful save, is gone.
   */
  async setLimit(euros: string): Promise<void> {
    await this.openSettings()
    await this.limitField.fill(euros)
    await Promise.all([
      this.page.waitForResponse(
        (response) =>
          response.url().includes('/api/finance/treasury-settings') && 'PATCH' === response.request().method(),
      ),
      this.saveLimit.click(),
    ])
    await expect(this.settingsDialog).toBeHidden()
  }
}

import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Matelas (`/finance/cushion`).
 *
 * The safety net is never stored as an amount: it is the balance of the
 * accounts flagged "matelas", against a target that is a number of months of
 * net income. The settings, behind « Régler » in a dialog, are what the target,
 * the monthly effort and the horizon are computed from — so saving them is the
 * one way to check the recomputation end to end.
 */
export class FinanceCushionPage extends AdminShell {
  readonly summary: Locator
  readonly settingsButton: Locator
  /** « Réglages »: a dialog, mounted outside the page content. */
  readonly settings: Locator
  readonly stateChip: Locator
  readonly save: Locator
  /** « Avancé à des proches »: what relatives still owe, held back from the net (MAG-454). */
  readonly peerClaims: Locator

  constructor(page: Page) {
    super(page)
    this.summary = this.content
      .locator('.MuiCard-root')
      .filter({ has: page.getByRole('heading', { name: 'Matelas de sécurité' }) })
    this.settingsButton = this.content.getByRole('button', { name: 'Régler' })
    this.settings = page.getByRole('dialog', { name: 'Réglages' })
    this.stateChip = this.summary.locator('.MuiChip-label')
    this.peerClaims = this.summary.getByTestId('cushion-peer-claims')
    this.save = this.settings.getByRole('button', { name: 'Enregistrer le matelas' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeCushion)
    await expect(this.summary).toBeVisible()
  }

  /** Opens « Régler »; the fields are seeded from the server's answer, the dialog being mounted on open. */
  async openSettings(): Promise<void> {
    await this.settingsButton.click()
    await expect(this.settings).toBeVisible()
  }

  /**
   * Opens « Régler », changes the target and saves; the dialog closes on success.
   *
   * It is mounted on open, so the fields come from the server rather than from
   * local state — which is exactly what makes "the page now shows the recomputed
   * target" a real assertion and not an echo of what was typed.
   */
  async setTargetMonths(months: number): Promise<void> {
    await this.openSettings()
    await this.settings.getByLabel('Cible (mois de revenu)').fill(String(months))
    // Waiting on the write itself, not on the summary changing: a save that
    // sets the target it already had changes nothing on screen, and that is
    // the call a retry-safe journey starts with.
    await Promise.all([
      this.page.waitForResponse(
        (response) =>
          response.url().includes('/api/finance/cushion-config') &&
          'PATCH' === response.request().method(),
      ),
      this.save.click(),
    ])
    await expect(this.settings).toBeHidden()
    // Reopened, the fields are the server's answer.
    await this.openSettings()
    await expect(this.settings.getByLabel('Cible (mois de revenu)')).toHaveValue(String(months))
    await this.settings.getByRole('button', { name: 'Fermer' }).click()
    await expect(this.settings).toBeHidden()
  }

  /** The line that says where the savings go, by the critical threshold and the share (MAG-669). */
  get shareRule(): Locator {
    return this.summary.getByTestId('cushion-share-rule')
  }

  /** Opens « Régler », sets « Part du matelas (%) » and saves, waiting for the write (see {@link setTargetMonths}). */
  async setSharePercent(percent: number): Promise<void> {
    await this.openSettings()
    await this.settings.getByLabel('Part du matelas (%)').fill(String(percent))
    await Promise.all([
      this.page.waitForResponse(
        (response) =>
          response.url().includes('/api/finance/cushion-config') &&
          'PATCH' === response.request().method(),
      ),
      this.save.click(),
    ])
    await expect(this.settings).toBeHidden()
  }
}

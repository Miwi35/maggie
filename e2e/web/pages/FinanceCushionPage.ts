import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Matelas (`/finance/cushion`).
 *
 * The safety net is never stored as an amount: it is the balance of the
 * accounts flagged "matelas", against a target that is a number of months of
 * net income. The settings below it are what the target, the monthly effort
 * and the horizon are computed from — so saving them is the one way to check
 * the recomputation end to end.
 */
export class FinanceCushionPage extends AdminShell {
  readonly summary: Locator
  readonly settings: Locator
  readonly stateChip: Locator
  readonly save: Locator

  constructor(page: Page) {
    super(page)
    this.summary = this.content
      .locator('.MuiCard-root')
      .filter({ has: page.getByRole('heading', { name: 'Matelas de sécurité' }) })
    this.settings = this.content.locator('.MuiCard-root').filter({ hasText: 'Réglages' })
    this.stateChip = this.summary.locator('.MuiChip-label')
    this.save = this.settings.getByRole('button', { name: 'Enregistrer' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeCushion)
    await expect(this.summary).toBeVisible()
  }

  /**
   * Changes the target and saves.
   *
   * `CushionSettings` is remounted on a `key` built from the stored values, so
   * the fields come back from the server rather than from local state — which
   * is exactly what makes "the page now shows the recomputed target" a real
   * assertion and not an echo of what was typed.
   */
  async setTargetMonths(months: number): Promise<void> {
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
    // The settings card is keyed on the stored values, so it is remounted once
    // the fresh status has come back — which is when the summary above it is
    // the server's answer rather than the previous one.
    await expect(this.settings.getByLabel('Cible (mois de revenu)')).toHaveValue(String(months))
  }
}

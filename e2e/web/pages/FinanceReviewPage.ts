import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { MONTH_LABELS } from './financeLabels.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Revue mensuelle (`/finance/monthly-review`).
 *
 * The page opens on *last* month, because this one is not over. Nothing of the
 * review is stored: the verdict on each transaction is the durable data, and
 * the comparison, the optimisation score and what is left to qualify are read
 * back from it — which is why rating a line and watching the score move is the
 * whole journey.
 */
export class FinanceReviewPage extends AdminShell {
  readonly summary: Locator
  readonly pendingCard: Locator

  constructor(page: Page) {
    super(page)
    this.summary = this.content
      .locator('.MuiCard-root')
      .filter({ has: page.getByRole('heading', { name: 'Revue du mois' }) })
    this.pendingCard = this.content
      .locator('.MuiCard-root')
      .filter({ hasText: 'Dépenses à qualifier' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeReview)
    await expect(this.summary).toBeVisible()
  }

  /** A line waiting for a verdict, by its label. */
  pending(label: string): Locator {
    return this.pendingCard.locator('.MuiStack-root').filter({ hasText: label }).first()
  }

  /** The labels still to qualify, biggest first — the order the page promises. */
  async pendingLabels(): Promise<string[]> {
    return this.pendingCard
      .locator('.MuiStack-root > .MuiBox-root')
      .evaluateAll((lines) =>
        lines.map((line) => line.querySelector('.MuiTypography-body2')?.textContent ?? ''),
      )
  }

  /** Judges one line, the way the owner does. */
  async rate(label: string, verdict: 'À conserver' | "J'aurais pu m'en passer"): Promise<void> {
    await this.pending(label).getByRole('button', { name: verdict }).click()
    // The verdict removes the line from the pending list, which is also how
    // the page says the PATCH landed.
    await expect(this.pending(label)).toHaveCount(0)
  }

  async selectPeriod(year: number, month: number): Promise<void> {
    await this.summary.getByLabel('Année').fill(String(year))
    await this.summary.getByLabel('Mois').click()
    await this.page.getByRole('option', { name: MONTH_LABELS[month] }).click()
    await expect(this.summary.getByLabel('Mois')).toHaveText(MONTH_LABELS[month])
  }
}

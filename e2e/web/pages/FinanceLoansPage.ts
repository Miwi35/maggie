import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Prêts (`/loans`): the relief schedule above the loan list.
 *
 * `DebtTimelinePanel` amortises every loan month by month the way a bank does
 * — interest on what is still owed, the rest off the capital — so the figures
 * it shows are computed, not stored, and are worth asserting.
 */
export class FinanceLoansPage extends AdminShell {
  readonly timeline: Locator

  constructor(page: Page) {
    super(page)
    this.timeline = this.content
      .locator('.MuiCard-root')
      .filter({ has: page.getByRole('heading', { name: 'Libération des charges' }) })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.loans)
    await expect(this.timeline).toBeVisible()
  }

  /** A line of the relief schedule, by the loan it frees. */
  relief(loanName: string): Locator {
    return this.timeline.locator('.MuiStack-root').filter({ hasText: loanName }).first()
  }

  /** A row of the loan list below the panel. */
  row(text: string): Locator {
    return this.content.getByRole('row').filter({ hasText: text })
  }
}

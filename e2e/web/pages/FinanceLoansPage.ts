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

  /**
   * A line of the relief schedule, by the loan it frees.
   *
   * Scoped to the schedule's own lines (`data-testid="debt-relief"`) rather
   * than to whatever `Stack` happens to hold the name: the card's header and
   * its footer mention the loans too.
   */
  relief(loanName: string): Locator {
    return this.timeline.getByTestId('debt-relief').filter({ hasText: loanName })
  }

  /** A row of the loan list below the panel. */
  row(text: string): Locator {
    return this.content.getByRole('row').filter({ hasText: text })
  }

  /** Opens a loan on its sheet: the row opens it. */
  async openSheet(loanName: string): Promise<void> {
    await this.row(loanName).click()
    await expect(this.content.getByRole('button', { name: 'Enregistrer' })).toBeVisible()
  }

  /** Picks an option of a select of the sheet, by its label. */
  async choose(label: string, option: string): Promise<void> {
    await this.content.getByRole('combobox', { name: label }).click()
    await this.page.getByRole('option', { name: option, exact: true }).click()
  }

  /** Saves the sheet, waiting for the PATCH or PUT itself. */
  async save(): Promise<void> {
    await Promise.all([
      this.page.waitForResponse(
        (response) => /\/api\/loans\//.test(response.url()) && ['PATCH', 'PUT'].includes(response.request().method()),
      ),
      this.content.getByRole('button', { name: 'Enregistrer' }).click(),
    ])
  }

  /** The early repayment simulation of the sheet (MAG-523). */
  get simulation(): Locator {
    return this.content.getByTestId('early-repayment')
  }
}

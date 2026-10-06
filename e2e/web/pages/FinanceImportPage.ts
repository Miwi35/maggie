import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Import de relevé (`/finance/import`).
 *
 * Two steps, always: the rehearsal reports what it would do and writes
 * nothing, and only then is there something to confirm. So the confirm button
 * does not exist until a rehearsal has run — which is what {@link confirm}
 * asserting on its own label is for.
 */
export class FinanceImportPage extends AdminShell {
  readonly accountPicker: Locator
  readonly fileInput: Locator
  readonly simulate: Locator
  /** "Importer N opération(s)" — only rendered once a rehearsal found something to write. */
  readonly confirm: Locator
  readonly report: Locator

  constructor(page: Page) {
    super(page)
    this.accountPicker = this.content.getByRole('combobox', { name: /Compte/ })
    // A file input is not a role, so it is reached by its label like any other
    // field of the form.
    this.fileInput = this.content.getByLabel(/Fichier/)
    this.simulate = this.content.getByRole('button', { name: "Simuler l'import" })
    this.confirm = this.content.getByRole('button', { name: /^Importer \d+ opération/ })
    this.report = this.content.getByRole('table')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeImport)
    await expect(this.simulate).toBeVisible()
  }

  async chooseAccount(name: string): Promise<void> {
    await this.accountPicker.click()
    await this.page.getByRole('option', { name, exact: true }).click()
  }

  /** Drops a statement built in memory — no fixture file to keep in step. */
  async dropStatement(csv: string, name = 'releve.csv'): Promise<void> {
    await this.fileInput.setInputFiles({
      name,
      mimeType: 'text/csv',
      buffer: Buffer.from(csv, 'utf-8'),
    })
  }

  /**
   * One figure of the report, read whole — label and value together.
   *
   * Whole rather than `toContainText(value)`: "2" is contained in "12" and in
   * "2 345", so a figure off by a digit would still pass. The figure's label
   * and its value share a wrapper, which is what makes the pair addressable.
   */
  async expectFigure(label: string, value: string): Promise<void> {
    await expect(
      this.content.getByText(label, { exact: true }).locator('..'),
    ).toHaveText(`${label}${value}`)
  }

  /** A line of the report, by the label the bank wrote. */
  row(label: string): Locator {
    return this.report.getByRole('row').filter({ hasText: label })
  }
}

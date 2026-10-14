import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Opérations récurrentes (MAG-308): the series with what they cost,
 * the attachments waiting for a yes, the series the history suggests, and the
 * catch-up over the history.
 *
 * Everything on the screen is read from the overview Maggie reads too, so the
 * figures asserted here are the ones the API journeys assert — read where the
 * owner reads them.
 */
export class FinanceRecurringPage extends AdminShell {
  readonly table: Locator
  readonly proposals: Locator
  readonly suggestions: Locator
  readonly catchUp: Locator
  /** The catch-up's preview, before it writes. */
  readonly preview: Locator
  /** The preview shown after a series is made from a transaction. */
  readonly historyPreview: Locator
  /** The form of a series, creating or editing. */
  readonly form: Locator

  constructor(page: Page) {
    super(page)
    this.table = this.content.getByRole('table', { name: 'Opérations récurrentes' })
    this.proposals = this.content.getByRole('region', { name: 'Rattachements proposés' })
    this.suggestions = this.content.getByRole('region', { name: 'Suggestions' })
    this.catchUp = this.content.getByRole('button', { name: 'Rattacher l’historique' })
    this.preview = page.getByRole('dialog', { name: 'Rattacher l’historique', exact: true })
    this.historyPreview = page.getByRole('dialog', { name: 'Rattacher l’historique à cette série' })
    this.form = page.getByRole('dialog', { name: /opération récurrente/ })
  }

  /** The screen, whether or not it holds a series: a table, or the invitation to create one. */
  async open(): Promise<void> {
    await this.goto(ROUTES.financeRecurring)
    await expect(
      this.table.or(this.content.getByText('Aucune opération récurrente pour l’instant')),
    ).toBeVisible()
  }

  /** One series of the list, by its label. */
  row(label: string): Locator {
    return this.table.getByRole('row').filter({ hasText: label })
  }

  /** The toggle of a series' row, by the series' label. */
  unrollToggle(label: string): Locator {
    return this.table.getByRole('button', { name: new RegExp(`^(Dérouler|Replier) les transactions de ${label}$`) })
  }

  /** The transactions a series recognised, once its row is unrolled. */
  seriesTransactions(): Locator {
    return this.table.getByRole('table', { name: 'Transactions de la série' })
  }

  /** The « Rattacher » of a proposed line, by the line's label. */
  accept(lineLabel: string): Locator {
    return this.proposals.getByRole('button', { name: `Rattacher ${lineLabel}` })
  }

  /** The « Créer la série » of a suggestion, by its label. */
  createSeries(label: string): Locator {
    return this.suggestions.getByRole('button', { name: `Créer la série ${label}` })
  }

  /** Picks an option of a select of the form (account, category…). */
  async choose(field: RegExp | string, option: string): Promise<void> {
    await this.form.getByRole('combobox', { name: field }).click()
    await this.page.getByRole('option', { name: option, exact: true }).click()
  }
}

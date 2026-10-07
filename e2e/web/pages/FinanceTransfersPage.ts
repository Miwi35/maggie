import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { FinanceAccountsPage } from './FinanceAccountsPage.js'
import { FinanceCategoriesPage } from './FinanceCategoriesPage.js'

/**
 * Internal transfers (MAG-272): the catch-up that pairs them, the badge they
 * carry in an account's list, and the correction on a transaction's own screen.
 *
 * Three screens, one journey. The detection button sits on the rules tab of
 * the categories screen, the badge and the counterpart in the account-scoped
 * list, the correction on the edit screen reached from that list.
 */
export class FinanceTransfersPage extends FinanceAccountsPage {
  readonly detect: Locator
  readonly preview: Locator
  readonly confirmPairs: Locator
  readonly release: Locator

  constructor(page: Page) {
    super(page)
    this.detect = this.content.getByRole('button', { name: 'Détecter les virements internes' })
    this.preview = page.getByRole('dialog', { name: 'Virements internes détectés' })
    this.confirmPairs = this.preview.getByRole('button', { name: /^Marquer ces \d+ paire/ })
    this.release = this.content.getByRole('button', { name: 'Ce n’est pas un virement interne' })
  }

  /** The detection button, wherever the rules tab shows it (a list with or without rules). */
  async openDetection(): Promise<void> {
    const categories = new FinanceCategoriesPage(this.page)
    await categories.open()
    await categories.openTab('Règles de catégorisation')
    await expect(this.detect).toBeVisible()
  }

  /** The badge of a row of the account's list. */
  badge(label: string): Locator {
    return this.row(label).getByText('Virement interne', { exact: true })
  }

  /** Opens a line on its own screen, where the marking can be corrected. */
  async openTransaction(label: string): Promise<void> {
    await this.row(label).getByRole('link', { name: 'Éditer' }).click()
    await expect(this.content.getByRole('button', { name: 'Enregistrer' })).toBeVisible()
  }
}

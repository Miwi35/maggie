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
 * list, the correction on the transaction's sheet reached from that list.
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

  /**
   * The detection button, wherever the rules tab shows it (a list with or
   * without rules).
   *
   * Once the list has answered. While it loads, the toolbar carries the button;
   * when it answers empty, react-admin swaps the whole view for `ListEmpty`,
   * which carries another one. A click that lands between the two runs on the
   * button about to be unmounted: the request goes out, its answer sets the
   * preview on a component that no longer exists, and the dialog never opens
   * (MAG-571, 33 ms between the list's answer and the click).
   */
  async openDetection(): Promise<void> {
    const categories = new FinanceCategoriesPage(this.page)
    await categories.open()
    await categories.openTab('Règles de catégorisation')
    await expect(
      this.content
        .getByRole('heading', { name: "Aucune règle pour l'instant" })
        .or(this.content.getByRole('link', { name: 'Modifier' }).first()),
    ).toBeVisible()
    await expect(this.detect).toBeVisible()
  }

  /** The badge of a row of the account's list. */
  badge(label: string): Locator {
    return this.row(label).getByText('Virement interne', { exact: true })
  }

  /** The « Incidents (n) » tab of an account: the rejected payments, one line each (MAG-375). */
  incidentsTab(count: number): Locator {
    return this.content.getByRole('tab', { name: `Incidents (${count})` })
  }

  /** « Régularisé » on a rejection of the pile (MAG-424). */
  settle(counterparty: string): Locator {
    return this.content.getByRole('button', { name: `Régularisé : ${counterparty}` })
  }

  /** The folded « Régularisés (n) » section under the pile. */
  settledSection(count: number): Locator {
    return this.content.getByRole('button', { name: `Régularisés (${count})` })
  }

  /** « Annuler » on the bar that follows « Régularisé », for a few seconds. */
  get undo(): Locator {
    return this.page.getByRole('button', { name: 'Annuler', exact: true })
  }

  /** The detail of a rejection: its two original operations. */
  get incidentDetail(): Locator {
    return this.page.getByRole('dialog', { name: /rejeté/ })
  }
}

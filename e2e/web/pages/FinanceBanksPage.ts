import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Banques (`/finance/banks`).
 *
 * In the e2e stack the provider is WireMock
 * (`.docker/e2e/wiremock/mappings/enablebanking.json`), so the bank picker,
 * the consent journey and the fetch all stay on the Docker network.
 *
 * What a browser cannot do here is the consent itself: `connect()` hands the
 * window to the bank's own screen, which in the stack is
 * `http://wiremock:8080/…` — a different origin, and every context is cut off
 * from anything that is not the stack's router. The connect → consent →
 * callback round trip is therefore driven over HTTP in
 * `e2e/smoke/smoke.sh` (step 12); what this page object covers is the admin's
 * half: the picker is filled from the real provider, and the fetch button
 * imports and then recognises what it already has.
 */
export class FinanceBanksPage extends AdminShell {
  readonly bankPicker: Locator
  readonly connectButton: Locator
  readonly connectionsCard: Locator
  readonly syncButton: Locator

  constructor(page: Page) {
    super(page)
    this.bankPicker = this.content.getByLabel('Banque')
    this.connectButton = this.content.getByRole('button', { name: 'Connecter' })
    this.connectionsCard = this.content
      .locator('.MuiCard-root')
      .filter({ hasText: 'Banques connectées' })
    // A regex, not the label: the button renames itself "Récupération…" while
    // the request is in flight, so an exact name would stop matching the
    // moment it is pressed — and `toBeEnabled` on a locator that resolves to
    // nothing fails for the wrong reason.
    this.syncButton = this.connectionsCard.getByRole('button', { name: /Récupér/ })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeBanks)
    await expect(this.connectionsCard).toBeVisible()
  }

  /** A connected bank's row, by its name. */
  connection(bankName: string): Locator {
    return this.connectionsCard.locator('.MuiStack-root').filter({ hasText: bankName }).first()
  }

  /**
   * Opens the bank list and returns what the provider offered.
   *
   * Scoped to the autocomplete's own listbox, and that is not a nicety: the
   * country picker beside it is a `SelectProps={{ native: true }}` field, so
   * the page already holds five hidden `<option>` elements — France,
   * Allemagne… — and a bare `getByRole('option')` waits for one of those to
   * become visible, for ever.
   */
  async bankOptions(): Promise<string[]> {
    await this.bankPicker.click()
    const options = this.page.getByRole('listbox').getByRole('option')
    await expect(options.first()).toBeVisible()

    return options.allInnerTexts()
  }

  /**
   * Pulls the movements of every connection, and waits for the button to be
   * usable again — it reads "Récupération…" while the request is in flight, so
   * its own label is the end of the call.
   */
  async fetchMovements(): Promise<void> {
    await this.syncButton.click()
    await expect(this.syncButton).toBeEnabled({ timeout: 30_000 })
  }
}

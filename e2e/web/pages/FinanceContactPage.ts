import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { contactRoute } from './routes.js'

/**
 * Finance → Contacts → a contact (`/contacts/<iri>`), the « fiche contact »
 * (MAG-450): name and notes, then « Ses comptes » — the labels their transfers
 * carry, each with how many transfers it recognises.
 */
export class FinanceContactPage extends AdminShell {
  readonly accounts: Locator
  readonly labels: Locator

  constructor(page: Page) {
    super(page)
    this.accounts = this.content.getByRole('region', { name: 'Ses comptes' })
    this.labels = this.accounts.getByRole('list', { name: 'Libellés liés' })
  }

  async open(contactId: string): Promise<void> {
    await this.goto(contactRoute(contactId))
    await expect(this.accounts).toBeVisible()
  }

  /** One linked label, with its « N virement(s) · dernier le … » line. */
  label(text: string): Locator {
    return this.labels.getByRole('listitem').filter({ hasText: text })
  }
}

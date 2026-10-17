import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { contactRoute } from './routes.js'

/**
 * Finance → Contacts → a contact (`/contacts/<iri>`), the « fiche contact »
 * (MAG-450): name and notes, then « Ses comptes » — the labels their transfers
 * carry, each with how many transfers it recognises — and « En cours », the
 * créances still open with them (MAG-451).
 */
export class FinanceContactPage extends AdminShell {
  readonly accounts: Locator
  readonly labels: Locator
  /** « En cours »: one line per open créance, « te doit 200,00 € ». */
  readonly openDebts: Locator
  /** « Virements affectés » (MAG-455): the transfers that settled a share of theirs. */
  readonly allocatedTransfers: Locator

  constructor(page: Page) {
    super(page)
    this.accounts = this.content.getByRole('region', { name: 'Ses comptes' })
    this.labels = this.accounts.getByRole('list', { name: 'Libellés liés' })
    this.openDebts = this.content.getByRole('region', { name: 'En cours' })
    this.allocatedTransfers = this.content.getByRole('region', { name: 'Virements affectés' })
  }

  async open(contactId: string): Promise<void> {
    await this.goto(contactRoute(contactId))
    await expect(this.accounts).toBeVisible()
  }

  /** One linked label, with its « N virement(s) · dernier le … » line. */
  label(text: string): Locator {
    return this.labels.getByRole('listitem').filter({ hasText: text })
  }

  /** One open créance, by its object: « Gîte » with « te doit 200,00 € ». */
  openDebt(object: string): Locator {
    return this.openDebts.getByRole('listitem').filter({ hasText: object })
  }

  /** One allocated transfer, by its label: « 08/10/2026 · Gîte » with its amount. */
  allocatedTransfer(label: string): Locator {
    return this.allocatedTransfers.getByRole('listitem').filter({ hasText: label })
  }
}

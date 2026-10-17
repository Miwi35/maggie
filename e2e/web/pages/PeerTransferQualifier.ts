import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

/**
 * « Ce virement est » (MAG-453), the same block on the transaction sheet and
 * on the « À trier » card: what a transfer with a person is — for a received
 * one, a recette, a repayment of créances (« Il couvre », the rest « Non
 * affecté ») or money to give back.
 */
export class PeerTransferQualifier {
  readonly root: Locator
  readonly repayment: Locator
  /** « Non affecté : 200,00 €, compté dans tes recettes ». */
  readonly unallocated: Locator

  constructor(
    private readonly page: Page,
    within: Locator,
  ) {
    this.root = within.getByTestId('peer-transfer-qualifier')
    this.repayment = this.root.getByRole('button', { name: 'Un remboursement' })
    this.unallocated = this.root.getByTestId('peer-unallocated')
  }

  /** One créance under « Il couvre », by its object: its label also names the person. */
  share(object: string): Locator {
    return this.root.getByRole('checkbox', { name: object })
  }

  /** The amount put on one créance, as its field shows it: « 200 ». */
  amountFor(object: string): Locator {
    return this.root.getByLabel(`Montant pour ${object} (€)`)
  }

  /** « Un remboursement », then each créance checked: each takes what it may, within the transfer. */
  async cover(objects: string[]): Promise<void> {
    await this.repayment.click()
    for (const object of objects) {
      await this.share(object).check()
      await expect(this.amountFor(object)).toBeVisible()
    }
  }

  /** Saves, waiting for the PUT itself: the block redraws, or the card leaves, from the API's answer. */
  async save(): Promise<void> {
    await Promise.all([
      this.page.waitForResponse(
        (response) => response.url().includes('/allocation') && 'PUT' === response.request().method(),
      ),
      this.root.getByRole('button', { name: 'Enregistrer' }).click(),
    ])
  }
}

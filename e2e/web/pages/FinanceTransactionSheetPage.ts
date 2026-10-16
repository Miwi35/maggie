import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { transactionRoute } from './routes.js'

/**
 * A transaction's sheet (`/transactions/<iri>`, MAG-464), read here for its
 * « Partage » section (MAG-451): my part of a spend, and who owes the rest.
 */
export class FinanceTransactionSheetPage extends AdminShell {
  readonly share: Locator
  readonly sharedSwitch: Locator

  constructor(page: Page) {
    super(page)
    this.share = this.content.getByRole('region', { name: 'Partage' })
    this.sharedSwitch = this.share.getByRole('switch', { name: 'Dépense partagée' })
  }

  async open(transactionId: string): Promise<void> {
    await this.goto(transactionRoute(transactionId))
    await expect(this.sharedSwitch).toBeVisible()
  }

  /** The amount a person owes, as its field shows it: « 200 ». */
  partOf(name: string): Locator {
    return this.share.getByLabel(`Part de ${name} (€)`)
  }

  /**
   * Shares the spend: my part, then each person picked from the contacts —
   * split equally — and saves, waiting for the PUT itself: the section redraws
   * from the API's answer.
   */
  async shareWith(myPartEuros: number, people: string[]): Promise<void> {
    await this.sharedSwitch.click()
    await this.share.getByLabel('Ta part (€)').fill(String(myPartEuros))
    for (const name of people) {
      await this.share.getByRole('button', { name: 'Ajouter une personne' }).click()
      const picker = this.page.getByRole('dialog', { name: 'Qui te doit ?' })
      await picker.getByLabel('Rechercher un contact').fill(name)
      await picker.getByRole('button', { name, exact: true }).click()
      await expect(picker).toBeHidden()
      await expect(this.partOf(name)).toBeVisible()
    }
    await Promise.all([
      this.page.waitForResponse(
        (response) => response.url().includes('/my-share') && 'PUT' === response.request().method(),
      ),
      this.share.getByRole('button', { name: 'Enregistrer le partage' }).click(),
    ])
  }
}

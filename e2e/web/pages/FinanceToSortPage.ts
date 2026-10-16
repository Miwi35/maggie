import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance → À trier (`/finance/to-sort`): the pile of operations waiting for a
 * category, one card at a time, with the whole pile listed beside it.
 */
export class FinanceToSortPage extends AdminShell {
  readonly card: Locator
  readonly pile: Locator
  readonly always: Locator
  readonly otherCategory: Locator
  readonly validate: Locator

  constructor(page: Page) {
    super(page)
    this.card = this.content.getByRole('region', { name: 'Transaction à trier' })
    this.pile = this.content.getByRole('list', { name: "File d'attente" })
    this.always = this.card.getByRole('switch', { name: 'Toujours pour ce marchand' })
    this.otherCategory = this.card.getByRole('button', { name: 'Autre catégorie' })
    this.validate = this.card.getByRole('button', { name: 'Valider' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeToSort)
    await expect(this.card.or(this.content.getByText('Rien à trier'))).toBeVisible()
  }

  /** Brings a line of the pile up as the current card. */
  async select(label: string): Promise<void> {
    await this.pile.getByRole('button', { name: label }).click()
    await expect(this.card.getByText(label, { exact: true })).toBeVisible()
  }

  async chooseCategory(category: string): Promise<void> {
    await this.otherCategory.click()
    await this.card.getByLabel('Catégorie').fill(category)
    await this.page.getByRole('option', { name: category, exact: true }).click()
  }
}

import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Comptes › la revue as an activity (`/finance/review/activity`, MAG-456): full
 * screen, out of the layout — no app bar, no menu, no `page-content`. Everything
 * is addressed from the activity's own root.
 */
export class FinanceReviewActivityPage extends AdminShell {
  readonly root: Locator
  /** The one line of the queue on screen. */
  readonly transaction: Locator
  readonly counter: Locator
  readonly next: Locator
  readonly previous: Locator
  readonly quitButton: Locator
  readonly categoryDialog: Locator

  constructor(page: Page) {
    super(page)
    this.root = page.getByTestId('review-activity')
    this.transaction = this.root.getByRole('region', { name: 'Transaction de la revue' })
    this.counter = this.transaction.getByTestId('review-counter')
    this.next = this.root.getByRole('button', { name: /^(Valider|Passer|Suivant|Terminer) →$/ })
    this.previous = this.root.getByRole('button', { name: '← Précédent' })
    this.quitButton = this.root.getByRole('button', { name: 'Quitter la revue' })
    this.categoryDialog = page.getByRole('dialog', { name: 'Changer la catégorie' })
  }

  /** Opens the activity straight from its route: a relaunch, from wherever. */
  async open(): Promise<void> {
    await this.goto(ROUTES.financeReviewActivity)
    await this.expectReady()
  }

  /** The activity resumed somewhere: a step is on screen, not the loader. */
  async expectReady(): Promise<void> {
    await expect(this.root).toBeVisible()
    await expect(this.root).not.toHaveAttribute('data-step', 'loading')
  }

  /** The label of the line on screen. */
  async currentLabel(): Promise<string> {
    await expect(this.transaction).toBeVisible()

    return (await this.transaction.getByRole('heading', { level: 2 }).innerText()).trim()
  }

  /** « Changer » → a category picked by name → « Qualifier »: the line is filed and the next one comes. */
  async qualifyWith(category: string): Promise<void> {
    await this.transaction.getByRole('button', { name: 'Changer' }).click()
    await expect(this.categoryDialog).toBeVisible()
    await this.categoryDialog.getByLabel('Catégorie').fill(category)
    await this.page.getByRole('option', { name: category, exact: true }).click()
    await this.categoryDialog.getByRole('button', { name: 'Qualifier' }).click()
    await expect(this.categoryDialog).toBeHidden()
  }

  /** Échap, as the keyboard says: back where the review was launched from. */
  async quit(): Promise<void> {
    await this.page.keyboard.press('Escape')
    await expect(this.root).toBeHidden()
  }
}

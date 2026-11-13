import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Agenda → Périodes (`/periods`), and the dialog that creates and edits a period (MAG-816).
 *
 * The dialog is centred, never a side panel: a portal, so it sits outside `content`.
 */
export class PeriodsPage extends AdminShell {
  readonly heading: Locator
  readonly table: Locator
  readonly newButton: Locator
  readonly dialog: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Périodes', level: 1 })
    this.table = this.content.getByTestId('periods-table')
    this.newButton = this.content.getByRole('button', { name: 'Nouvelle période' })
    this.dialog = page.getByRole('dialog')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.periods)
    await expect(this.heading).toBeVisible()
  }

  row(name: string): Locator {
    return this.table.getByTestId('period-row').filter({ hasText: name })
  }

  /** Creates a period from the dialog and waits for the write. */
  async create(name: string): Promise<void> {
    await this.newButton.click()
    await expect(this.dialog).toBeVisible()
    await this.dialog.getByLabel('Nom').fill(name)
    await Promise.all([
      this.page.waitForResponse(
        (response) => response.url().includes('/api/periods') && 'POST' === response.request().method(),
      ),
      this.dialog.getByRole('button', { name: 'Enregistrer' }).click(),
    ])
    await expect(this.dialog).toBeHidden()
  }
}

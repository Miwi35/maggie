import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** Courses → Courses passées (MAG-488): one row per errand, its lines under it. */
export class PastErrandsPage extends AdminShell {
  readonly storeFilter: Locator
  readonly monthFilter: Locator

  constructor(page: Page) {
    super(page)
    this.storeFilter = this.content.getByLabel('Magasin')
    this.monthFilter = this.content.getByLabel('Mois')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.pastErrands)
    await expect(this.storeFilter).toBeVisible()
  }

  /** Narrows the list to one shop, as the owner does from the select. */
  async filterByStore(name: string): Promise<void> {
    await this.storeFilter.click()
    await this.page.getByRole('option', { name, exact: true }).click()
  }

  /** The errand rows, most recent first — the header row left out. */
  rows(): Locator {
    return this.content.locator('tbody tr').filter({ has: this.page.getByRole('cell') })
  }

  /** The lines bought, as the row's expand panel lists them. */
  boughtLines(): Locator {
    return this.content.getByRole('table', { name: 'Articles achetés' })
  }
}

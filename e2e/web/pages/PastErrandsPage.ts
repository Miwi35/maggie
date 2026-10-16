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

  /** The errand rows, most recent first — the header row and the expand panels left out. */
  rows(): Locator {
    return this.content
      .getByRole('table')
      .first()
      .locator(':scope > tbody > tr')
      .filter({ has: this.page.getByRole('cell'), hasNot: this.page.getByRole('table') })
  }

  /** The lines bought, as the row's expand panel lists them. */
  boughtLines(): Locator {
    return this.content.getByRole('table', { name: 'Articles achetés' })
  }

  /**
   * Expands the errand that bought `label` and returns its row. The e2e clock is frozen, so
   * every errand ended in a run shares one date: the newest is not reliably the top row.
   */
  async expandErrandWith(label: string, candidates: Locator = this.rows()): Promise<Locator> {
    await expect(candidates.first(), 'no past errand to open').toBeVisible()
    const count = await candidates.count()
    for (let index = 0; index < count; index++) {
      const row = candidates.nth(index)
      await row.click()
      await expect(this.boughtLines()).toBeVisible()
      if ((await this.boughtLines().getByText(label, { exact: true }).count()) > 0) {
        return row
      }
      await row.click()
      await expect(this.boughtLines()).toHaveCount(0)
    }
    throw new Error(`no past errand bought « ${label} »`)
  }
}

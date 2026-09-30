import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** Courses → Liste de courses. */
export class GroceryListPage extends AdminShell {
  readonly heading: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByText('Ma liste de courses')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.grocery)
    await expect(this.heading).toBeVisible()
  }

  item(label: string): Locator {
    return this.content.getByText(label, { exact: false }).first()
  }

  /**
   * Reloads once, then waits for the item.
   *
   * Call it *after* the write is known to be indexed — `waitForIndexed` on
   * the collection. This view fetches when it mounts and again on a Mercure
   * update, and both can happen before the index caught up, so the page in
   * front of you may be stale even though the data is not. One reload is
   * enough once the index is confirmed; polling `open()` in a loop meant each
   * attempt paid for a full page load and, on a loaded CI runner, the budget
   * went entirely on loading.
   */
  async expectItemEventually(label: string): Promise<void> {
    await this.page.reload()
    await expect(this.heading).toBeVisible()
    await expect(this.item(label)).toBeVisible()
  }
}

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
   * Waits for an item to show up, reloading between attempts.
   *
   * A grocery item is written through Doctrine, dispatched to RabbitMQ and
   * indexed by the worker — and this view is served from Elasticsearch, which
   * refreshes on its own schedule. The page fetches once when it mounts and
   * again on a Mercure update, and both can happen before the index caught up.
   * Reloading is the only way to ask again, and asking once is how a working
   * write gets reported as broken.
   */
  async expectItemEventually(label: string, timeout = 45_000): Promise<void> {
    await expect
      .poll(
        async () => {
          await this.open()

          return this.item(label).isVisible()
        },
        { timeout, intervals: [1_000, 2_000, 3_000], message: `'${label}' never appeared on the grocery list` },
      )
      .toBe(true)
  }
}

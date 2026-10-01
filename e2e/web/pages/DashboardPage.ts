import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The landing page: today, tomorrow, this week, this month. */
export class DashboardPage extends AdminShell {
  readonly heading: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Tableau de bord' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.dashboard)
    await this.expectReady()
  }

  /**
   * Waits until the dashboard has actually loaded its data.
   *
   * The heading renders before the four `getList` calls come back, so
   * asserting on it alone would let a test read an empty page and call it a
   * clean run. The section headings only exist once the digest has rendered.
   */
  async expectReady(): Promise<void> {
    await expect(this.heading).toBeVisible()
    await expect(this.section('Cette semaine')).toBeVisible()
  }

  section(title: string): Locator {
    return this.content.getByRole('heading', { name: title, exact: true })
  }

  /** An event or task line, wherever it sits in the digest. */
  entry(label: string): Locator {
    return this.content.getByText(label, { exact: false }).first()
  }

  /**
   * Every line whose text is exactly `label` — the locator to count, and the only
   * honest way to assert an absence.
   *
   * {@link entry} ends on `.first()`, so `toBeHidden()` on it passes as soon as the
   * *first* match is hidden and says nothing about the rest; and its substring match
   * would find a title inside a longer one. A task leaving the digest is the whole
   * assertion behind "it is done", so it gets a locator that can be counted.
   */
  line(label: string): Locator {
    return this.content.getByText(label, { exact: true })
  }

  /** The checkbox beside a task line, which is how a task is ticked off here. */
  taskCheckbox(title: string): Locator {
    // `has` takes a locator Playwright re-roots on each candidate, so it is built
    // from the page rather than from `content` — an inner locator carrying its own
    // ancestor chain matches nothing.
    return this.content
      .getByRole('listitem')
      .filter({ has: this.page.getByText(title, { exact: true }) })
      .getByRole('checkbox')
  }
}

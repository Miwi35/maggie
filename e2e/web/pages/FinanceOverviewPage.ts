import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { MONTH_LABELS } from './financeLabels.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Vue d'ensemble (`/finance/dashboard`).
 *
 * One read of `GetFinanceDashboard`, drawn as four cards and a chart. Nothing
 * here is stored: the balance is the sum of the accounts, the saving capacity
 * is income minus loan payments minus a measured lifestyle, and each top post
 * is this month against the same post last month. So the journey reads the
 * cards and checks the arithmetic against the fixtures.
 *
 * Cards are reached through the subtitle each one carries — they are unique on
 * the screen and they are what a reader looks for.
 */
export class FinanceOverviewPage extends AdminShell {
  readonly heading: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: "Vue d'ensemble" })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeOverview)
    // Not the heading: it renders before the fetch answers, so waiting on it
    // would let an assertion run against a screen that has no data yet.
    await expect(this.card('Solde de tous les comptes')).toBeVisible()
  }

  /** One card of the dashboard, by the subtitle it is headed with. */
  card(subtitle: string): Locator {
    return this.content.locator('.MuiCard-root').filter({ hasText: subtitle })
  }

  /**
   * A line of "Principaux postes du mois": the category, what it cost this
   * month, and the change against last month.
   *
   * By `data-category`, as on the budget gauges: the posts repeat, and the
   * assertion is that *this* category cost *that* much. Reaching one through
   * the layout would make it "some post cost that much" the day a `Stack` is
   * wrapped around them.
   */
  topPost(categoryName: string): Locator {
    return this.content.locator(`[data-testid="top-post"][data-category="${categoryName}"]`)
  }

  async selectPeriod(year: number, month: number): Promise<void> {
    await this.content.getByLabel('Année').fill(String(year))
    await this.content.getByLabel('Mois').click()
    await this.page.getByRole('option', { name: MONTH_LABELS[month] }).click()
    await expect(this.content.getByLabel('Mois')).toHaveText(MONTH_LABELS[month])
  }
}

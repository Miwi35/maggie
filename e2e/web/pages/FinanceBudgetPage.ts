import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { MONTH_LABELS } from './financeLabels.js'
import { ROUTES } from './routes.js'

/**
 * Finance → Budgets (`/envelopes`).
 *
 * Three things stacked on one route, and the journeys read all three: the
 * day's score banner, the consumption gauges for the chosen period with the
 * roll-over button, and the envelope list itself.
 *
 * Every number on this screen is computed by the API from the seeded
 * transactions — which is what MAG-102 asks to be checked — so the gauges are
 * addressed by their category name and read whole, label and amounts together.
 */
export class FinanceBudgetPage extends AdminShell {
  readonly scoreBanner: Locator
  readonly budgetCard: Locator
  readonly rollOverButton: Locator
  readonly totalLine: Locator

  constructor(page: Page) {
    super(page)
    // The banner is an Alert whose title is the score's own wording; the card
    // below it is the one holding the "Budgets" heading.
    this.scoreBanner = this.content.getByRole('alert').first()
    this.budgetCard = this.content
      .locator('.MuiCard-root')
      .filter({ has: page.getByRole('heading', { name: 'Budgets' }) })
    this.rollOverButton = this.budgetCard.getByRole('button', {
      name: 'Reconduire le mois précédent',
    })
    this.totalLine = this.budgetCard.getByText(/^Total :/)
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.envelopes)
    await expect(this.budgetCard).toBeVisible()
  }

  /**
   * One gauge, with its category, its period and its amounts.
   *
   * Addressed by `data-category` rather than by the amounts it shows: the
   * point of these assertions is that *this* category consumed *that* much,
   * and a `getByText('120,00 € / 200,00 €')` would be just as happy if the two
   * gauges swapped their categories.
   */
  gauge(categoryName: string): Locator {
    return this.budgetCard.locator(`[data-testid="budget-gauge"][data-category="${categoryName}"]`)
  }

  /** The gauges on screen, in the order the panel draws them. */
  async gaugedCategories(): Promise<string[]> {
    return this.budgetCard
      .getByTestId('budget-gauge')
      .evaluateAll((gauges) => gauges.map((gauge) => gauge.getAttribute('data-category') ?? ''))
  }

  /**
   * The envelope list's row for a category *and* a period, below the gauges.
   *
   * Both, not just the category: the list is not filtered by the picker, so
   * one category holds as many rows as it has periods — and the roll-over
   * journey adds one while the others are reading.
   */
  listRow(categoryName: string, period: string): Locator {
    return this.content
      .getByRole('row')
      .filter({ hasText: categoryName })
      .filter({ hasText: period })
  }

  /**
   * Carries the previous period's envelopes into the one on screen, and waits
   * for the call itself.
   *
   * Waiting on the response rather than on the button coming back: the pass is
   * safe to re-run, so the second run changes nothing on screen — and an
   * assertion made while it is still in flight would be reading the state
   * before it, which is the state it is meant to prove unchanged.
   */
  async rollOver(): Promise<void> {
    await Promise.all([
      this.page.waitForResponse(
        (response) => response.url().includes('/api/finance/rollover-envelopes'),
      ),
      this.rollOverButton.click(),
    ])
  }

  /**
   * Moves the period picker, and waits for the panel to have answered for it.
   *
   * The picker drives three independent fetches (score, status, list), so the
   * wait is on the heading the new period produces rather than on a spinner
   * that may already have gone.
   */
  async selectPeriod(year: number, month: number): Promise<void> {
    const picker = this.budgetCard
    await picker.getByLabel('Année').fill(String(year))
    await picker.getByLabel('Mois').click()
    await this.page.getByRole('option', { name: MONTH_LABELS[month] }).click()
    await expect(picker.getByLabel('Mois')).toHaveText(MONTH_LABELS[month])
  }
}

import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Santé › Nutrition (MAG-748): the « Bilan du jour » first — `balance-value`
 * (« +320 kcal »), `balance-eaten` (« Mangé 515 kcal »), `balance-planned`
 * (« Prévu 0 kcal »), `balance-expended` —, the « 7 jours », the « Macros »,
 * then the « Journal »: an intake is `intake-row-<ulid>` (its source in
 * `intake-source`), a planned meal not eaten yet `planned-row-<mealId>` with
 * its « Mangé » button; « Ajouter » last.
 */
export class HealthNutritionPage extends AdminShell {
  readonly balanceCard: Locator
  readonly eaten: Locator
  readonly planned: Locator
  readonly journal: Locator

  constructor(page: Page) {
    super(page)
    this.balanceCard = this.content.getByRole('region', { name: 'Bilan du jour', exact: true })
    this.eaten = this.balanceCard.getByTestId('balance-eaten')
    this.planned = this.balanceCard.getByTestId('balance-planned')
    this.journal = this.content.getByRole('region', { name: 'Journal', exact: true })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.healthNutrition)
    await expect(this.balanceCard).toBeVisible()
  }

  plannedRow(mealId: string): Locator {
    return this.journal.getByTestId(`planned-row-${mealId}`)
  }

  intakeRow(intakeId: string): Locator {
    return this.journal.getByTestId(`intake-row-${intakeId}`)
  }

  /** Presses « Mangé » on a planned meal; resolves with the id of the intake the API recorded. */
  async markEaten(mealId: string): Promise<string> {
    const posted = this.page.waitForResponse(
      (response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/health/food-intakes',
    )
    await this.plannedRow(mealId).getByRole('button', { name: /^Mangé/ }).click()
    const response = await posted
    expect(response.status(), `POST /api/health/food-intakes answered ${response.status()}`).toBe(201)

    return ((await response.json()) as { id: string }).id
  }
}

import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Santé › Suivi (MAG-706): the period, the Poids card and the « Pesées » of
 * the period, with « Ajouter une pesée » on that card.
 *
 * The Poids card's header shows the selected column — today by default —
 * as `weight-label`, `weight-value` (« 72,4 kg »), `weight-trend`, `weight-fat`;
 * each column is a button named « Aujourd’hui : 72,4 kg, tendance 72,1 kg ».
 * A row of « Pesées » is `weighing-row-<ulid>`.
 */
export class HealthSuiviPage extends AdminShell {
  readonly period: Locator
  readonly weightCard: Locator
  readonly weighings: Locator
  readonly addButton: Locator
  readonly form: Locator

  constructor(page: Page) {
    super(page)
    this.period = this.content.getByRole('radiogroup', { name: 'Période' })
    this.weightCard = this.content.getByRole('region', { name: 'Poids', exact: true })
    this.weighings = this.content.getByRole('region', { name: 'Pesées', exact: true })
    this.addButton = this.weighings.getByRole('button', { name: 'Ajouter une pesée', exact: true })
    this.form = page.getByRole('dialog')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.healthSuivi)
    await expect(this.weightCard).toBeVisible()
    await expect(this.addButton).toBeVisible()
  }

  /** The header of the Poids card: the label of the selected column and its weight. */
  get selectedLabel(): Locator {
    return this.weightCard.getByTestId('weight-label')
  }

  get selectedWeight(): Locator {
    return this.weightCard.getByTestId('weight-value')
  }

  /** Today's column, the last one. */
  get todayColumn(): Locator {
    return this.weightCard.getByRole('button', { name: /^Aujourd’hui : / })
  }

  periodChoice(label: '7 j' | '30 j' | '90 j' | '1 an'): Locator {
    return this.period.getByRole('radio', { name: label, exact: true })
  }

  row(measurementId: string): Locator {
    return this.weighings.getByTestId(`weighing-row-${measurementId}`)
  }

  /** Fills « Ajouter une pesée » with a weight, at the time the form proposes (now), and saves; resolves with the id the API gave. */
  async addWeight(weight: string): Promise<string> {
    await this.addButton.click()
    await expect(this.form).toBeVisible()
    await this.form.getByRole('textbox', { name: 'Poids (kg)' }).fill(weight)

    const created = this.page.waitForResponse(
      (response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/body-measurements',
    )
    await this.form.getByRole('button', { name: 'Enregistrer', exact: true }).click()
    const response = await created
    expect(response.status(), `POST /api/body-measurements answered ${response.status()}`).toBe(201)
    await expect(this.form).toBeHidden()
    const { id } = (await response.json()) as { id: string }

    return id.split('/').pop() ?? id
  }
}

import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Santé › Configuration (MAG-707): the « Profil » card (Calculé · Saisi, Sexe,
 * Âge, Taille, Poids, Activité), the day's need beside it — `need-kcal`
 * (« 1 980 kcal ») and `bmr-kcal` (« métabolisme de base 1 648 kcal ») — and
 * the « Activité » card. Each change is one PATCH of /api/health/profile; the
 * figures are saved when their field is left, the choices when picked.
 */
export class HealthConfigurationPage extends AdminShell {
  readonly profile: Locator
  readonly need: Locator
  readonly activity: Locator

  constructor(page: Page) {
    super(page)
    this.profile = this.content.getByRole('region', { name: 'Profil', exact: true })
    this.need = this.content.getByRole('region', { name: 'Besoin du jour', exact: true })
    this.activity = this.content.getByRole('region', { name: 'Activité', exact: true })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.healthConfiguration)
    await expect(this.profile).toBeVisible()
  }

  mode(label: 'Calculé' | 'Saisi'): Locator {
    return this.profile.getByRole('radio', { name: label, exact: true })
  }

  get needKcal(): Locator {
    return this.need.getByTestId('need-kcal')
  }

  get bmrKcal(): Locator {
    return this.need.getByTestId('bmr-kcal')
  }

  /** The PATCH a change sends, answered. */
  private async saved(change: () => Promise<void>): Promise<void> {
    const patched = this.page.waitForResponse(
      (response) => response.request().method() === 'PATCH' && new URL(response.url()).pathname === '/api/health/profile',
    )
    await change()
    const response = await patched
    expect(response.status(), `PATCH /api/health/profile answered ${response.status()}: ${await response.text()}`).toBe(200)
  }

  /** Types a figure (« 38 », « 72 ») in a field of the profile and leaves it with Enter. */
  async type(label: 'Âge' | 'Taille' | 'Poids', figure: string): Promise<void> {
    const field = this.profile.getByRole('textbox', { name: label, exact: true })
    await this.saved(async () => {
      await field.fill(figure)
      await field.press('Enter')
    })
  }

  /** Picks a choice of the profile: Sexe « Homme », Activité « Sédentaire · × 1,2 ». */
  async pick(label: 'Sexe' | 'Activité', option: string): Promise<void> {
    await this.saved(async () => {
      await this.profile.getByRole('combobox', { name: label, exact: true }).selectOption({ label: option })
    })
  }
}

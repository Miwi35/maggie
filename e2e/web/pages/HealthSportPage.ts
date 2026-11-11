import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Santé › Sport (MAG-705): the weekly minutes, the workouts table and the
 * selected workout, with « Ajouter une séance » in the header.
 *
 * A row is `workout-row-<ulid>`, its sheet `workout-detail-<ulid>`; the bars of the chart are images named
 * « Semaine du 19 oct. : 32 min », the current week's being the last one.
 */
export class HealthSportPage extends AdminShell {
  readonly addButton: Locator
  readonly currentWeek: Locator
  readonly form: Locator

  constructor(page: Page) {
    super(page)
    this.addButton = this.content.getByRole('button', { name: 'Ajouter une séance', exact: true })
    this.currentWeek = this.content.getByTestId('weekly-minutes-current')
    this.form = page.getByRole('dialog')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.healthSport)
    await expect(this.addButton).toBeVisible()
    await expect(this.currentWeek).toBeVisible()
  }

  row(workoutId: string): Locator {
    return this.content.getByTestId(`workout-row-${workoutId}`)
  }

  /** Opens the sheet of a workout by its row's title (MAG-775). */
  async select(workoutId: string): Promise<void> {
    await this.row(workoutId).getByRole('button').first().click()
    await expect(this.detail(workoutId)).toBeVisible()
  }

  /** The sheet of the selected workout, `workout-detail-<ulid>`. */
  detail(workoutId: string): Locator {
    return this.content.getByTestId(`workout-detail-${workoutId}`)
  }

  /** The « Exercices » block of a sheet: one `workout-exercise` row per exercise (MAG-775). */
  exercises(workoutId: string): Locator {
    return this.detail(workoutId).getByTestId('workout-exercises')
  }

  /** The minutes of the current week, read off its bar. */
  async currentWeekMinutes(): Promise<number> {
    const name = (await this.currentWeek.getAttribute('aria-label')) ?? ''
    const match = /: (\d+) min$/.exec(name)
    if (!match) throw new Error(`Cannot read the minutes of the current week from « ${name} »`)

    return Number(match[1])
  }

  /** Fills the form of « Ajouter une séance » and saves it; resolves with the id the API gave. */
  async add(workout: { type: string; title: string; startedAt: string; minutes: string; km: string }): Promise<string> {
    await this.addButton.click()
    await expect(this.form).toBeVisible()
    await this.form.getByRole('combobox', { name: 'Type' }).click()
    await this.page.getByRole('option', { name: workout.type, exact: true }).click()
    await this.form.getByRole('textbox', { name: 'Titre' }).fill(workout.title)
    await this.form.getByLabel('Date et heure de début').fill(workout.startedAt)
    await this.form.getByRole('textbox', { name: 'Durée (minutes)' }).fill(workout.minutes)
    await this.form.getByRole('textbox', { name: 'Distance (km)' }).fill(workout.km)

    const created = this.page.waitForResponse(
      (response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/workouts',
    )
    await this.form.getByRole('button', { name: 'Enregistrer', exact: true }).click()
    const response = await created
    expect(response.status(), `POST /api/workouts answered ${response.status()}`).toBe(201)
    const { id } = (await response.json()) as { id: string }

    return id.split('/').pop() ?? id
  }
}

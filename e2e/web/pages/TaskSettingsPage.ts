import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The urgencies that alert, label by stored value — `low` never does. */
export const URGENCIES = { critical: 'Critique', high: 'Haute', normal: 'Normale' } as const

export type Urgency = keyof typeof URGENCIES

/**
 * Settings → Tâches (MAG-407): the alert schedule per urgency and the task
 * order's weights. A form saved as a whole by « Enregistrer ».
 */
export class TaskSettingsPage extends AdminShell {
  readonly heading: Locator
  readonly morningTime: Locator
  readonly saveButton: Locator
  readonly defaultsButton: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Tâches', exact: true })
    this.morningTime = this.content.getByLabel('Heure du matin')
    this.saveButton = this.content.getByRole('button', { name: 'Enregistrer' })
    this.defaultsButton = this.content.getByRole('button', { name: 'Valeurs par défaut' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.taskSettings)
    await this.expectReady()
  }

  async expectReady(): Promise<void> {
    await expect(this.heading).toBeVisible()
    await expect(this.morningTime).toBeVisible()
  }

  daysBefore(urgency: Urgency): Locator {
    return this.content.getByLabel(`${URGENCIES[urgency]} — jours avant l'échéance`)
  }

  dueDay(urgency: Urgency): Locator {
    return this.content.getByLabel(`${URGENCIES[urgency]} — le jour J`)
  }

  overdueEvery(urgency: Urgency): Locator {
    return this.content.getByLabel(`${URGENCIES[urgency]} — en retard, tous les … jours`)
  }

  /** Saves and waits for the confirmation. */
  async save(): Promise<void> {
    await this.saveButton.click()
    await expect(this.page.getByText('Réglages des tâches enregistrés')).toBeVisible()
  }
}

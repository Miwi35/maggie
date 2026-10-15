import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** Settings → Repas (MAG-483): the number of guests a meal is cooked for when it does not say. */
export class MealSettingsPage extends AdminShell {
  readonly heading: Locator
  readonly guests: Locator
  readonly more: Locator
  readonly less: Locator
  readonly saveButton: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Repas', exact: true })
    this.guests = this.content.getByTestId('guests-value')
    this.more = this.content.getByRole('button', { name: /Un convive de plus/ })
    this.less = this.content.getByRole('button', { name: /Un convive de moins/ })
    this.saveButton = this.content.getByRole('button', { name: 'Enregistrer' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.mealSettings)
    await expect(this.heading).toBeVisible()
    await expect(this.guests).toBeVisible()
  }

  /** Steps to `count` with − / +, the way the owner does. */
  async stepTo(count: number): Promise<void> {
    for (;;) {
      const shown = Number(await this.guests.textContent())
      if (shown === count) return
      await (shown < count ? this.more : this.less).click()
    }
  }

  async save(): Promise<void> {
    await this.saveButton.click()
    await expect(this.page.getByText('Réglages des repas enregistrés')).toBeVisible()
  }
}

import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The landing page, « Accueil »: the day selector, then the cards of the modules. */
export class HomePage extends AdminShell {
  readonly trail: Locator
  readonly daySelector: Locator

  constructor(page: Page) {
    super(page)
    this.trail = page.getByRole('navigation', { name: "Fil d'Ariane" })
    this.daySelector = this.content.getByRole('group', { name: 'Jour' })
  }

  async open(day?: 'today' | 'tomorrow'): Promise<void> {
    await this.goto('tomorrow' === day ? `${ROUTES.home}?day=tomorrow` : ROUTES.home)
    await this.expectReady()
  }

  /** The trail names the page and the selector is drawn: the cards bring their own readiness. */
  async expectReady(): Promise<void> {
    await expect(this.trail.getByText('Accueil')).toHaveAttribute('aria-current', 'page')
    await expect(this.daySelector).toBeVisible()
  }

  /** « Aujourd'hui » or « Demain ». */
  day(label: "Aujourd'hui" | 'Demain'): Locator {
    return this.daySelector.getByRole('button', { name: label, exact: true })
  }
}

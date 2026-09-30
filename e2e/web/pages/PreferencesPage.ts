import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** Calendar views the "Vue par défaut" radio group offers, label by stored value. */
export const CALENDAR_VIEWS = { month: 'Mois', week: 'Semaine', day: 'Jour' } as const

export type CalendarView = keyof typeof CALENDAR_VIEWS

/**
 * Settings → Préférences.
 *
 * The harness's real-time reference page, and not by accident: `UserPreference`
 * is one of the few entities served straight from Doctrine rather than from
 * Elasticsearch, so a Mercure update and the refetch it triggers cannot race
 * the search index. On an indexed collection the observing tab would refetch
 * before the worker had indexed anything and read stale data — a flake that
 * says nothing about real-time.
 */
export class PreferencesPage extends AdminShell {
  readonly heading: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Préférences' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.preferences)
    await this.expectReady()
  }

  async expectReady(): Promise<void> {
    await expect(this.heading).toBeVisible()
    await expect(this.calendarView('week')).toBeAttached()
  }

  calendarView(view: CalendarView): Locator {
    return this.content.getByRole('radio', { name: CALENDAR_VIEWS[view] })
  }

  /**
   * Clicks, rather than `check()`.
   *
   * The radio group is controlled by what the server stored: the click sends a
   * PATCH and the button only moves once the response comes back. `check()`
   * asserts the state changed the moment it clicks, so it fails on a round
   * trip it was never waiting for. The assertion belongs in the test, after
   * whatever it is really waiting on.
   */
  async chooseCalendarView(view: CalendarView): Promise<void> {
    await this.calendarView(view).click()
  }

  async expectCalendarView(view: CalendarView): Promise<void> {
    await expect(this.calendarView(view)).toBeChecked()
  }
}

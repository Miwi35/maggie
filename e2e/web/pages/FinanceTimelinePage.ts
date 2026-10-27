import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance › Planification › Indépendance › the 20-year frise (MAG-554, MAG-633): the events of the current
 * scenario — or of the planning without a scenario — over 20 years, a second
 * scenario below it on request, the categories as filters.
 */
export class FinanceTimelinePage extends AdminShell {
  readonly panel: Locator
  readonly currentRow: Locator
  readonly comparedRow: Locator
  readonly selector: Locator

  constructor(page: Page) {
    super(page)
    this.panel = this.content.getByTestId('timeline-panel')
    this.currentRow = this.panel.getByTestId('timeline-row-current')
    this.comparedRow = this.panel.getByTestId('timeline-row-compared')
    this.selector = this.panel.getByLabel('Scénario comparé')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financePlanningIndependence)
    await expect(this.currentRow).toBeVisible()
  }

  /** An event of the current scenario's row, by the id the API gives it. */
  event(id: string): Locator {
    return this.panel.getByTestId(`timeline-event-${id}`)
  }

  comparedEvent(id: string): Locator {
    return this.panel.getByTestId(`timeline-compared-event-${id}`)
  }

  filter(category: string): Locator {
    return this.panel.getByTestId('timeline-filters').getByRole('button', { name: category, exact: true })
  }
}

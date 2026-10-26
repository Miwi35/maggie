import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance › Planification › Placements (MAG-555, own tab since MAG-633): the
 * simulated placements — a table of what each one would be worth, and the curve
 * of the one selected — with and without the capital the projects of the plan
 * take out of it (MAG-556).
 */
export class FinancePlacementsPage extends AdminShell {
  readonly card: Locator
  readonly rows: Locator

  constructor(page: Page) {
    super(page)
    this.card = this.content.getByRole('region', { name: 'Placements', exact: true })
    this.rows = this.card.getByTestId('placement-row')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financePlanningPlacements)
    await expect(this.card.getByRole('heading', { name: 'Placements' })).toBeVisible()
  }

  row(name: string): Locator {
    return this.card.locator(`[data-testid="placement-row"][data-placement="${name}"]`)
  }

  /** The capital a placement would reach at its end, as its row says it. */
  finalCapital(name: string): Locator {
    return this.row(name).getByTestId('placement-final')
  }

  /** The curve and the figures of the selected placement. */
  simulation(): Locator {
    return this.card.getByRole('region', { name: /^Simulation de / })
  }

  /** « Écart à 20 ans : −X € », only when a project leaves capital in the placement. */
  gap(): Locator {
    return this.simulation().getByTestId('placement-gap')
  }

  /** The capital without the withdrawals (dashed) and with them (solid). */
  curveWithout(): Locator {
    return this.simulation().getByTestId('placement-curve-without')
  }

  curveWith(): Locator {
    return this.simulation().getByTestId('placement-curve-with')
  }

  /** One line per withdrawal: the project, the month and the amount. */
  withdrawals(): Locator {
    return this.simulation().getByTestId('placement-withdrawal')
  }

  async create(fields: { name: string; deposit: string; rate: string; years: string; initial?: string }): Promise<void> {
    await this.card.getByRole('button', { name: 'Placement', exact: true }).click()
    const dialog = this.page.getByRole('dialog')
    await dialog.getByLabel(/^Nom/).fill(fields.name)
    if (fields.initial) await dialog.getByLabel('Versement initial (€)').fill(fields.initial)
    await dialog.getByLabel('Versement (€)').fill(fields.deposit)
    await dialog.getByLabel(/^Rendement annuel/).fill(fields.rate)
    await dialog.getByLabel(/^Durée/).fill(fields.years)
    await dialog.getByRole('button', { name: 'Enregistrer' }).click()
    await expect(dialog).toBeHidden()
  }
}

import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance › Planification › Projets (`/finance/projects`, MAG-519): the
 * projects in priority order, each row with a handle that moves it with the
 * mouse (dnd-kit) or the keyboard (Espace, flèches, Espace), then the Gantt of the
 * planning over 36 months under the list (MAG-527).
 */
export class FinanceProjectsPage extends AdminShell {
  readonly rows: Locator
  readonly gantt: Locator

  constructor(page: Page) {
    super(page)
    this.rows = this.content.getByTestId('project-row')
    this.gantt = this.content.getByTestId('project-gantt')
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.financeProjects)
    await expect(this.content.getByRole('heading', { name: 'Projets' })).toBeVisible()
  }

  row(name: string): Locator {
    return this.content.locator(`[data-testid="project-row"][data-project="${name}"]`)
  }

  /** The drag handle of a project, also its keyboard control. */
  handle(name: string): Locator {
    return this.row(name).getByRole('button', { name: new RegExp(`^Déplacer ${name},`) })
  }

  /** The names shown, top first, among `names` — other projects may sit in between. */
  async order(names: string[]): Promise<string[]> {
    const shown = await this.rows.evaluateAll((rows) => rows.map((row) => row.getAttribute('data-project') ?? ''))

    return shown.filter((name) => names.includes(name))
  }

  /** A project's line in the Gantt. */
  ganttRow(name: string): Locator {
    return this.gantt.locator(`[data-testid="gantt-row"][data-project="${name}"]`)
  }

  /** The projects drawn in the Gantt, top first, among `names`. */
  async ganttOrder(names: string[]): Promise<string[]> {
    const shown = await this.gantt
      .getByTestId('gantt-row')
      .evaluateAll((rows) => rows.map((row) => row.getAttribute('data-project') ?? ''))

    return shown.filter((name) => names.includes(name))
  }

  /** The month index (0 = this month) of a project's purchase mark, or null when it is past the horizon. */
  async purchaseAt(name: string): Promise<number | null> {
    const mark = this.ganttRow(name).getByTestId('gantt-purchase')
    if ((await mark.count()) === 0) return null

    return Number(await mark.getAttribute('data-at'))
  }
}

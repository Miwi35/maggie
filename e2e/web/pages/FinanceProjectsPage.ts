import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/**
 * Finance › Planification › Projets (`/finance/projects`, MAG-519): the
 * projects in priority order, each row with a handle that moves it with the
 * mouse (dnd-kit) or the keyboard (Espace, flèches, Espace).
 */
export class FinanceProjectsPage extends AdminShell {
  readonly rows: Locator

  constructor(page: Page) {
    super(page)
    this.rows = this.content.getByTestId('project-row')
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
}

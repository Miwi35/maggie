import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The four tabs of the « Tâches » page, by the word the owner reads (MAG-408). */
export type TasksTab = "Aujourd'hui" | 'À venir' | 'À faire un jour' | 'Faites'

/** The « Tâches » page: « Aujourd'hui », « À venir », « À faire un jour » and « Faites ». */
export class TasksPage extends AdminShell {
  readonly heading: Locator
  readonly quickCreate: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Tâches', exact: true })
    this.quickCreate = this.content.getByRole('textbox', { name: 'Nouvelle tâche' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.tasks)
    await expect(this.heading).toBeVisible()
    // The counts come with the first answer: the tabs are not final before.
    await expect(this.tab("Aujourd'hui")).toHaveText(/\(\d+\)/)
  }

  /** A tab, whatever number it carries: « À venir (3) ». */
  tab(name: TasksTab): Locator {
    return this.content.getByRole('tab', { name: new RegExp(`^${name}( \\(\\d+\\))?$`) })
  }

  async select(name: TasksTab): Promise<void> {
    await this.tab(name).click()
    await expect(this.tab(name)).toHaveAttribute('aria-selected', 'true')
  }

  /** The number on a tab: « À venir (3) » is 3. */
  async count(name: TasksTab): Promise<number> {
    const label = (await this.tab(name).textContent()) ?? ''

    return Number(/\((\d+)\)/.exec(label)?.[1] ?? Number.NaN)
  }

  /** The row of a task in the list on screen. */
  row(title: string): Locator {
    return this.content.getByRole('listitem').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** The checkbox that ticks a task off, or reopens it. */
  checkbox(title: string): Locator {
    return this.row(title).getByRole('checkbox')
  }
}

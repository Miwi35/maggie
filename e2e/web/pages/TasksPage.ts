import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The four tabs of the « Tâches » page, by the word the owner reads (MAG-408). */
export type TasksTab = "Aujourd'hui" | 'À venir' | 'À faire un jour' | 'Faites'

/**
 * The « Tâches » page: « Aujourd'hui » — the day of five tasks at most, its
 * « Maintenant » and its folded reserve (MAG-497) — then « À venir », « À faire
 * un jour » and « Faites ».
 */
export class TasksPage extends AdminShell {
  readonly heading: Locator
  readonly quickCreate: Locator
  /** The card of the first task of the day not done. */
  readonly now: Locator
  /** The day's progress, named « 1 sur 5 ». */
  readonly ring: Locator
  /** The day's slots, in order. */
  readonly slots: Locator
  /** « Réserve (N) », the button that folds and unfolds the reserve. */
  readonly reserveToggle: Locator
  /** The reserve, once unfolded. */
  readonly reserve: Locator
  /** « Ajouter une tâche », the day's own quick create. */
  readonly dayCreate: Locator
  /** « Ta journée » (MAG-498): the morning's proposal, which replaces the day until confirmed or put off. */
  readonly morning: Locator
  /** The five proposed, numbered, in order. */
  readonly proposed: Locator
  /** « ≈ 55 min · 2 h 30 libres ». */
  readonly morningTime: Locator
  /** « C'est parti »: writes the five kept. */
  readonly go: Locator
  /** « Plus tard »: leaves the morning, the day as it is. */
  readonly later: Locator
  /** « Changer », on the day: the proposal on demand. */
  readonly change: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { name: 'Tâches', exact: true })
    this.quickCreate = this.content.getByRole('textbox', { name: 'Nouvelle tâche' })
    this.now = this.content.getByRole('region', { name: 'Maintenant' })
    this.ring = this.content.getByRole('img', { name: /^\d+ sur \d+$/ })
    this.slots = this.content.getByRole('list', { name: 'Mes 5' })
    this.reserveToggle = this.content.getByRole('button', { name: /^Réserve \(\d+\)$/ })
    this.reserve = this.content.getByRole('list', { name: 'Réserve' })
    this.dayCreate = this.content.getByRole('textbox', { name: 'Ajouter une tâche' })
    this.morning = this.content.getByRole('region', { name: 'Ta journée' })
    this.proposed = this.morning.getByRole('list', { name: 'Ta journée' })
    this.morningTime = this.morning.getByText(/^≈ .+ libres$/)
    this.go = this.morning.getByRole('button', { name: "C'est parti" })
    this.later = this.morning.getByRole('button', { name: 'Plus tard' })
    this.change = this.content.getByRole('button', { name: 'Changer', exact: true })
  }

  /**
   * The page on « Aujourd'hui », the day shown. The morning (MAG-498) is offered once per load
   * while the day is unconfirmed and replaces the day: a journey about the day puts it off.
   */
  async open(): Promise<void> {
    await this.openMorning()
    if (await this.later.isVisible()) {
      await this.later.click()
    }
    await expect(this.dayCreate).toBeVisible()
  }

  /** The page once « Ta journée » or the day is on screen — whichever the state of the day calls for. */
  async openMorning(): Promise<void> {
    await this.goto(ROUTES.tasks)
    await expect(this.heading).toBeVisible()
    // The counts come with the first answer: the tabs are not final before.
    await expect(this.tab("Aujourd'hui")).toHaveText(/\(\d+\)/)
    await expect(this.later.or(this.dayCreate)).toBeVisible()
  }

  /** The row of `title` in the proposal. */
  proposedRow(title: string): Locator {
    return this.proposed.getByRole('listitem').filter({ has: this.page.getByText(title, { exact: true }) })
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

  /** The slot of the day that holds `title`. */
  slot(title: string): Locator {
    return this.slots.getByRole('listitem').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** The row of `title` in the unfolded reserve. */
  reserveRow(title: string): Locator {
    return this.reserve.getByRole('listitem').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** Unfolds the reserve, folded on every visit. */
  async openReserve(): Promise<void> {
    await expect(this.reserveToggle).toBeVisible()
    if ((await this.reserveToggle.getAttribute('aria-expanded')) !== 'true') {
      await this.reserveToggle.click()
    }
    await expect(this.reserveToggle).toHaveAttribute('aria-expanded', 'true')
    await expect(this.reserve).toBeVisible()
  }

  /** The number on « Réserve (N) ». */
  async reserveCount(): Promise<number> {
    const label = (await this.reserveToggle.textContent()) ?? ''

    return Number(/\((\d+)\)/.exec(label)?.[1] ?? Number.NaN)
  }
}

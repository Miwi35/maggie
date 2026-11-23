import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'

export const BILAN_ROUTE = '/activites/bilan'

/** The evening review as an activity (`/activites/bilan`, MAG-961): full screen, out of the layout. */
export class BilanActivityPage extends AdminShell {
  readonly root: Locator
  readonly counter: Locator
  readonly heading: Locator
  readonly nextButton: Locator
  readonly quitButton: Locator
  readonly stepsNav: Locator
  readonly dayTotal: Locator

  constructor(page: Page) {
    super(page)
    this.root = page.getByTestId('activity-shell')
    this.counter = this.root.getByText(/^Étape \d sur 5$/)
    this.heading = this.root.getByRole('heading', { level: 2 })
    this.nextButton = this.root.getByRole('button', { name: /^(Suivant|Bonne nuit)$/ })
    this.quitButton = this.root.getByRole('button', { name: 'Quitter l’activité' })
    this.stepsNav = this.root.getByRole('navigation', { name: 'Étapes du bilan' })
    this.dayTotal = this.root.getByTestId('bilan-day-total')
  }

  async open(): Promise<void> {
    await this.goto(BILAN_ROUTE)
    await expect(this.heading).toBeVisible()
  }

  step(name: string): Locator {
    return this.stepsNav.getByRole('button', { name: new RegExp(name) })
  }

  /** The Finance step's line of a period: « 186 € / 320 € » and the time gone. */
  rhythm(period: 'Semaine' | 'Mois'): Locator {
    return this.root.getByTestId(`bilan-rhythm-${period.toLowerCase()}`)
  }

  /** A task line of the Demain step's « Tâches prévues » card. */
  plannedTask(title: string): Locator {
    return this.root.getByTestId('bilan-planned-task').filter({ hasText: title })
  }

  async swap(title: string): Promise<void> {
    await this.root.getByRole('button', { name: `Échanger « ${title} » contre une autre tâche` }).click()
  }

  async next(): Promise<void> {
    await this.nextButton.click()
  }

  async quit(): Promise<void> {
    await this.quitButton.click()
  }

  async confirmQuit(): Promise<void> {
    await this.page.getByRole('button', { name: 'Quitter', exact: true }).click()
    await expect(this.root).toBeHidden()
  }
}

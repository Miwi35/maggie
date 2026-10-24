import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The landing page, « Accueil »: the day selector, then the cards of the modules. */
export class HomePage extends AdminShell {
  readonly trail: Locator
  readonly daySelector: Locator
  readonly groceryCard: Locator
  /** The ring of the Tâches card, named by what it reads: « 1 sur 3 ». */
  readonly tasksRing: Locator
  readonly financeCard: Locator

  constructor(page: Page) {
    super(page)
    this.trail = page.getByRole('navigation', { name: "Fil d'Ariane" })
    this.daySelector = this.content.getByRole('group', { name: 'Jour' })
    this.groceryCard = this.content.getByTestId('home-card-grocery')
    this.tasksRing = this.content.getByTestId('home-tasks-ring')
    this.financeCard = this.content.getByTestId('home-card-finance')
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

  /** The tick of a task (or of a routine) in the Tâches card, named « {title} : fait ». */
  tick(title: string): Locator {
    return this.content.getByRole('checkbox', { name: `${title} : fait`, exact: true })
  }

  /** The Agenda card's list of the day. */
  get agendaList(): Locator {
    return this.content.getByRole('list', { name: 'Événements du jour' })
  }

  /** A row of the Agenda card, by the title of its event. */
  agendaRow(title: string): Locator {
    return this.agendaList.getByRole('button', { name: new RegExp(title) })
  }

  /** The « maintenant · HH:MM » line of the Agenda card. */
  get nowLine(): Locator {
    return this.agendaList.getByRole('separator', { name: /^maintenant · \d{2}:\d{2}$/ })
  }

  /** The fiche of the event touched in the Agenda card. */
  eventPanel(title: string): Locator {
    return this.page.getByRole('region', { name: `Fiche : ${title}` })
  }
}

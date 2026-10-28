import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { ROUTES } from './routes.js'

/** The views of « Tâches », each a route of its own and a sub-entry of the menu (MAG-644), « Routines » reached from the page (MAG-511). */
export type TasksView = "Aujourd'hui" | 'À venir' | 'Un jour' | 'Faites' | 'Routines'

/** The views that sit in the menu with a number. */
export type CountedView = Exclude<TasksView, 'Routines'>

const PATHS: Record<TasksView, string> = {
  "Aujourd'hui": ROUTES.tasks,
  'À venir': ROUTES.tasksUpcoming,
  'Un jour': ROUTES.tasksSomeday,
  Faites: ROUTES.tasksDone,
  Routines: ROUTES.tasksRoutines,
}

/** The groups of « Routines »: the part of the day for the daily ones, then the rhythm (MAG-511). */
export type RoutineGroup = 'Matin' | 'Après-midi' | 'Soir' | 'Chaque semaine' | 'Chaque mois'

/**
 * The « Tâches » page: « Aujourd'hui » — the day of five tasks at most with its « Maintenant » (MAG-497),
 * and the reserve in the same full-width list — then « À venir », « Un jour » and « Faites », each its
 * own route with the menu's sub-entry and number (MAG-644).
 */
export class TasksPage extends AdminShell {
  /** The h1 of the view: « Aujourd'hui », « À venir »… */
  readonly heading: Locator
  /** « Tâches · jeudi 15 octobre », above the list. */
  readonly title: Locator
  /** « Ajouter une tâche »: « Appeler le garage demain » creates a task due tomorrow (MAG-644). */
  readonly quickAdd: Locator
  /** « Routines », the link to the routines from the page's header. */
  readonly routinesLink: Locator
  /** The sheet of a task, a 420 px panel beside the list (MAG-644). */
  readonly sheet: Locator
  /** The card of the first task of the day not done. */
  readonly now: Locator
  /** The day's progress, named « 1 sur 5 ». */
  readonly ring: Locator
  /** The day's rows, in order: the slots, then the reserve — one list, « En retard » on top when there is one. */
  readonly dayRows: Locator
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
  /** « Le soir » (MAG-500): the review, which replaces the day after the evening time until « Bonne nuit ». */
  readonly evening: Locator
  /** What is done, listed first. */
  readonly eveningDone: Locator
  /** Each task left, with its three ways out. */
  readonly eveningLeft: Locator
  /** « Bonne nuit »: records what helped and closes. */
  readonly goodNight: Locator
  /** « Plus tard »: closes the review without recording it. */
  readonly eveningLater: Locator
  /** « Le soir », on the day: the review on demand. */
  readonly openEveningButton: Locator
  /** The bravo of a task done (MAG-508), over the day: named « {titre} · faite ». */
  readonly bravo: Locator
  /** The reward's message, from a written bank. */
  readonly bravoMessage: Locator
  /** « Pause méritée »: back to the day. */
  readonly pause: Locator
  /** The constellation of the month (MAG-509), in the bravo or in the evening: « Ta constellation d'octobre · 5 étoiles ». */
  readonly constellationOfBravo: Locator
  readonly constellationOfEvening: Locator
  /** « Nouvelle routine », on « Routines » (MAG-511). */
  readonly newRoutine: Locator
  /** The routine's form, new or opened from its card: titled « Nouvelle routine » or by the routine. */
  readonly routineDialog: Locator

  constructor(page: Page) {
    super(page)
    this.heading = this.content.getByRole('heading', { level: 1 })
    this.title = this.content.getByText(/^Tâches · /)
    this.quickAdd = this.content.getByRole('textbox', { name: 'Ajouter une tâche' })
    this.routinesLink = this.content.getByRole('link', { name: 'Routines', exact: true })
    this.sheet = page.getByRole('complementary', { name: 'Fiche de la tâche' })
    this.now = this.content.getByRole('region', { name: 'Maintenant' })
    this.ring = this.content.getByRole('img', { name: /^\d+ sur \d+$/ })
    this.dayRows = this.content.getByRole('list', { name: /^(En retard|Aujourd'hui)$/ }).getByRole('listitem')
    this.morning = this.content.getByRole('region', { name: 'Ta journée' })
    this.proposed = this.morning.getByRole('list', { name: 'Ta journée' })
    this.morningTime = this.morning.getByText(/^≈ .+ libres$/)
    this.go = this.morning.getByRole('button', { name: "C'est parti" })
    this.later = this.morning.getByRole('button', { name: 'Plus tard' })
    this.change = this.content.getByRole('button', { name: 'Changer', exact: true })
    this.evening = this.content.getByRole('region', { name: 'Le soir' })
    this.eveningDone = this.evening.getByRole('list', { name: 'Fait' })
    this.eveningLeft = this.evening.getByRole('list', { name: 'Reste' })
    this.goodNight = this.evening.getByRole('button', { name: 'Bonne nuit', exact: true })
    this.eveningLater = this.evening.getByRole('button', { name: 'Plus tard', exact: true })
    this.openEveningButton = this.content.getByRole('button', { name: 'Le soir', exact: true })
    // A dialog is drawn outside the page's content.
    this.bravo = page.getByRole('dialog', { name: / · faite$/ })
    this.bravoMessage = this.bravo.getByTestId('bravo-message')
    this.pause = this.bravo.getByRole('button', { name: 'Pause méritée', exact: true })
    this.constellationOfBravo = this.bravo.getByTestId('constellation')
    this.constellationOfEvening = this.evening.getByTestId('constellation')
    this.newRoutine = this.content.getByRole('button', { name: 'Nouvelle routine', exact: true })
    this.routineDialog = page.getByRole('dialog')
  }

  /** The page on « Routines », by its own route: the morning or the evening are offered on « Aujourd'hui » only. */
  async openRoutines(): Promise<void> {
    await this.goto(ROUTES.tasksRoutines)
    await expect(this.heading).toHaveText('Routines')
    await expect(this.newRoutine).toBeVisible()
  }

  /** A group of « Routines », as a region named by its heading. */
  routineGroup(name: RoutineGroup): Locator {
    return this.content.getByRole('region', { name, exact: true })
  }

  /** The card of `title` among the routines. */
  routineCard(title: string): Locator {
    return this.content.getByTestId('routine-card').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** The round tick of `title` for today — only on a day it is due. */
  routineCheck(title: string): Locator {
    return this.routineCard(title).getByRole('checkbox', { name: `${title} : fait aujourd'hui`, exact: true })
  }

  /** « ce mois : 3 fois » or « depuis août : 9 fois », on the card of `title`. */
  routineCount(title: string): Locator {
    return this.routineCard(title).getByTestId('routine-count')
  }

  /**
   * The page on « Aujourd'hui », the day shown. The morning (MAG-498) and the evening (MAG-500) are
   * offered once per load and replace the day: a journey about the day puts them off.
   */
  async open(): Promise<void> {
    await this.openMorning()
    if (await this.later.isVisible()) {
      await this.later.click()
    }
    if (await this.eveningLater.isVisible()) {
      await this.eveningLater.click()
    }
    await expect(this.dayReady).toBeVisible()
  }

  /** The day is drawn: a row of it, or the word that says it is empty. */
  get dayReady(): Locator {
    return this.dayRows.first().or(this.content.getByText("Rien à faire aujourd'hui"))
  }

  /** The page once « Ta journée », « Le soir » or the day is on screen — whichever the hour and the day call for. */
  async openMorning(): Promise<void> {
    await this.goto(ROUTES.tasks)
    await expect(this.heading).toHaveText("Aujourd'hui")
    await expect(this.later.or(this.eveningLater).or(this.dayReady)).toBeVisible()
  }

  /** The star a task done carries in the day's list (MAG-509). */
  starOf(title: string): Locator {
    return this.slot(title).getByTestId('task-star')
  }

  /** « Suivante : {titre} … », the next task of the day from the bravo. */
  next(title: string): Locator {
    return this.bravo.getByRole('button', { name: new RegExp(`^Suivante : ${title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`) })
  }

  /** The row of `title` among the tasks left in the evening. */
  leftRow(title: string): Locator {
    return this.eveningLeft.getByRole('listitem').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** The way out of `title` in the evening: « Demain », « Plus tard » or « Mettre de côté ». */
  outcome(title: string, label: 'Demain' | 'Plus tard' | 'Mettre de côté'): Locator {
    return this.leftRow(title).getByRole('button', { name: `${title} : ${label}`, exact: true })
  }

  /** The row of `title` in the proposal. */
  proposedRow(title: string): Locator {
    return this.proposed.getByRole('listitem').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** The sub-entry of the menu for a view, whatever number it carries: « À venir 3 ». */
  menuEntry(view: CountedView): Locator {
    return this.page.getByRole('menuitem', { name: new RegExp(`^${view}\\s*\\d*$`) })
  }

  /** Goes to a view the way the owner does: « Routines » by the link of the page, the others by the menu. */
  async select(view: TasksView): Promise<void> {
    if (view === 'Routines') {
      await this.routinesLink.click()
    } else {
      await this.openMenu()
      await this.menuEntry(view).click()
    }

    await expect(this.page).toHaveURL(new RegExp(`#${PATHS[view]}$`))
    await expect(this.heading).toHaveText(view)
  }

  /** The number on the menu's entry: « À venir 3 » is 3. Waits for the menu to have its numbers. */
  async count(view: CountedView): Promise<number> {
    await this.openMenu()
    const label = (await this.menuEntry(view).getByTestId('menu-count').textContent()) ?? ''

    return Number(label)
  }

  /** The row of a task in the list on screen. */
  row(title: string): Locator {
    return this.content.getByRole('listitem').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** The checkbox that ticks a task off, or reopens it. */
  checkbox(title: string): Locator {
    return this.row(title).getByRole('checkbox')
  }

  /** The row of the day that holds `title`, slot or reserve. */
  slot(title: string): Locator {
    return this.dayRows.filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** The Focus of `title` (MAG-499): its first step, its ten-minute ring, its petits pas. */
  focus(title: string): Locator {
    return this.content.getByRole('region', { name: title, exact: true })
  }

  /** The Focus's ring, which reads the time left: « 10:00 ». */
  get timer(): Locator {
    return this.content.getByRole('timer', { name: 'Minuteur' })
  }
}

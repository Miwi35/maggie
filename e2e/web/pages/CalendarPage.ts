import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { adminUrl, ROUTES } from './routes.js'

/** The three widths of the view switcher, by the label on its button. */
export type CalendarViewName = 'Mois' | 'Semaine' | 'Jour'

/** Which occurrences a change to a recurring event applies to. */
export type RecurrenceScope = 'Cet événement' | 'Cet événement et tous les suivants' | 'Tous les événements'

export interface NewEvent {
  summary: string
  /** `YYYY-MM-DDTHH:mm`, or `YYYY-MM-DD` when `allDay`. */
  start: string
  end: string
  allDay?: boolean
  /** The agenda's name as the sidebar spells it — "Perso", "Famille". */
  agenda?: string
  /** A label of the "Récurrence" select, e.g. "Toutes les semaines". */
  recurrence?: string
  /** How many occurrences, when `recurrence` is set. Leaves the series open when omitted. */
  occurrences?: number
  /** A label of the "Rappel" select, e.g. "1 heure avant". No reminder when omitted. */
  reminder?: string
  /** A label of the "Statut" select — "Provisoire". Confirmed when omitted. */
  status?: string
}

export interface NewTask {
  title: string
  /** `YYYY-MM-DD`. Omit for a task with no due date. */
  dueDate?: string
  /** A label of the "Criticité" select — "Basse", "Moyenne", "Haute", "Critique". */
  criticality?: string
}

/**
 * Agenda — the FullCalendar grid, its sidebar and the dialogs around it.
 *
 * Three things about this page object are load-bearing.
 *
 * {@link openEvent} is the only honest way to reach a particular event. A journey
 * cannot assume the result of a write lands in the month the calendar happens to
 * be showing, and driving the toolbar to some other date would be a test of the
 * toolbar. The admin already has the answer: search results deep-link with
 * `?eventId=`, and that path navigates the grid to the event's own date and opens
 * its detail card. Following it asserts the write *and* the one navigation an
 * owner really uses to reach it. Everything else — the view switcher, the next
 * day — moves relative to wherever that left the grid, so no journey computes a
 * date from the wall clock.
 *
 * The chips are addressed through FullCalendar's own class names. It renders no
 * roles and no accessible names, so there is nothing else to hold on to; the
 * classes are part of its documented theming API and have outlived several of its
 * major versions.
 *
 * The sidebar — "Créer", the mini calendar, the agenda list, "Ajouter" — is
 * `display: none` below MUI's `md` breakpoint. Every journey using it therefore
 * runs on the `desktop` project only, which is the default: `@responsive` is
 * what opts a test into the narrow widths, and nothing here asks for it.
 */
export class CalendarPage extends AdminShell {
  /** The whole FullCalendar root — chips, columns, day cells. */
  readonly grid: Locator
  /** The toolbar's own title: "octobre 2026", "5 – 11 oct. 2026", a full date in day view. */
  readonly title: Locator
  readonly createButton: Locator
  readonly createMenu: Locator
  /** The detail card. A Popover in a portal, so it sits outside `content`. */
  readonly popover: Locator
  readonly createEventDialog: Locator
  readonly editEventDialog: Locator
  readonly createTaskDialog: Locator
  readonly editTaskDialog: Locator
  readonly recurrenceDialog: Locator
  readonly importDialog: Locator
  private readonly readyAgenda: string

  /**
   * @param readyAgenda an agenda the signed-in user owns, which {@link expectReady}
   *   waits for in the sidebar. "Perso" is the owner's; a journey signed in as the
   *   second seeded account passes "Agenda du voisin", since it has no "Perso".
   */
  constructor(page: Page, readyAgenda = 'Perso') {
    super(page)
    this.readyAgenda = readyAgenda
    this.grid = page.locator('.fc')
    // The toolbar's title is the page's only level-6 heading.
    this.title = this.content.getByRole('heading', { level: 6 }).first()
    this.createButton = this.content.getByRole('button', { name: 'Créer', exact: true })
    this.createMenu = this.content.getByRole('button', { name: 'Options de création' })
    this.popover = page.locator('.MuiPopover-paper')
    this.createEventDialog = page.getByRole('dialog', { name: 'Nouvel événement' })
    // `exact`, because "Modifier l'événement récurrent" contains this name and
    // the two dialogs are open one after the other in every recurrence journey.
    this.editEventDialog = page.getByRole('dialog', { name: "Modifier l'événement", exact: true })
    this.createTaskDialog = page.getByRole('dialog', { name: 'Nouvelle tâche' })
    this.editTaskDialog = page.getByRole('dialog', { name: 'Modifier la tâche' })
    // One dialog, two titles: "Modifier" on a change, "Supprimer" on a deletion.
    this.recurrenceDialog = page.getByRole('dialog', { name: /l'événement récurrent$/ })
    this.importDialog = page.getByRole('dialog', { name: 'Importer depuis Google Calendar' })
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.calendar)
    await this.expectReady()
  }

  /**
   * The grid is up *and* it has its data.
   *
   * The sidebar's agenda list is the signal, not an event chip: it only renders
   * once `getList('agendas')` came back, and unlike a chip it is there whatever
   * date the grid is showing. Waiting on a chip would make this method unusable
   * on an empty month, which is half of what the recurrence journeys look at.
   */
  async expectReady(): Promise<void> {
    await this.expectLoaded()
    await expect(this.grid).toBeVisible()
    await expect(this.agendaRow(this.readyAgenda)).toBeVisible()
  }

  /**
   * Opens an event by its ULID and waits for its detail card to carry `title`.
   *
   * The `eventId` parameter takes the IRI react-admin knows the record by, so
   * the ULID is expanded here. The global search builds the same IRI from the
   * identifier Elasticsearch returns (MAG-144) — `search.spec.ts` follows that
   * route end to end.
   *
   * The card is drawn 300 ms after the event comes back, to let the grid
   * finish moving; `toBeVisible` covers that without anyone sleeping.
   */
  async openEvent(id: string, title: string): Promise<void> {
    const recordId = encodeURIComponent(id.startsWith('/') ? id : `/api/events/${id}`)

    await this.page.goto(adminUrl(`${ROUTES.calendar}?eventId=${recordId}`))
    await this.expectReady()
    await expect(this.detailTitle(title), 'the calendar never opened the event').toBeVisible()
  }

  /**
   * Navigates the grid to an event's date without leaving anything open.
   *
   * Same deep link as {@link openEvent}, then the card is dismissed — what a
   * journey wants when the assertion is about the grid rather than the card.
   */
  async goToEventDate(id: string, title: string): Promise<void> {
    await this.openEvent(id, title)
    await this.closePopover()
  }

  /**
   * The title on the detail card.
   *
   * A heading, and that matters: the card is a Popover rendered in a portal,
   * so it sits outside `content` — and Maggie quotes the event's name in the
   * chat panel a moment earlier, which an unscoped `getByText` would match too.
   */
  detailTitle(title: string): Locator {
    return this.page.getByRole('heading', { name: title, exact: true })
  }

  // ---------------------------------------------------------------------------
  // Moving around
  // ---------------------------------------------------------------------------

  async chooseView(view: CalendarViewName): Promise<void> {
    const button = this.content.getByRole('button', { name: view, exact: true })
    await button.click()
    // The pressed button is what says the switch took effect — FullCalendar has
    // redrawn and refetched by the time react-admin re-rendered the toolbar.
    await expect(button).toHaveClass(/MuiButton-contained/)
  }

  /** The view whose toolbar button is the pressed one. */
  async expectView(view: CalendarViewName): Promise<void> {
    await expect(this.content.getByRole('button', { name: view, exact: true })).toHaveClass(/MuiButton-contained/)
  }

  /** Forward one month, week or day, depending on the view. */
  async goForward(): Promise<void> {
    await this.step(/suivant/)
  }

  /** Back one month, week or day, depending on the view. */
  async goBack(): Promise<void> {
    await this.step(/précédent/)
  }

  /**
   * Clicks one of the two arrows and waits for the title to change.
   *
   * The title is the one thing every view updates, and waiting on it is what
   * stops the next assertion from reading the page the grid is about to replace.
   */
  private async step(label: RegExp): Promise<void> {
    const before = await this.title.innerText()
    await this.content.getByRole('button', { name: label }).click()
    await expect(this.title).not.toHaveText(before)
  }

  // ---------------------------------------------------------------------------
  // The chips on the grid
  // ---------------------------------------------------------------------------

  /**
   * Every chip on the grid whose title is exactly `title` — the locator to count.
   *
   * Counting is what the recurrence journeys are about: a series renders one chip
   * per occurrence in the visible range, and an overridden occurrence has to
   * replace exactly one of them rather than add to them.
   *
   * By text rather than by class: FullCalendar puts the title in its own node and
   * the time in another, so an exact text match lands on the title alone — and it
   * is the same handle the component's own unit tests use (`findAllByText`),
   * which keeps the two from drifting apart over a theming change.
   *
   * Month view collapses a day holding more than three events behind
   * "+N autres", so count in the week or day view when the number is the
   * assertion.
   */
  chip(title: string): Locator {
    return this.grid.getByText(title, { exact: true })
  }

  /** The chips of `title` the grid draws as tentative (MAG-246) — a class of ours, not FullCalendar's. */
  tentativeChip(title: string): Locator {
    return this.grid.locator('.fc-event.event-tentative').filter({ hasText: title })
  }

  /** The chips on one day, whatever the view — several `td` carry the date in week view. */
  chipsOnDay(isoDate: string, title: string): Locator {
    return this.grid.locator(`td[data-date="${isoDate}"]`).getByText(title, { exact: true })
  }

  /**
   * The bar FullCalendar draws for `title` — the `<a>` around the chip's text,
   * whose width is how many days it covers in the month view.
   */
  eventBar(title: string): Locator {
    return this.grid.locator('.fc-event').filter({ has: this.page.getByText(title, { exact: true }) })
  }

  /** One day's cell in the month view. */
  monthCell(isoDate: string): Locator {
    return this.grid.locator(`td.fc-daygrid-day[data-date="${isoDate}"]`)
  }

  /**
   * How many month-view cells the bar of `title` spans, measured on screen.
   *
   * FullCalendar draws a bar of several days as one element laid across the cells,
   * so counting chips per day says nothing: the width is the answer. Rounded,
   * because the bar sits a couple of pixels inside its cells.
   */
  async monthCellsSpanned(title: string, isoDate: string): Promise<number> {
    const bar = await this.eventBar(title).first().boundingBox()
    const cell = await this.monthCell(isoDate).boundingBox()
    if (!bar || !cell) {
      throw new Error(`"${title}" or the cell of ${isoDate} is not on screen`)
    }

    return Math.round(bar.width / cell.width)
  }

  /** Clicks a chip to open its detail card. `index` picks an occurrence of a series. */
  async openChip(title: string, index = 0): Promise<void> {
    await this.chip(title).nth(index).click()
    await expect(this.popover).toBeVisible()
  }

  async closePopover(): Promise<void> {
    await this.popover.getByRole('button', { name: 'Fermer' }).click()
    await expect(this.popover).toBeHidden()
  }

  /** The pencil on the open detail card. */
  async editFromPopover(): Promise<void> {
    await this.popover.getByRole('button', { name: 'Modifier' }).click()
  }

  /** The bin on the open detail card. */
  async deleteFromPopover(): Promise<void> {
    await this.popover.getByRole('button', { name: 'Supprimer' }).click()
  }

  // ---------------------------------------------------------------------------
  // Creating and changing
  // ---------------------------------------------------------------------------

  /**
   * Fills the "Nouvel événement" dialog from the sidebar's "Créer" and submits.
   *
   * `agenda` is picked by the name the sidebar shows, never by an id: the IRI the
   * dialog sends is exactly what `c359b43` got wrong — `calendarId` already *is*
   * an IRI from the Hydra provider, and wrapping it again doubled the path and
   * broke every creation. A journey passing an id would be writing the bug into
   * the test.
   */
  async createEvent(event: NewEvent): Promise<void> {
    await this.createButton.click()
    const dialog = this.createEventDialog
    await expect(dialog).toBeVisible()

    await dialog.getByLabel(/Résumé/).fill(event.summary)

    // Before the dates: flipping the switch rewrites both fields, and their
    // input type with them.
    if (event.allDay) {
      await dialog.getByRole('switch', { name: 'Journée entière' }).check()
    }

    await dialog.getByLabel(/Début/).fill(event.start)
    await dialog.getByLabel(/Fin/).fill(event.end)

    if (event.recurrence) {
      await this.choose(dialog, 'Récurrence', event.recurrence)

      if (event.occurrences !== undefined) {
        await this.choose(dialog, 'Se termine', 'Après')
        // Two number fields appear once a frequency is chosen: the interval
        // first, the occurrence count after "Après".
        await dialog.getByRole('spinbutton').last().fill(String(event.occurrences))
      }
    }

    if (event.reminder) {
      await this.pickReminder(dialog, event.reminder)
    }

    if (event.status) {
      await this.choose(dialog, 'Statut', event.status)
    }

    if (event.agenda) {
      await this.choose(dialog, 'Calendrier', event.agenda)
    }

    await dialog.getByRole('button', { name: 'Créer' }).click()
    await expect(dialog, 'the create dialog stayed open — the POST was refused').toBeHidden()
  }

  /** Fills the "Nouvelle tâche" dialog from "Créer ▾ → Tâche" and submits. */
  async createTask(task: NewTask): Promise<void> {
    await this.createMenu.click()
    await this.page.getByRole('menuitem', { name: 'Tâche' }).click()

    const dialog = this.createTaskDialog
    await expect(dialog).toBeVisible()
    await dialog.getByLabel(/Titre/).fill(task.title)

    if (task.criticality) {
      await this.choose(dialog, 'Criticité', task.criticality)
    }

    if (task.dueDate) {
      await dialog.getByLabel('Échéance').fill(task.dueDate)
    }

    await dialog.getByRole('button', { name: 'Créer' }).click()
    await expect(dialog, 'the task dialog stayed open — the POST was refused').toBeHidden()
  }

  /**
   * Changes the open "Modifier l'événement" dialog and saves.
   *
   * `reminder` is a delay label to end up with, or `null` to leave none — the
   * form sends the reminders it holds on every save, so what it shows is what
   * the event keeps.
   */
  async submitEventEdit(values: {
    summary?: string
    start?: string
    end?: string
    reminder?: string | null
    status?: string
  }): Promise<void> {
    const dialog = this.editEventDialog
    await expect(dialog).toBeVisible()

    if (values.summary !== undefined) {
      await dialog.getByLabel(/Résumé/).fill(values.summary)
    }
    if (values.start !== undefined) {
      await dialog.getByLabel(/Début/).fill(values.start)
    }
    if (values.end !== undefined) {
      await dialog.getByLabel(/Fin/).fill(values.end)
    }
    if (values.reminder === null) {
      await this.clearReminders(dialog)
    } else if (values.reminder !== undefined) {
      await this.clearReminders(dialog)
      await this.pickReminder(dialog, values.reminder)
    }

    if (values.status !== undefined) {
      await this.choose(dialog, 'Statut', values.status)
    }

    await dialog.getByRole('button', { name: 'Enregistrer' }).click()
    await expect(dialog).toBeHidden()
  }

  /** The reminders line on the open detail card, by the delay it names. */
  reminderOnCard(label: string): Locator {
    return this.popover.getByText(label, { exact: true })
  }

  /** Adds a reminder row and picks `label` in it. */
  private async pickReminder(dialog: Locator, label: string): Promise<void> {
    await dialog.getByRole('button', { name: 'Ajouter un rappel' }).click()
    await this.choose(dialog, 'Rappel', label)
  }

  /**
   * Removes every reminder row the dialog shows.
   *
   * One at a time and re-counted each round: the list re-renders on every
   * removal, so a locator captured before the first click points at nothing.
   */
  private async clearReminders(dialog: Locator): Promise<void> {
    const remove = dialog.getByRole('button', { name: /^Supprimer le rappel/ })

    for (let left = await remove.count(); left > 0; left = await remove.count()) {
      await remove.first().click()
    }
  }

  /**
   * Answers the "cet événement / et les suivants / tous" question.
   *
   * It opens on every change to a recurring event, and which radio is picked is
   * the difference between an exception instance, a truncated RRULE and a moved
   * series — three different writes behind one OK button.
   */
  async chooseRecurrenceScope(scope: RecurrenceScope): Promise<void> {
    const dialog = this.recurrenceDialog
    await expect(dialog).toBeVisible()
    await dialog.getByRole('radio', { name: scope, exact: true }).check()
    await dialog.getByRole('button', { name: 'OK' }).click()
    await expect(dialog).toBeHidden()
  }

  // ---------------------------------------------------------------------------
  // The agenda sidebar
  // ---------------------------------------------------------------------------

  /** A row of "Mes agendas", by its name. */
  /**
   * One agenda's row, matched on its name element rather than on the row's text.
   *
   * `hasText: /^name$/` over the whole row broke the moment the row gained the
   * sync badge: an SVG `<title>`, which is what gives the badge an accessible
   * name, counts as text content — so the row read "DéfautSynchronisé avec
   * Google" and matched nothing. Anything the row grows later — a count, a
   * second badge — would have cost the same debugging session.
   */
  agendaRow(name: string): Locator {
    return this.content.getByTestId('agenda-row').filter({
      has: this.page.getByTestId('agenda-name').filter({ hasText: exactly(name) }),
    })
  }

  /** The sidebar checkbox that shows or hides one agenda. */
  agendaCheckbox(name: string): Locator {
    return this.agendaRow(name).getByRole('checkbox')
  }

  /** Opens the ⋮ menu of one agenda. It only appears on hover, so hover first. */
  async openAgendaMenu(name: string): Promise<void> {
    const row = this.agendaRow(name)
    await row.hover()
    await row.getByRole('button', { name: `Options de l'agenda ${name}` }).click()
  }

  /** "Ajouter → Importer depuis Google", then imports the calendar named `name`. */
  async importFromGoogle(name: string): Promise<void> {
    const dialog = await this.openImportDialog()
    await expect(dialog.getByText(name), 'Google offered no calendar to import').toBeVisible()
    await dialog.getByRole('button', { name: 'Importer' }).click()
    await expect(dialog, 'the import dialog stayed open — the import was refused').toBeHidden()
  }

  /** "Ajouter → Importer depuis Google", left open on the list of calendars. */
  async openImportDialog(): Promise<Locator> {
    await this.content.getByRole('button', { name: 'Ajouter' }).click()
    await this.page.getByRole('menuitem', { name: 'Importer depuis Google' }).click()

    const dialog = this.importDialog
    await expect(dialog).toBeVisible()

    return dialog
  }

  /** "⋮ → Exporter vers Google" on one agenda. */
  async exportToGoogle(name: string): Promise<void> {
    await this.openAgendaMenu(name)
    await this.page.getByRole('menuitem', { name: 'Exporter vers Google' }).click()
  }

  /** The sync icon the sidebar shows beside a Google-backed agenda. */
  syncBadge(name: string): Locator {
    // Our own handle, not MUI's: it sets `data-testid` on an icon only when
    // `NODE_ENV !== 'production'`, and the stack serves a built bundle — so
    // `[data-testid="SyncIcon"]` matched nothing here, and nothing noticed
    // because the only test using it was expected to fail (MAG-148).
    return this.agendaRow(name).getByTestId('agenda-sync-badge')
  }

  /**
   * Picks a value in one of the dialogs' MUI selects.
   *
   * By role rather than by label: MUI renders a hidden `<input>` alongside the
   * `combobox`, `getByLabel` matches the input, and clicking it does nothing at
   * all — the menu never opens and the next line fails on a missing option.
   */
  private async choose(dialog: Locator, label: string, option: string): Promise<void> {
    await dialog.getByRole('combobox', { name: label }).click()
    await this.page.getByRole('option', { name: option, exact: true }).click()
  }
}

/** A `hasText` regex matching the whole text and nothing more. */
function exactly(value: string): RegExp {
  return new RegExp(`^${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`)
}

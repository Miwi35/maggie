import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { AdminShell } from './AdminShell.js'
import { adminUrl, ROUTES } from './routes.js'

/**
 * Agenda — the FullCalendar grid.
 *
 * {@link openEvent} is the reason this page object exists. A journey that asks
 * Maggie to book something cannot assume the result lands in the month the
 * calendar happens to be showing, and driving the toolbar to some other date
 * would be a test of the toolbar. The admin already has the answer: search
 * results deep-link to an event with `?eventId=`, and that path navigates the
 * grid to the event's own date and opens its detail card. Following it asserts
 * the write *and* the one navigation an owner really uses to reach it.
 */
export class CalendarPage extends AdminShell {
  constructor(page: Page) {
    super(page)
  }

  async open(): Promise<void> {
    await this.goto(ROUTES.calendar)
    await this.expectLoaded()
  }

  /**
   * Opens an event by its ULID and waits for its detail card to carry `title`.
   *
   * The `eventId` parameter is fed straight to react-admin's `getOne`, which
   * takes the IRI as the record id — so the ULID is expanded here. The admin's
   * own search does *not*: `getResultPath` hands over the bare id
   * Elasticsearch returned, `getOne` resolves it against the origin, and the
   * request goes to `/<ulid>`, answers 404, and the screen says "Événement
   * introuvable". That is the whole of the global search's navigation, not
   * just the calendar's, so it is a bug of its own (MAG-144) rather than
   * something this journey should paper over — and this journey exercises the
   * deep link the way it is meant to be called, which is what keeps it
   * meaningful once the bug is fixed.
   *
   * The card is drawn 300 ms after the event comes back, to let the grid
   * finish moving; `toBeVisible` covers that without anyone sleeping.
   */
  async openEvent(id: string, title: string): Promise<void> {
    const recordId = encodeURIComponent(id.startsWith('/') ? id : `/api/events/${id}`)

    await this.page.goto(adminUrl(`${ROUTES.calendar}?eventId=${recordId}`))
    await this.expectLoaded()
    await expect(this.detailTitle(title), 'the calendar never opened the event').toBeVisible()
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
}

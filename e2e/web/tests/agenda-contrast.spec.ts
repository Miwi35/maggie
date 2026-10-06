import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext, Locator } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * The text of a multi-day bar is readable on its agenda's colour (MAG-252).
 *
 * The owner's screenshot: "Meven", a multi-day event on a yellow agenda, written in
 * white on light yellow. FullCalendar writes white on every bar unless the event
 * carries a text colour, and the colour is read where it is computed — in the
 * browser — because that is the only place the bar's real text colour exists.
 *
 * Signed in as the neighbour, like `agenda-default.spec.ts`: the journey adds two
 * agendas, and the seeded owner's sidebar is read by every parallel journey.
 */

const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface Created {
  id: string
}

async function createAgenda(api: APIRequestContext, name: string, color: string): Promise<Created> {
  const response = await api.post('/api/agendas', {
    headers: JSON_LD,
    data: { name, color, timeZone: 'Europe/Paris' },
  })
  expect(response.status()).toBe(201)

  return (await response.json()) as Created
}

async function createMultiDayEvent(api: APIRequestContext, summary: string, agenda: Created): Promise<Created> {
  const response = await api.post('/api/events', {
    headers: JSON_LD,
    data: {
      summary,
      allDay: true,
      startAt: `${seedDate(-1)}T00:00:00+00:00`,
      endAt: `${seedDate(2)}T00:00:00+00:00`,
      agenda: `/api/agendas/${agenda.id}`,
    },
  })
  expect(response.status()).toBe(201)

  return (await response.json()) as Created
}

/** The text colour of the bar carrying `summary`, as the browser computes it: [r, g, b]. */
async function barTextColor(calendar: CalendarPage, summary: string): Promise<number[]> {
  const text: Locator = calendar.grid.locator('.fc-daygrid-block-event .fc-event-main').filter({ hasText: summary }).first()
  const computed = await text.evaluate((element) => getComputedStyle(element).color)

  return (computed.match(/\d+(\.\d+)?/g) ?? []).slice(0, 3).map(Number)
}

test('a multi-day event on a yellow agenda is written in black, on a blue one in white', async ({ otherUser }) => {
  const { api, page } = otherUser
  const retry = test.info().retry
  const yellowName = `Jaune, essai ${retry}`
  const blueName = `Bleu, essai ${retry}`
  const yellowSummary = `Meven (jaune), essai ${retry}`
  const blueSummary = `Séminaire (bleu), essai ${retry}`

  const yellow = await createAgenda(api, yellowName, '#FDD663')
  const blue = await createAgenda(api, blueName, '#1A73E8')

  try {
    await createMultiDayEvent(api, yellowSummary, yellow)
    await createMultiDayEvent(api, blueSummary, blue)

    for (const summary of [yellowSummary, blueSummary]) {
      await waitForIndexed(api, '/api/events?itemsPerPage=100', (member) => member.summary === summary, {
        what: `The event "${summary}"`,
      })
    }

    const calendar = new CalendarPage(page, 'Agenda du voisin')
    await calendar.open()
    await expect(calendar.chip(yellowSummary).first()).toBeVisible()
    await expect(calendar.chip(blueSummary).first()).toBeVisible()

    const [yr, yg, yb] = await barTextColor(calendar, yellowSummary)
    expect(Math.max(yr, yg, yb), `yellow bar text is rgb(${yr}, ${yg}, ${yb}), not black`).toBeLessThan(80)

    const [br, bg, bb] = await barTextColor(calendar, blueSummary)
    expect(Math.min(br, bg, bb), `blue bar text is rgb(${br}, ${bg}, ${bb}), not white`).toBeGreaterThan(220)
  } finally {
    await api.delete(`/api/agendas/${yellow.id}`)
    await api.delete(`/api/agendas/${blue.id}`)
  }
})

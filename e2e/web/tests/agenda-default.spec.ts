import { test, expect, seedDate } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * One default agenda per user, and the one they chose (MAG-149).
 *
 * Signed in as the neighbour, whose agendas no other journey writes events
 * into: marking another agenda as the default moves what the "Nouvel
 * événement" dialog preselects, and doing it on the seeded owner's account
 * would change the target of every parallel journey that creates an event
 * without choosing a calendar.
 *
 * Asserted through `/api/agendas` and `/api/events`, not through the badge
 * alone: the promise is that the event lands in the chosen agenda and that
 * the previous default stopped being one.
 */

interface AgendaRow {
  id: string
  name: string
  default?: boolean
  isDefault?: boolean
}

const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

async function agendas(api: APIRequestContext): Promise<AgendaRow[]> {
  return getCollection<AgendaRow>(api, '/api/agendas?itemsPerPage=100')
}

const isDefault = (agenda: AgendaRow): boolean => Boolean(agenda.default ?? agenda.isDefault)

test('the agenda marked as default receives the events created without a calendar', async ({ otherUser }) => {
  const { api, page } = otherUser
  const name = `Concerts, essai ${test.info().retry}`
  const summary = `Concert sans calendrier, essai ${test.info().retry}`
  const day = seedDate(0)

  const [original] = (await agendas(api)).filter(isDefault)
  expect(original, 'the neighbour should start with a default agenda').toBeDefined()

  const created = await api.post('/api/agendas', {
    headers: JSON_LD,
    data: { name, color: '#ff9800', timeZone: 'Europe/Paris' },
  })
  expect(created.status()).toBe(201)
  const second = (await created.json()) as AgendaRow

  try {
    // A second agenda never takes the default from the one the user already has.
    expect((await agendas(api)).filter(isDefault).map((agenda) => agenda.name)).toEqual([original.name])

    const calendar = new CalendarPage(page, 'Agenda du voisin')
    await calendar.open()
    await expect(calendar.agendaRow(name)).toBeVisible()
    await expect(calendar.agendaRow(original.name).getByTestId('agenda-default-badge')).toBeVisible()
    await expect(calendar.agendaRow(name).getByTestId('agenda-default-badge')).toHaveCount(0)

    await calendar.openAgendaMenu(name)
    await page.getByRole('menuitem', { name: 'Définir comme agenda par défaut' }).click()

    await expect(calendar.agendaRow(name).getByTestId('agenda-default-badge')).toBeVisible()
    await expect(calendar.agendaRow(original.name).getByTestId('agenda-default-badge')).toHaveCount(0)

    await calendar.createEvent({ summary, start: `${day}T10:00`, end: `${day}T11:00` })

    const event = await waitForIndexed<{ summary?: string; agenda?: string }>(
      api,
      '/api/events?itemsPerPage=100',
      (member) => member.summary === summary,
      { what: `The event "${summary}"` },
    )
    expect(event.agenda, 'the event was not filed in the new default agenda').toBe(`/api/agendas/${second.id}`)

    const defaults = (await agendas(api)).filter(isDefault)
    expect(defaults.map((agenda) => agenda.name), 'exactly one agenda should be the default').toEqual([name])
  } finally {
    await api.patch(`/api/agendas/${original.id}`, {
      headers: { 'Content-Type': 'application/merge-patch+json' },
      data: { isDefault: true },
    })
    await api.delete(`/api/agendas/${second.id}`)
  }
})

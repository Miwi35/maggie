import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * The calendar opens the way the user saved it (MAG-120).
 *
 * « Préférences » stores a default view and the agendas to show; the calendar
 * used to ignore both and always open on the month with every agenda ticked.
 *
 * Signed in as the neighbour, like `agenda-default.spec.ts`: the preference is
 * the user's own, so writing it on the owner's account would change the view
 * every parallel agenda journey opens on.
 */

interface AgendaRow {
  id: string
  name: string
}

const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json' }
const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

async function savePreferences(api: APIRequestContext, data: Record<string, unknown>): Promise<void> {
  const response = await api.patch('/api/user_preferences/me', { headers: MERGE_PATCH, data })
  expect(response.ok(), `PATCH /api/user_preferences/me answered ${response.status()}`).toBe(true)
}

test('the calendar opens on the saved view and shows only the saved agendas', async ({ otherUser }) => {
  const { api, page } = otherUser
  const name = `Agenda masqué, essai ${test.info().retry}`

  const kept = (await getCollection<AgendaRow>(api, '/api/agendas?itemsPerPage=100')).find(
    (agenda) => agenda.name === 'Agenda du voisin',
  )
  expect(kept, "the neighbour should start with « Agenda du voisin »").toBeDefined()
  if (!kept) return

  const created = await api.post('/api/agendas', {
    headers: JSON_LD,
    data: { name, color: '#ff9800', timeZone: 'Europe/Paris' },
  })
  expect(created.status()).toBe(201)
  const hidden = (await created.json()) as AgendaRow

  try {
    // The list is served from Elasticsearch, which refreshes once a second.
    await waitForIndexed<AgendaRow>(api, '/api/agendas?itemsPerPage=100', (member) => member.name === name, {
      what: `The agenda "${name}"`,
    })

    await savePreferences(api, { defaultCalendarView: 'day', enabledAgendaIds: [`/api/agendas/${kept.id}`] })

    const calendar = new CalendarPage(page, 'Agenda du voisin')
    await calendar.open()

    await calendar.expectView('Jour')
    await expect(calendar.agendaCheckbox(kept.name)).toBeChecked()
    await expect(calendar.agendaCheckbox(name)).not.toBeChecked()

    // The saved choice is where the screen starts, not a lock: the user's own
    // click wins, and it is not written back to the preferences.
    await calendar.agendaRow(name).click()
    await expect(calendar.agendaCheckbox(name)).toBeChecked()
  } finally {
    await savePreferences(api, { defaultCalendarView: 'month', enabledAgendaIds: [] })
    await api.delete(`/api/agendas/${hidden.id}`)
  }
})

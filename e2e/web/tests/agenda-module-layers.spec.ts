import { test, expect, seedDate } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * The module agendas of the calendar — MAG-820.
 *
 * « Tâches » sits beside Repas, Santé and Finance in the filter list, read from
 * `GET /api/agendas?exists[module]=true`, and the API hides it from the ordinary list.
 * A task scheduled on a day is drawn there in the agenda's colour, with no checkbox;
 * unticking « Tâches » takes it off the grid.
 *
 * The journey's own account: the module agenda is born with its first task, so
 * nothing the shared seed wrote is read.
 */

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const DAY = seedDate()

test.use({ journeyAccount: 'standard' })

interface Named {
  name?: string
  color?: string
  module?: string
}

test('« Tâches » is a module agenda: listed in the filters, hidden from the agendas, tasks on their day in its colour — MAG-820', async ({ page, api }) => {
  const { retry } = test.info()
  const title = `Appeler le garage MAG-820${0 === retry ? '' : ` essai ${retry}`}`

  const created = await api.post('/api/tasks', { headers: jsonLd, data: { title, scheduledDate: DAY } })
  expect(created.status(), `POST /api/tasks answered ${created.status()}: ${await created.text()}`).toBe(201)

  await waitForIndexed<{ title?: string }>(api, '/api/tasks?itemsPerPage=100', (t) => t.title === title, { what: 'The scheduled task' })

  // The agenda was filed on the task's creation: absent from the plain list, present under `exists[module]`.
  const plain = await getCollection<Named>(api, '/api/agendas?itemsPerPage=100')
  expect(plain.map((a) => a.name)).not.toContain('Tâches')
  const tasksAgendaOf = async (): Promise<Named | undefined> =>
    (await getCollection<Named>(api, '/api/agendas?itemsPerPage=100&exists%5Bmodule%5D=true')).find((a) => 'tasks' === a.module)
  await expect.poll(async () => (await tasksAgendaOf())?.name, { timeout: 30_000 }).toBe('Tâches')
  const tasksAgenda = await tasksAgendaOf()
  expect(tasksAgenda?.name).toBe('Tâches')
  expect(tasksAgenda?.color?.toLowerCase()).toBe('#e8a9d9')

  const calendar = new CalendarPage(page)
  await calendar.open()
  await calendar.chooseView('Jour')

  await expect(calendar.moduleAgendaRow('Tâches')).toHaveCount(1)
  await expect(calendar.moduleAgendaRow('Repas')).toHaveCount(1)
  await expect(calendar.chip(title)).toHaveCount(1)
  // No checkbox on the chip: the grid shows what is left to do, nothing to tick.
  await expect(calendar.chip(title).getByRole('checkbox')).toHaveCount(0)

  await calendar.moduleAgendaRow('Tâches').getByRole('checkbox').uncheck()
  await expect(calendar.chip(title)).toHaveCount(0)

  await calendar.moduleAgendaRow('Tâches').getByRole('checkbox').check()
  await expect(calendar.chip(title)).toHaveCount(1)
})

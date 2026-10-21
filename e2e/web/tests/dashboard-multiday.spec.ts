import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * A holiday begun before today is still on today's dashboard (MAG-536).
 *
 * The dashboard asked for the events that *begin* in its window, so an all-day
 * event of several days vanished from « Aujourd'hui » and « Demain » from its
 * second day on, while the phone kept listing it.
 */
test('an all-day event begun two days ago shows in « Aujourd’hui » and « Demain »', async ({ page, api }) => {
  const { retry } = test.info()
  const summary = 0 === retry ? 'Vacances multi-jours MAG-536' : `Vacances multi-jours MAG-536 (essai ${retry})`

  const response = await api.post('/api/events', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: {
      agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
      summary,
      allDay: true,
      startDate: seedDate(-2),
      endDate: seedDate(4),
    },
  })
  expect(response.status(), `POST /api/events answered ${response.status()}: ${await response.text()}`).toBe(201)
  await waitForIndexed<{ summary?: string }>(api, '/api/events?itemsPerPage=200', (event) => event.summary === summary, {
    what: 'The multi-day event',
  })

  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const column = (name: string) => dashboard.content.getByRole('heading', { name }).locator('..')
  await expect(column("Aujourd'hui").getByText(summary, { exact: true })).toBeVisible()
  await expect(column('Demain').getByText(summary, { exact: true })).toBeVisible()
})

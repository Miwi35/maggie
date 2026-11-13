import { test, expect } from '../fixtures/index.js'
import { getCollection } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { PeriodsPage } from '../pages/PeriodsPage.js'

/**
 * Agenda › Périodes (MAG-816, « Périodes et contexte du jour » part 6, extends MAG-97):
 * the bands above the Week grid, the page, and the centred dialog that creates a period.
 *
 * The account is created for the test, so a retry starts on an account of its own.
 */

test.use({ journeyAccount: 'empty' })

const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

/** Monday of the week Paris is in now, and the Saturday after it, as `YYYY-MM-DD`. */
function thisWeek(): { monday: string; saturday: string } {
  const today = new Date().toLocaleDateString('fr-CA', { timeZone: 'Europe/Paris' })
  const date = new Date(`${today}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() - ((date.getUTCDay() + 6) % 7))
  const monday = date.toISOString().slice(0, 10)
  date.setUTCDate(date.getUTCDate() + 5)

  return { monday, saturday: date.toISOString().slice(0, 10) }
}

test('a period shows as a band over the week, and a new one is created from the page', async ({ api, page }) => {
  const { monday, saturday } = thisWeek()

  const agenda = await api.post('/api/agendas', { headers: JSON_LD, data: { name: 'Famille' } })
  expect(agenda.status(), await agenda.text()).toBe(201)
  const agendaIri = (await agenda.json())['@id'] as string

  // Monday to Friday, the end excluded as the API stores it.
  const garde = await api.post('/api/periods', {
    headers: JSON_LD,
    data: { name: 'Garde', agenda: agendaIri, allDay: true, startDate: monday, endDate: saturday },
  })
  expect(garde.status(), await garde.text()).toBe(201)

  const calendar = new CalendarPage(page, 'Famille')
  await calendar.open()
  await calendar.chooseView('Semaine')

  const band = page.getByTestId('period-band').filter({ hasText: 'Garde' })
  await expect(band).toBeVisible()
  await expect(band).toHaveAttribute('data-span', '5')

  const periods = new PeriodsPage(page)
  await calendar.content.getByRole('link', { name: 'Périodes' }).click()
  await expect(periods.heading).toBeVisible()
  await expect(periods.row('Garde')).toBeVisible()

  await periods.create('Vacances')
  await expect(periods.row('Vacances')).toBeVisible()

  await expect
    .poll(async () => (await getCollection<{ name: string }>(api, '/api/periods')).map((period) => period.name))
    .toContain('Vacances')
})

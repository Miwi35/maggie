import { test, expect, parisDay } from '../fixtures/index.js'
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

/** Monday of the week Paris is in at the stack's clock, and the Saturday after it, as `YYYY-MM-DD`. */
function thisWeek(): { monday: string; saturday: string } {
  const today = parisDay()
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

  // The band sits over the columns of its days: Monday's header to Friday's.
  const headers = page.locator('.fc-col-header-cell')
  const [bandBox, firstBox, fifthBox] = await Promise.all([band.boundingBox(), headers.nth(0).boundingBox(), headers.nth(4).boundingBox()])
  expect(Math.abs(bandBox!.x - firstBox!.x)).toBeLessThan(8)
  expect(Math.abs(bandBox!.x + bandBox!.width - (fifthBox!.x + fifthBox!.width))).toBeLessThan(8)

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

test('the dialog of an existing period reads its own agenda and saves it unchanged (MAG-913)', async ({ api, page }) => {
  const { monday, saturday } = thisWeek()

  const first = await api.post('/api/agendas', { headers: JSON_LD, data: { name: 'Famille' } })
  expect(first.status(), await first.text()).toBe(201)
  const second = await api.post('/api/agendas', { headers: JSON_LD, data: { name: 'Travail' } })
  expect(second.status(), await second.text()).toBe(201)
  const secondIri = (await second.json())['@id'] as string

  const created = await api.post('/api/periods', {
    headers: JSON_LD,
    data: { name: 'Déplacement', agenda: secondIri, allDay: true, startDate: monday, endDate: saturday },
  })
  expect(created.status(), await created.text()).toBe(201)
  const periodIri = (await created.json())['@id'] as string

  const periods = new PeriodsPage(page)
  await periods.open()
  await periods.row('Déplacement').click()
  await expect(periods.dialog).toBeVisible()
  await expect(periods.dialog.getByRole('combobox', { name: /Agenda/ })).toHaveText('Travail')

  await Promise.all([
    page.waitForResponse((response) => response.url().includes('/api/periods') && ['PUT', 'PATCH'].includes(response.request().method())),
    periods.dialog.getByRole('button', { name: 'Enregistrer' }).click(),
  ])
  await expect(periods.dialog).toBeHidden()

  const saved = await api.get(periodIri, { headers: JSON_LD })
  expect((await saved.json()).agenda).toBe(secondIri)
})

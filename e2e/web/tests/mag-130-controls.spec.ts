import { test, expect, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'

/**
 * A control that existed in the code but that nothing on screen reached
 * (MAG-130): the pencil on an event, which only knew how to open a task.
 */

test.describe('A control that did nothing', () => {
  test('the pencil on an event opens its form and saves the change', async ({ page, api }) => {
    const calendar = new CalendarPage(page)
    await calendar.openEvent(seedId('e2e_event_with_reminder'), 'Rendez-vous dentiste')

    await page.getByRole('button', { name: 'Modifier' }).click()

    const dialog = page.getByRole('dialog', { name: "Modifier l'événement" })
    await expect(dialog).toBeVisible()
    await dialog.getByLabel(/Résumé/).fill('Rendez-vous dentiste (contrôle)')
    await dialog.getByRole('button', { name: 'Enregistrer' }).click()

    const stored = await waitForIndexed<{ summary: string }>(
      api,
      '/api/events',
      (event) => event.summary === 'Rendez-vous dentiste (contrôle)',
      { what: 'The renamed event' },
    )

    expect(stored.summary).toBe('Rendez-vous dentiste (contrôle)')
  })
})

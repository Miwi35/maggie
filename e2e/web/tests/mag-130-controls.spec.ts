import { test, expect, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { PreferencesPage } from '../pages/PreferencesPage.js'

/**
 * Two controls that existed in the code but that nothing on screen reached
 * (MAG-130): the wake word switch, whose hook had no UI, and the pencil on an
 * event, which only knew how to open a task.
 */

test.describe('Controls that did nothing', () => {
  test('the wake word can be turned on and off from the preferences', async ({ page }) => {
    const preferences = new PreferencesPage(page)
    await preferences.open()

    const wakeWord = preferences.content.getByRole('switch', { name: /mot d.activation/i })
    await expect(wakeWord).not.toBeChecked()

    await wakeWord.click()
    await expect(wakeWord).toBeChecked()
    expect(await page.evaluate(() => localStorage.getItem('wakeWordEnabled'))).toBe('true')

    // The choice survives a reload: it is what the app bar's listening icon reads.
    await page.reload()
    await preferences.expectReady()
    await expect(wakeWord).toBeChecked()

    await wakeWord.click()
    await expect(wakeWord).not.toBeChecked()
    expect(await page.evaluate(() => localStorage.getItem('wakeWordEnabled'))).toBe('false')
  })

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

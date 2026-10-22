import { test, expect } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The Accueil (MAG-560): the trail says where we are and a selector picks the
 * day every card shows. The selector lives in the URL, so a reload or a shared
 * link keeps it. The cards come with their own tickets and journeys.
 */
test('the Accueil shows its trail and a day selector that switches to « Demain » @responsive', async ({ page }) => {
  const home = new HomePage(page)
  await home.open()

  await expect(home.day("Aujourd'hui")).toHaveAttribute('aria-pressed', 'true')
  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'false')

  await home.day('Demain').click()

  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
  await expect(page).toHaveURL(/day=tomorrow/)

  // A reload keeps the day.
  await page.reload()
  await home.expectReady()
  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
})

test('a link to « Demain » opens the Accueil on it', async ({ page }) => {
  const home = new HomePage(page)
  await home.open('tomorrow')

  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
})

import { test, expect } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The Cuisine activity, launched from the Accueil (MAG-580, extends MAG-99): the full-screen frame, the
 * preparation of tonight's meal with its two recipes in order, and the way out.
 *
 * The account of `65-nutrition-home.yaml`: tonight's dinner has « Pâtes au pesto » (two steps) then
 * « Salade de saison » (one step). What each screen holds is the Vitest suite's; this is the request and the
 * real route, the real browser's full screen and the way back to where the cook came from.
 */
test('« ▶ Lancer » on tonight’s meal opens the Cuisine activity, which lists both recipes, and Échap goes back to the Accueil', async ({
  nutritionUser,
}) => {
  const { page } = nutritionUser
  const home = new HomePage(page)

  // Given tonight's dinner with two recipes, when I tap « Lancer » on the Accueil…
  await home.open()
  await home.card('nutrition').getByTestId('home-launch-nutrition').click()

  // …the activity opens over the whole window, on the preparation of the meal.
  await expect(page).toHaveURL(/\/activites\/cuisine\?meal=/)
  await expect(page.getByTestId('activity-shell')).toBeVisible()
  const preparation = page.getByTestId('cooking-preparation')
  await expect(preparation).toBeVisible()
  await expect(preparation.getByTestId('cooking-queue-item')).toHaveCount(2)
  await expect(preparation.getByTestId('cooking-queue-item').nth(0)).toContainText('Pâtes au pesto')
  await expect(preparation.getByTestId('cooking-queue-item').nth(0)).toHaveAttribute('data-current', 'true')
  await expect(preparation.getByTestId('cooking-queue-item').nth(1)).toContainText('Salade de saison')

  // When I press Échap with nothing running, I leave without being asked, back on the Accueil.
  const left = page.waitForURL((url) => !url.pathname.startsWith('/activites/cuisine'))
  await page.keyboard.press('Escape')
  // A real browser leaves full screen itself on Échap, which is what ends the activity; a synthetic key does
  // not move the browser's own full screen, so it is given the same signal.
  await page.evaluate(() => (document.fullscreenElement ? document.exitFullscreen() : undefined)).catch(() => undefined)
  await left

  await expect(page.getByText("Quitter l'activité ?")).toHaveCount(0)
  await home.expectReady()
  await expect(home.card('nutrition')).toBeVisible()
})

import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The agent's settings are named after her (MAG-329, extends MAG-103).
 *
 * Given a signed-in user who never renamed her, the Paramètres menu lists
 * « Maggie », not « Agent », and that entry opens the agent's settings.
 * The renamed case (« Alfred ») is covered by the component tests: renaming
 * the personality here would rename Maggie for every parallel journey.
 */
test('the Paramètres menu lists Maggie and opens her settings', async ({ page }) => {
  const shell = new AdminShell(page)

  await shell.goto(ROUTES.dashboard)
  await shell.navigateTo('Maggie', 'Paramètres')

  await expect(page).toHaveURL(/#\/settings\/agent$/)
  await expect(page.getByRole('heading', { name: 'Maggie', level: 5 })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Personnalité' })).toBeVisible()
})

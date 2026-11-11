import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The agent's settings are named after her (MAG-329, extends MAG-103).
 *
 * Given a signed-in user who never renamed her, the Réglages menu lists
 * « Maggie », not « Agent », and that entry opens the agent's settings.
 * The renamed case (« Alfred ») is covered by the component tests: renaming
 * the personality here would rename Maggie for every parallel journey.
 */
test('the Réglages menu lists Maggie and opens her settings', async ({ page }) => {
  const shell = new AdminShell(page)

  await shell.goto(ROUTES.home)
  await shell.expectLoaded()
  await shell.navigateTo('Maggie', 'Réglages')

  await expect(page).toHaveURL(/#\/settings\/agent$/)
  await expect(page.getByRole('heading', { name: 'Maggie', level: 1 })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Personnalité' })).toBeVisible()
})

/**
 * The morning digest and the evening review are set from the admin too (MAG-788, extends MAG-103).
 *
 * Given the e2e seed, when the digest is switched off and the review set to 22:15,
 * then after a reload both cards show those values.
 */
test('the morning digest and the evening review are saved and survive a reload', async ({ page }) => {
  const shell = new AdminShell(page)

  await shell.goto(ROUTES.home)
  await shell.expectLoaded()
  await shell.navigateTo('Maggie', 'Réglages')

  await page.getByLabel('Digest du matin', { exact: true }).uncheck()
  await expect(page.getByLabel('Heure — Digest du matin')).toBeDisabled()
  await page.getByLabel('Heure — Bilan du soir').fill('22:15')
  await expect(page.getByText('Enregistré')).toBeVisible()

  await page.reload()

  await expect(page.getByLabel('Digest du matin', { exact: true })).not.toBeChecked()
  await expect(page.getByLabel('Heure — Bilan du soir')).toHaveValue('22:15')
})

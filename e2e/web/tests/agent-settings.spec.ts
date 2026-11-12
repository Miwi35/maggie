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
 *
 * An account of its own: the shared one is read by every parallel journey, and the digest and the
 * review hours are its settings.
 */
test.describe('the daily rendezvous', () => {
  test.use({ journeyAccount: 'empty' })

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

  // The working hours (MAG-790): the end set to 17:00 and Friday taken off, then a reload.
  test('the working hours are saved and survive a reload', async ({ page }) => {
    const shell = new AdminShell(page)

    await shell.goto(ROUTES.home)
    await shell.expectLoaded()
    await shell.navigateTo('Maggie', 'Réglages')

    await page.getByLabel('Fin du travail').fill('17:00')
    await expect(page.getByText('Enregistré')).toBeVisible()
    const friday = page.getByRole('button', { name: 'vendredi' })
    await friday.click()
    await expect(friday).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByText('Enregistré').first()).toBeVisible()

    await page.reload()

    await expect(page.getByLabel('Fin du travail')).toHaveValue('17:00')
    await expect(page.getByRole('button', { name: 'vendredi' })).toHaveAttribute('aria-pressed', 'false')
    await expect(page.getByRole('button', { name: 'jeudi' })).toHaveAttribute('aria-pressed', 'true')
  })
})

import { test, expect } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * « Maggie ne peut plus répondre » (MAG-727, extends MAG-99).
 *
 * The provider's state is global to the account, so a journey that forced it would show the
 * banner to every journey running beside it: the answer of `GET /agent/provider-status` is
 * stubbed in this page only. The state's own rules (credit, key, success, 529/429) are the
 * agent's pytest suite.
 */

const answer = (status: 'ok' | 'credit' | 'auth') => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ status, since: status === 'ok' ? null : '2026-10-30T08:00:00+00:00' }),
})

test('an empty credit shows the banner with « Recharger », and it goes when the state is ok again', async ({ page }) => {
  let state: 'ok' | 'credit' | 'auth' = 'credit'
  await page.route('**/agent/provider-status', (route) => route.fulfill(answer(state)))

  await new HomePage(page).open()
  const banner = page.getByTestId('provider-banner')
  await expect(banner).toContainText('Maggie ne peut plus répondre')
  await expect(banner).toContainText('Crédit Anthropic épuisé')
  await expect(banner.getByRole('link', { name: 'Recharger' })).toHaveAttribute(
    'href',
    'https://console.anthropic.com/settings/billing',
  )

  state = 'ok'
  await new HomePage(page).open()
  await expect(page.getByTestId('provider-banner')).toHaveCount(0)
})

test('a refused key says so and opens the keys page of the console', async ({ page }) => {
  await page.route('**/agent/provider-status', (route) => route.fulfill(answer('auth')))

  await new HomePage(page).open()
  const banner = page.getByTestId('provider-banner')
  await expect(banner).toContainText('Clé Anthropic refusée')
  await expect(banner.getByRole('link', { name: 'Ouvrir la console' })).toHaveAttribute(
    'href',
    'https://console.anthropic.com/settings/keys',
  )
})

import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext, Page } from '@playwright/test'
import { PreferencesPage } from '../pages/PreferencesPage.js'
import type { Theme } from '../pages/PreferencesPage.js'
import { TOKENS } from '../helpers/tokens.js'

/**
 * « Apparence » repaints the interface (MAG-315, extends MAG-103).
 *
 * The select stored the choice and said « Thème mis à jour » while `body` stayed
 * light: the choice was written under a store key react-admin never reads.
 *
 * Signed in as the neighbour: the theme is the user's own, and on the owner's
 * account a dark interface would repaint every parallel journey.
 */

const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json' }

async function saveTheme(api: APIRequestContext, theme: Theme): Promise<void> {
  const response = await api.patch('/api/user_preferences/me', { headers: MERGE_PATCH, data: { theme } })
  expect(response.ok(), `PATCH /api/user_preferences/me answered ${response.status()}`).toBe(true)
}

/** `rgb(17, 14, 28)` as `#110E1C`, so a failure prints the token. */
async function bodyBackground(page: Page): Promise<string> {
  const computed = await page.locator('body').evaluate((element) => getComputedStyle(element).backgroundColor)
  const [r, g, b] = (computed.match(/\d+/g) ?? []).map(Number)

  return `#${[r, g, b].map((channel) => channel.toString(16).padStart(2, '0')).join('')}`.toUpperCase()
}

const surface = (mode: 'light' | 'dark'): string => TOKENS.surface[mode].background.toUpperCase()

const expectBackground = (page: Page, mode: 'light' | 'dark') =>
  expect.poll(() => bodyBackground(page)).toBe(surface(mode))

test.afterEach(async ({ otherUser }) => {
  await saveTheme(otherUser.api, 'system')
})

test('Sombre repaints at once and stays after a reload, Clair holds against a dark OS', async ({ otherUser }) => {
  const { page, api } = otherUser
  await saveTheme(api, 'system')
  const preferences = new PreferencesPage(page)
  await preferences.open()
  await expectBackground(page, 'light')

  await preferences.chooseTheme('dark')
  await preferences.expectTheme('dark')
  await expectBackground(page, 'dark')

  await page.reload()
  await preferences.expectReady()
  await expectBackground(page, 'dark')

  await page.emulateMedia({ colorScheme: 'dark' })
  await preferences.chooseTheme('light')
  await preferences.expectTheme('light')
  await expectBackground(page, 'light')
})

test('Système follows the OS', async ({ otherUser }) => {
  const { page, api } = otherUser
  await saveTheme(api, 'dark')
  const preferences = new PreferencesPage(page)
  await preferences.open()
  await expectBackground(page, 'dark')

  await preferences.chooseTheme('system')
  await preferences.expectTheme('system')
  await expectBackground(page, 'light')

  await page.emulateMedia({ colorScheme: 'dark' })
  await expectBackground(page, 'dark')
})

test('the saved preference is restored on a fresh session', async ({ otherUser }) => {
  const { page, api } = otherUser
  await saveTheme(api, 'dark')

  await new PreferencesPage(page).open()

  await expectBackground(page, 'dark')
})

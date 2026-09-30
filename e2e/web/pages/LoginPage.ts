import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

/**
 * The sign-in screen.
 *
 * Journeys never drive it — Google's consent screen cannot be automated, which
 * is why the test login exists. What this page object is for is the *other*
 * direction: asserting the admin lands here when a session stops being valid
 * (MAG-93 — a163dcb, a blank page on a mid-session 401; e799109, an expired
 * JWT `checkAuth` never checked).
 */
export class LoginPage {
  readonly googleButton: Locator
  readonly prompt: Locator

  constructor(private readonly page: Page) {
    this.googleButton = page.getByRole('link', { name: 'Se connecter avec Google' })
    this.prompt = page.getByText('Connectez-vous pour continuer')
  }

  async goto(): Promise<void> {
    await this.page.goto('/admin/#/login')
  }

  /**
   * The timeout is deliberate and should shrink.
   *
   * A signed-in dashboard renders in under a second, but a visitor with no
   * session — or a dead one — waits between 7 and 25 seconds on a blank page
   * before this screen appears: react-admin retries the rejected `checkAuth`
   * with exponential backoff, and nothing is rendered in the meantime. That is
   * the same family as a163dcb and is filed as MAG-140; the journeys assert
   * the behaviour today and the budget comes down to 5s once it is fixed.
   */
  async expectShown(timeout = 35_000): Promise<void> {
    await expect(this.prompt).toBeVisible({ timeout })
    await expect(this.googleButton).toBeVisible()
  }

  /** The credentials the admin keeps, and must drop the moment a session dies. */
  async storedCredentials(): Promise<{ token: string | null; user: string | null }> {
    return this.page.evaluate(() => ({
      token: window.localStorage.getItem('token'),
      user: window.localStorage.getItem('user'),
    }))
  }
}

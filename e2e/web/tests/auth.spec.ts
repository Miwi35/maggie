import { test, expect, e2eNow } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { LoginPage } from '../pages/LoginPage.js'
import { adminUrl, ROUTES } from '../pages/routes.js'

/**
 * The admin shell's authentication, which is where three of the six
 * regressions MAG-93 found live — none of them inside any feature:
 *
 *   a163dcb  a 401 during a session left a blank page instead of sending the
 *            user back to the login screen;
 *   e799109  `checkAuth` never looked at the JWT's expiry, so an expired
 *            session looked signed in until the first request failed;
 *   3f16b42  the JWT was not passed to the API documentation parser, so schema
 *            introspection 401'd and no resource could load.
 *
 * All three are asserted against the real API — no route interception. A
 * forged-but-unexpired token is refused by the server exactly as a revoked one
 * would be, which is the situation a163dcb describes; mocking it would only
 * prove that Playwright can return a 401.
 */

/** Well-formed, unexpired, and signed with nothing the API recognises. */
function forgedToken(expiresInSeconds: number): string {
  const encode = (value: object): string =>
    Buffer.from(JSON.stringify(value))
      .toString('base64')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '')

  const header = encode({ typ: 'JWT', alg: 'RS256' })
  const payload = encode({
    sub: 'e2e@maggie.local',
    username: 'e2e@maggie.local',
    roles: ['ROLE_USER'],
    iat: Math.floor(e2eNow().getTime() / 1000),
    exp: Math.floor(e2eNow().getTime() / 1000) + expiresInSeconds,
  })

  return `${header}.${payload}.not-a-real-signature`
}

test('an expired token is refused and its credentials dropped', async ({ pageWithToken }) => {
  const page = await pageWithToken(forgedToken(-60))
  const login = new LoginPage(page)

  await page.goto(adminUrl(ROUTES.dashboard))

  // e799109: `checkAuth` did not look at `exp`, so an expired session looked
  // alive until the first request failed. What must happen is the login
  // screen, and no expired token left in storage for the next page to reuse.
  await login.expectShown(3_000)
  await expect.poll(async () => (await login.storedCredentials()).token).toBeNull()
  await expect(page.getByTestId('page-content')).toHaveCount(0)
})

test('a session the server refuses sends the user back to the login screen', async ({ pageWithToken }) => {
  const page = await pageWithToken(forgedToken(3600))
  const login = new LoginPage(page)

  await page.goto(adminUrl(ROUTES.dashboard))

  // The regression (a163dcb) was a blank page: the 401 came back, nothing
  // caught it, and the app rendered nothing at all. What must happen instead
  // is the login screen, with the dead credentials dropped.
  await login.expectShown(3_000)
  await expect.poll(async () => (await login.storedCredentials()).token).toBeNull()
  await expect.poll(async () => (await login.storedCredentials()).user).toBeNull()
})

test('a valid session introspects the API schema and can list a resource', async ({ page }) => {
  const shell = new AdminShell(page)

  // 3f16b42: `parseHydraDocumentation` was called without the Authorization
  // header, so every resource page came up empty behind a 401 nobody saw. A
  // guessed list rendering its columns is the proof the schema was read.
  const unauthorised: string[] = []
  page.on('response', (response) => {
    if (response.status() === 401 && response.url().includes('/api/')) {
      unauthorised.push(response.url())
    }
  })

  await shell.goto(ROUTES.tasks)
  await shell.expectLoaded()

  await expect(shell.content.getByText('Appeler le garage')).toBeVisible()
  expect(unauthorised, 'the admin made an unauthenticated API call').toEqual([])
})

test('signing out drops the credentials and shows the login screen', async ({ page }) => {
  const shell = new AdminShell(page)
  const login = new LoginPage(page)

  await shell.goto(ROUTES.dashboard)
  await shell.expectLoaded()

  await page.getByRole('banner').getByRole('button', { name: 'Profil' }).click()
  await page.getByRole('menuitem', { name: 'Déconnexion' }).click()

  await login.expectShown()
  await expect.poll(async () => (await login.storedCredentials()).token).toBeNull()
})

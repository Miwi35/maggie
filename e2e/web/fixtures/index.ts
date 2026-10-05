import { test as base, expect } from '@playwright/test'
import type { APIRequestContext, BrowserContext, Page } from '@playwright/test'
import { OTHER_USER_EMAIL, SEED_USER_EMAIL, signIn, storageStateOf } from './session.js'
import type { Session } from './session.js'
import { pinClock } from './clock.js'

export { expect }
export { SEED_USER_EMAIL, OTHER_USER_EMAIL } from './session.js'
export type { Session, SeededUser } from './session.js'
export { e2eNow, parisTime } from './clock.js'
export { seedId, seedAnchorDate, seedDate, seedManifest } from './manifest.js'

/**
 * One login per account per worker.
 *
 * Memoised at module scope rather than in a worker fixture because Playwright's
 * `baseURL` is test-scoped, and a worker fixture may not read it. The effect is
 * the same — one round trip per worker process — without forcing every test
 * that wants a URL to declare a worker dependency it does not have.
 */
const sessions = new Map<string, Promise<Session>>()

function sessionFor(baseURL: string, email: string): Promise<Session> {
  const key = `${baseURL}|${email}`
  let session = sessions.get(key)

  if (!session) {
    session = signIn(baseURL, email)
    sessions.set(key, session)
  }

  return session
}

/** A second signed-in user, with their own browser context, page and API client. */
export interface OtherUser {
  session: Session
  context: BrowserContext
  page: Page
  api: APIRequestContext
  /**
   * A second window of the same account — the "two tabs" check, for this user.
   *
   * Opened lazily: most tests never need it, and a second context per test is
   * a browser window and a page load nobody asked for. Call it once; the same
   * page comes back. Not re-entrant — two calls awaited concurrently would
   * open two windows; no caller does that, and a test wanting a third window
   * should say so here rather than race for it.
   *
   * Two *windows*, not two tabs, for the reason {@link MaggieFixtures.twoWindows}
   * gives: headless Chromium freezes a hidden tab, so a second page in one
   * context stops rendering the moment the first is acted on, both sit
   * unchanged, and real-time looks dead.
   */
  secondWindow: () => Promise<Page>
}

export interface MaggieFixtures {
  /** The signed-in seeded user — `session.user.id` is what Mercure topics are scoped to. */
  session: Session
  /** An API client carrying that user's JWT, for setting a test up or asserting on the database. */
  api: APIRequestContext
  /**
   * The other seeded account, isolated from `page` in its own context.
   *
   * What it is for: proving an update published for one user does not reach the
   * other. Not for feature journeys — those sign in as the seeded user.
   */
  otherUser: OtherUser
  /**
   * Two windows of the same signed-in user — the "two tabs" check.
   *
   * Two *windows*, not two tabs of one context, and the difference is not
   * cosmetic: headless Chromium freezes a hidden tab, so a second page in the
   * same context stops rendering the moment the first is acted on. Both tabs
   * then sit unchanged, real-time looks dead, and the test teaches nothing
   * about the feature. Separate contexts get separate windows, both live.
   */
  twoWindows: { actor: Page; observer: Page }
  /** A tab with no session at all — the login screen, and anything an anonymous visitor sees. */
  anonymousPage: Page
  /**
   * A tab carrying the JWT you hand it, and nothing else.
   *
   * For the credential paths the happy fixture cannot reach: an expired token
   * `checkAuth` has to reject offline, and a well-formed one the server
   * refuses — the two regressions behind e799109 and a163dcb.
   */
  pageWithToken: (token: string) => Promise<Page>
}

/**
 * Cuts a context off from everything that is not the stack.
 *
 * The admin's index.html pulls a Google font, and react-admin phones home to
 * marmelab's telemetry endpoint. Both are render-blocking or slow depending on
 * what the CI runner's egress happens to allow, and neither has anything to do
 * with what a journey asserts — left alone they turn a two-second page load
 * into fifteen, and a firewalled runner into a suite of timeouts.
 *
 * It is also the rule the e2e stack is built on: every external service is
 * simulated, so a request leaving the network is a bug rather than a
 * dependency. See agent-os/standards/global/e2e-environment.md.
 *
 * Matched with a negative lookahead rather than a catch-all pattern plus a
 * `continue()` for our own origin: a route handler sits in the middle of the
 * response, and `continue()` buffers it. That is invisible for a JSON call and
 * fatal for the Mercure stream, which never ends — every real-time assertion
 * in the suite went silent the first time this was written the easy way.
 */
async function isolateFromInternet(context: BrowserContext, baseURL: string): Promise<void> {
  const host = new URL(baseURL).host.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const external = new RegExp(`^https?://(?!${host}(?:[/?#]|$))`)

  await context.route(external, (route) => route.abort())
  await pinClock(context)
}

export const test = base.extend<MaggieFixtures>({
  // Overrides Playwright's own option: every test starts signed in. A test that
  // wants the login page uses the `anonymousPage` fixture instead.
  storageState: async ({ baseURL }, use) => {
    const session = await sessionFor(requireBaseURL(baseURL), SEED_USER_EMAIL)
    await use(storageStateOf(requireBaseURL(baseURL), session))
  },

  context: async ({ context, baseURL }, use) => {
    await isolateFromInternet(context, requireBaseURL(baseURL))
    await use(context)
  },

  session: async ({ baseURL }, use) => {
    await use(await sessionFor(requireBaseURL(baseURL), SEED_USER_EMAIL))
  },

  api: async ({ playwright, baseURL, session }, use) => {
    const context = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: {
        Authorization: `Bearer ${session.token}`,
        Accept: 'application/ld+json',
      },
    })
    await use(context)
    await context.dispose()
  },

  otherUser: async ({ playwright, browser, baseURL, contextOptions }, use) => {
    const url = requireBaseURL(baseURL)
    const session = await sessionFor(url, OTHER_USER_EMAIL)

    const signedIn = async (): Promise<BrowserContext> => {
      // `contextOptions` carries the project's own settings — viewport, device,
      // locale, timezone. A bare `browser.newContext()` would silently give
      // this user a 1280x720 desktop on the `phone` project.
      const context = await browser.newContext({
        ...contextOptions,
        storageState: storageStateOf(url, session),
      })
      await isolateFromInternet(context, url)

      return context
    }

    const context = await signedIn()
    const page = await context.newPage()
    const api = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: {
        Authorization: `Bearer ${session.token}`,
        Accept: 'application/ld+json',
      },
    })

    // An array rather than a nullable, like `pageWithToken` below: TypeScript
    // does not track an assignment made inside the closure, so a `let … | null`
    // narrows to `never` at the teardown and will not compile.
    const extra: BrowserContext[] = []
    const windows: Page[] = []
    const secondWindow = async (): Promise<Page> => {
      if (0 === windows.length) {
        const second = await signedIn()
        extra.push(second)
        windows.push(await second.newPage())
      }

      return windows[0]
    }

    await use({ session, context, page, api, secondWindow })

    await api.dispose()
    await Promise.all(extra.map((spare) => spare.close()))
    await context.close()
  },

  twoWindows: async ({ browser, baseURL, contextOptions, page }, use) => {
    const second = await browser.newContext(contextOptions)
    await isolateFromInternet(second, requireBaseURL(baseURL))

    const observer = await second.newPage()
    await use({ actor: page, observer })

    await second.close()
  },

  anonymousPage: async ({ browser, baseURL, contextOptions }, use) => {
    const context = await browser.newContext({ ...contextOptions, storageState: undefined })
    await isolateFromInternet(context, requireBaseURL(baseURL))
    await use(await context.newPage())
    await context.close()
  },

  pageWithToken: async ({ browser, baseURL, session, contextOptions }, use) => {
    const url = requireBaseURL(baseURL)
    const contexts: BrowserContext[] = []

    await use(async (token: string) => {
      const context = await browser.newContext({
        ...contextOptions,
        storageState: {
          cookies: [],
          origins: [
            {
              origin: new URL(url).origin,
              localStorage: [
                { name: 'token', value: token },
                // A real identity, so nothing fails for the trivial reason
                // that the admin could not tell who it was looking at.
                { name: 'user', value: JSON.stringify(session.user) },
              ],
            },
          ],
        },
      })
      await isolateFromInternet(context, url)
      contexts.push(context)

      return context.newPage()
    })

    await Promise.all(contexts.map((context) => context.close()))
  },
})

function requireBaseURL(baseURL: string | undefined): string {
  if (!baseURL) {
    throw new Error('No baseURL — set E2E_BASE_URL, or run the journeys through `task e2e:web`.')
  }

  return baseURL
}

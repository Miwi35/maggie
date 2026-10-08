import { request as playwrightRequest } from '@playwright/test'
import type { Cookie } from '@playwright/test'

/**
 * Signing in without Google.
 *
 * `POST /api/auth/e2e/login` (MAG-94) hands back the same payload as the Google
 * callback, so nothing below is an e2e-only shape: the admin reads `token` and
 * `user` from localStorage exactly as it does after a real sign-in, and the
 * `mercureAuthorization` cookie the response sets is the one the browser
 * presents to the hub.
 *
 * Deliberately *not* a helper that pokes localStorage from a loaded page: doing
 * that would leave the cookie behind, and every real-time assertion in the
 * suite would then fail for a reason that has nothing to do with the feature
 * under test.
 */

export const SEED_USER_EMAIL = 'e2e@maggie.local'

/**
 * The second seeded account, with two jobs.
 *
 * The first is isolation: proving that a Mercure update published for one user
 * never reaches another (MAG-93 — b333376, c2d3758), and that no read leaks
 * across (MAG-114). Journeys sign in as {@link SEED_USER_EMAIL}.
 *
 * The second is being **the shopper** (MAG-101). A journey that asserts "*this*
 * write published" cannot run on a grocery list anybody else writes to: every
 * writer publishes the whole list on one topic, so a payload showing the new
 * state is satisfied by another worker's add even with the middleware mute —
 * which is `afc1a70` all over again. Ending an errand is worse still: it
 * deletes *every* ticked line. The same applies to the chat, because
 * `GET /agent/messages` is scoped to the user and returns the last twenty, and
 * `chat.spec.ts` depends on that window.
 *
 * So `grocery-errand.spec.ts` owns this account's grocery list outright, and
 * the journeys that talk to Maggie about groceries talk to her as this account.
 * See `api/fixtures/e2e/10-core.yaml`.
 */
export const OTHER_USER_EMAIL = 'e2e-other@maggie.local'

/**
 * The third seeded account: the only one Maggie's interruptions are published
 * to (MAG-311). An interruption covers every window its user has open, so it
 * cannot share an account with a journey that clicks at the same time.
 */
export const INTERRUPTED_USER_EMAIL = 'e2e-interrupt@maggie.local'

const LOGIN_TOKEN = process.env.E2E_LOGIN_TOKEN ?? 'e2e-login-token'

export interface SeededUser {
  id: string
  email: string
  name: string
  avatar: string | null
}

export interface Session {
  token: string
  refreshToken: string
  mercureToken: string
  user: SeededUser
  /** Everything the server set, `mercureAuthorization` included. */
  cookies: Cookie[]
}

/** The `storageState` shape Playwright accepts inline on a browser context. */
export interface SessionStorageState {
  cookies: Cookie[]
  origins: Array<{ origin: string; localStorage: Array<{ name: string; value: string }> }>
}

export async function signIn(baseURL: string, email: string): Promise<Session> {
  const context = await playwrightRequest.newContext({ baseURL })

  try {
    const response = await context.post('/api/auth/e2e/login', {
      headers: { 'X-E2E-Token': LOGIN_TOKEN, 'Content-Type': 'application/json' },
      data: { email },
    })

    if (!response.ok()) {
      // 404 is the interesting one: the seed never made this user. Saying so
      // beats a suite that fails later on an empty dashboard.
      throw new Error(
        `The e2e login refused ${email}: HTTP ${response.status()} — ${await response.text()}. ` +
          'Has `task e2e:seed` run against this stack?',
      )
    }

    const body = (await response.json()) as Omit<Session, 'cookies'>
    const { cookies } = await context.storageState()

    return { ...body, cookies }
  } finally {
    await context.dispose()
  }
}

/**
 * Turns a session into a browser state: the server's cookies as they came, plus
 * the two localStorage keys `authProvider` reads.
 *
 * `user` is stored as the JSON string the Google callback puts in the URL —
 * `authProvider.getIdentity` and `useMercure.getUserId` both `JSON.parse` it,
 * so a plain object here would break identity and every real-time subscription.
 */
export function storageStateOf(baseURL: string, session: Session): SessionStorageState {
  return {
    cookies: session.cookies,
    origins: [
      {
        origin: new URL(baseURL).origin,
        localStorage: [
          { name: 'token', value: session.token },
          { name: 'user', value: JSON.stringify(session.user) },
        ],
      },
    ],
  }
}

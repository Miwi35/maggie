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
 * The second seeded account. It exists for one purpose: proving that a Mercure
 * update published for one user never reaches another (MAG-93 — b333376,
 * c2d3758). Journeys sign in as {@link SEED_USER_EMAIL}; this one is for
 * isolation assertions only, and carries almost no data of its own.
 */
export const OTHER_USER_EMAIL = 'e2e-other@maggie.local'

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

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

/**
 * The fourth seeded account, for the stock journey (MAG-294): telling Maggie a
 * product is nearly out writes to a grocery list and to the chat history, and
 * neither can be shared with the files that assert on theirs.
 */
export const STOCK_USER_EMAIL = 'e2e-stock@maggie.local'

/**
 * The fifth seeded account, for the approval cards journey (MAG-6): it talks to
 * Maggie and answers a held deletion, which neither the owner's chat history nor
 * the interruptions account can take.
 */
export const APPROVALS_USER_EMAIL = 'e2e-approvals@maggie.local'

/**
 * The sixth seeded account, for the savings advances journey (MAG-276): it
 * declares a salary day and a « Salaire » series, which the reference salary
 * journey, on the stock account, must not see doubled.
 */
export const TREASURY_USER_EMAIL = 'e2e-treasury@maggie.local'

/**
 * The seventh seeded account, for the recurring operations detection journey
 * (MAG-47): its rent debits would move the train de vie another file compares
 * with itself.
 */
export const RECURRING_USER_EMAIL = 'e2e-recurring@maggie.local'

/**
 * The eighth seeded account, for the internal transfers and rejections journey
 * (MAG-272): it compares the train de vie with itself, which a debit booked by
 * any other file on the same account would move.
 */
export const TRANSFERS_USER_EMAIL = 'e2e-transfers@maggie.local'

/**
 * The ninth seeded account, for the meals and the grocery list journey
 * (MAG-300): it asks Maggie to prepare the week, which tops the list up with
 * *every* recurring item due. On the neighbour, that would add the weekly rice
 * the errand journey needs to find absent.
 */
export const MEALS_USER_EMAIL = 'e2e-meals@maggie.local'

/**
 * The tenth seeded account, for the envelope alert (MAG-49): a bank sync takes
 * its « Loisirs » past 80 % and Maggie interrupts. The alert goes out once per
 * period and covers every window of its user, so no other file signs in here.
 */
export const ENVELOPE_ALERT_USER_EMAIL = 'e2e-envelope-alert@maggie.local'

/**
 * The twelfth seeded account, for the seasonality journey (MAG-494): it puts a
 * potiron and tomatoes on its list and asks Maggie what is in season — a list
 * and a chat history no other file may share.
 */
export const SEASON_USER_EMAIL = 'e2e-season@maggie.local'

/**
 * The thirteenth seeded account, for the scores journeys (MAG-417): a rent series
 * and a month of spending that only the rings and the rhythm curve read. The
 * owner's account has no recurring operation, and giving it one would move
 * what the other finance journeys compute.
 */
export const SCORES_USER_EMAIL = 'e2e-scores@maggie.local'

/**
 * The fourteenth seeded account, for the envelope adjustments journey (MAG-461):
 * it spends in the three months up to the clock's day, so on the neighbour two
 * complete months would move the lifestyle the independence counter reads.
 */
export const ADJUST_USER_EMAIL = 'e2e-adjust@maggie.local'

/**
 * The fifteenth seeded account, for the « À venir » journey (MAG-463): a salary,
 * a rent and a weekly market of next month, and the envelopes they sit in, which
 * would otherwise move the neighbours' recurring operations and projections.
 */
export const UPCOMING_USER_EMAIL = 'e2e-upcoming@maggie.local'

/**
 * The sixteenth seeded account, for the month balance (MAG-457): one held
 * envelope and one overrun, which only Dashboard › État courant reads. The
 * owner's account has no overrun, and giving it one would move the other
 * finance journeys.
 */
export const BALANCE_USER_EMAIL = 'e2e-balance@maggie.local'

/**
 * The seventeenth seeded account, for the bravo of a task done (MAG-508): a
 * shooting star comes once a day per user, so on an account other journeys
 * tick tasks of, the one the journey waits for could already have gone.
 */
export const BRAVO_USER_EMAIL = 'e2e-bravo@maggie.local'

/**
 * The eighteenth seeded account, for the constellation of the month (MAG-509):
 * four stars this month and seven last month, so the count a tick brings it to
 * (five) is exact. Another journey ticking tasks of it would move that count.
 */
export const STARS_USER_EMAIL = 'e2e-stars@maggie.local'

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

/**
 * `headers` are added to the login request: a journey that signs in on its own
 * (`pageWithOwnSession`) passes its `X-E2E-Journey`, so the login's lines are
 * filed under it. The worker's memoised logins are setup, and send none.
 */
export async function signIn(baseURL: string, email: string, headers: Record<string, string> = {}): Promise<Session> {
  const context = await playwrightRequest.newContext({ baseURL, extraHTTPHeaders: headers })

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

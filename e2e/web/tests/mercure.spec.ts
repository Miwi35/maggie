import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import {
  MERCURE_PATH,
  expectRealtimeSync,
  openMercureProbe,
  openSubscribed,
  subscribedTopics,
  userTopic,
} from '../helpers/mercure.js'
import { PreferencesPage } from '../pages/PreferencesPage.js'
import type { Theme } from '../pages/PreferencesPage.js'

/**
 * Real-time, the part of the socle MAG-93 says carries the value.
 *
 * Three regressions shipped here and none of them belonged to a feature:
 * topics and per-user scoping (b333376, c2d3758), and a relative Mercure URL
 * that made `new URL()` throw in production, killing the dashboard's live
 * updates while every test stayed green (b16916d).
 *
 * So the assertions are: one tab sees what another did, the neighbour sees
 * nothing, and the admin's own subscription is built from a relative path
 * resolved against the current origin.
 *
 * `theme` is the thing being changed throughout — not the calendar view, which
 * the calendar screen now opens on (MAG-120): flipping it would change what every
 * parallel journey sees on the owner's agenda. It is stored on
 * `UserPreference`, one of the few entities served straight from Doctrine —
 * on an Elasticsearch-backed collection the observing tab would refetch before
 * the worker had indexed anything, and the flake would say nothing about
 * Mercure.
 */

// One mutable preference, shared by every test here: they have to take turns.
test.describe.configure({ mode: 'default' })

const PREFERENCE_TOPIC = '/api/user_preferences/{id}'

// Unconditional, so a failing assertion still leaves the seeded value behind
// for the next test and the next retry. Putting it at the end of each test
// meant the first failure poisoned everything after it.
test.afterEach(async ({ api }) => {
  await setTheme(api, 'light')
})

test('one window sees what the other did, without reloading', async ({ twoWindows, api }) => {
  const { actor, observer } = twoWindows
  const actorPage = new PreferencesPage(actor)
  const observerPage = new PreferencesPage(observer)

  await actorPage.open()
  // The observer has to be listening before the actor acts, or the update it
  // is waiting for is published into a socket nobody holds yet.
  await openSubscribed(observer, () => observerPage.open())

  const before = await theme(api)
  const target = otherThan(before)

  await expectRealtimeSync(
    observer,
    () => actorPage.chooseTheme(target),
    () => observerPage.expectTheme(target),
  )
})

test('the hub delivers on the user-scoped topic the API publishes to', async ({ page, api, session }) => {
  await new PreferencesPage(page).open()

  const probe = await openMercureProbe(page, [userTopic(session.user.id, PREFERENCE_TOPIC)])
  const before = await theme(api)
  const target = otherThan(before)

  await setTheme(api, target)

  const update = await probe.waitFor((message) => typeof message.parsed?.['@id'] === 'string')
  expect(update.parsed?.theme, 'the payload must carry what changed').toBe(target)

  await probe.close()
})

test("nothing published for one user shows up on another's own topics", async ({
  page,
  api,
  session,
  otherUser,
}) => {
  await new PreferencesPage(page).open()
  await new PreferencesPage(otherUser.page).open()

  // The neighbour listening the way their browser really does: their own
  // topics, their own subscriber token.
  const neighbour = await openMercureProbe(otherUser.page, [
    userTopic(otherUser.session.user.id, PREFERENCE_TOPIC),
    userTopic(otherUser.session.user.id, '/api/tasks/{id}'),
  ])
  // The control: the same write, delivered to its owner. Without it, a silent
  // neighbour would equally well mean the publish never happened — which is
  // precisely what a 144-bit MERCURE_JWT_SECRET made it mean for a while.
  const owner = await openMercureProbe(page, [userTopic(session.user.id, PREFERENCE_TOPIC)])

  const before = await theme(api)
  const target = otherThan(before)

  await setTheme(api, target)

  await owner.waitFor((message) => message.parsed?.theme === target)
  await neighbour.expectSilence()

  await Promise.all([owner.close(), neighbour.close()])
})

test('the hub refuses a subscriber asking for another user\'s topic', async ({
  page,
  api,
  session,
  otherUser,
}) => {
  // The assertion this harness was written to make, and the one that found
  // MAG-139: updates used to be published without `private: true`, and a
  // public update reaches any subscriber whose *requested* topic matches,
  // whatever their token's `subscribe` grant says. So a user holding
  // a perfectly valid token of their own received another user's updates by
  // asking for their topic — and user ids are in every API response. Fixed
  // on main; this is what keeps it fixed.
  await new PreferencesPage(page).open()
  await new PreferencesPage(otherUser.page).open()

  const eavesdrop = await openMercureProbe(otherUser.page, [userTopic(session.user.id, PREFERENCE_TOPIC)])

  const before = await theme(api)
  await setTheme(api, otherThan(before))

  await eavesdrop.expectSilence()

  await eavesdrop.close()
})

test("the admin's own subscription is relative and user-scoped", async ({ page, session }) => {
  // b16916d: `VITE_MERCURE_PUBLIC_URL` is `/.well-known/mercure` in production
  // and `new URL()` threw on it, so real-time died there and only there. The
  // e2e build ships that same relative value, so watching what the browser
  // actually opens covers it — and the topics it asks for cover b333376.
  const subscription = page.waitForRequest((request) => request.url().includes(MERCURE_PATH), {
    timeout: 20_000,
  })

  await new PreferencesPage(page).open()

  const url = new URL((await subscription).url())
  expect(url.origin, 'the hub URL must resolve against the page origin').toBe(new URL(page.url()).origin)
  expect(url.pathname).toBe(MERCURE_PATH)
  expect(url.searchParams.has('topic'), 'a 1.0 hub answers 400 to the 0.x `topic` parameter').toBe(false)

  const topics = subscribedTopics(url)
  expect(topics.length).toBeGreaterThan(0)
  expect(topics.every((topic) => topic.startsWith(`/users/${session.user.id}/`))).toBe(true)
})

function otherThan(current: string): Theme {
  return current === 'dark' ? 'light' : 'dark'
}

async function theme(api: APIRequestContext): Promise<string> {
  const response = await api.get('/api/user_preferences/me')
  const body = (await response.json()) as { theme?: string }

  return body.theme ?? 'light'
}

async function setTheme(api: APIRequestContext, value: string): Promise<void> {
  const response = await api.patch('/api/user_preferences/me', {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { theme: value },
  })

  expect(response.ok(), `PATCH /api/user_preferences/me answered ${response.status()}`).toBe(true)
}

import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import {
  MERCURE_PATH,
  expectRealtimeSync,
  openMercureProbe,
  openSubscribed,
  userTopic,
} from '../helpers/mercure.js'
import { PreferencesPage } from '../pages/PreferencesPage.js'
import type { CalendarView } from '../pages/PreferencesPage.js'

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
 * `defaultCalendarView` is the thing being changed throughout. It is stored on
 * `UserPreference`, one of the few entities served straight from Doctrine —
 * on an Elasticsearch-backed collection the observing tab would refetch before
 * the worker had indexed anything, and the flake would say nothing about
 * Mercure.
 */

// One mutable preference, shared by every test here: they have to take turns.
test.describe.configure({ mode: 'default' })

const PREFERENCE_TOPIC = '/api/user_preferences/{id}'

test('one window sees what the other did, without reloading', async ({ twoWindows, api }) => {
  const { actor, observer } = twoWindows
  const actorPage = new PreferencesPage(actor)
  const observerPage = new PreferencesPage(observer)

  await actorPage.open()
  // The observer has to be listening before the actor acts, or the update it
  // is waiting for is published into a socket nobody holds yet.
  await openSubscribed(observer, () => observerPage.open())

  const before = await calendarView(api)
  const target = otherThan(before)

  await expectRealtimeSync(
    observer,
    () => actorPage.chooseCalendarView(target),
    () => observerPage.expectCalendarView(target),
  )

  await setCalendarView(api, before)
})

test('the hub delivers on the user-scoped topic the API publishes to', async ({ page, api, session }) => {
  await new PreferencesPage(page).open()

  const probe = await openMercureProbe(page, [userTopic(session.user.id, PREFERENCE_TOPIC)])
  const before = await calendarView(api)
  const target = otherThan(before)

  await setCalendarView(api, target)

  const update = await probe.waitFor((message) => typeof message.parsed?.['@id'] === 'string')
  expect(update.parsed?.defaultCalendarView, 'the payload must carry what changed').toBe(target)

  await probe.close()
  await setCalendarView(api, before)
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

  const before = await calendarView(api)
  const target = otherThan(before)

  await setCalendarView(api, target)

  await owner.waitFor((message) => message.parsed?.defaultCalendarView === target)
  await neighbour.expectSilence()

  await Promise.all([owner.close(), neighbour.close()])
  await setCalendarView(api, before)
})

test('the hub refuses a subscriber asking for another user\'s topic', async ({
  page,
  api,
  session,
  otherUser,
}) => {
  // Expected to fail today, and that is the point: it holds the contract the
  // fix has to satisfy, and it turns red — "passed unexpectedly" — the moment
  // somebody fixes it, so the bug cannot be closed without noticing.
  //
  // What it found: every update is published without `private: true`, and a
  // public update is delivered to any subscriber whose *requested* topic
  // matches, whatever their token's `mercure.subscribe` claim says. So a user
  // holding a perfectly valid token of their own receives another user's
  // updates by asking for their topic — and user ids are in every API
  // response. This is c2d3758's family, still open: MAG-139.
  test.fail()

  await new PreferencesPage(page).open()
  await new PreferencesPage(otherUser.page).open()

  const eavesdrop = await openMercureProbe(otherUser.page, [userTopic(session.user.id, PREFERENCE_TOPIC)])

  const before = await calendarView(api)
  await setCalendarView(api, otherThan(before))

  await eavesdrop.expectSilence()

  await eavesdrop.close()
  await setCalendarView(api, before)
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
  expect(url.searchParams.getAll('topic').every((topic) => topic.startsWith(`/users/${session.user.id}/`))).toBe(
    true,
  )
})

function otherThan(current: string): CalendarView {
  return current === 'month' ? 'day' : 'month'
}

async function calendarView(api: APIRequestContext): Promise<string> {
  const response = await api.get('/api/user_preferences/me')
  const body = (await response.json()) as { defaultCalendarView?: string }

  return body.defaultCalendarView ?? 'week'
}

async function setCalendarView(api: APIRequestContext, view: string): Promise<void> {
  const response = await api.patch('/api/user_preferences/me', {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { defaultCalendarView: view },
  })

  expect(response.ok(), `PATCH /api/user_preferences/me answered ${response.status()}`).toBe(true)
}

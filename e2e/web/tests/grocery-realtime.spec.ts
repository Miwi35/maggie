import { test, expect, seedId } from '../fixtures/index.js'
import { expectRealtimeSync, openMercureProbe, openSubscribed, userTopic } from '../helpers/mercure.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * The grocery list in two places at once — MAG-101's first target.
 *
 * The ticket names this as the thing that has already broken, twice:
 *
 *   6ba9859  the update carried no items, so a client had to re-read a
 *            collection served from an Elasticsearch index that had not caught
 *            up yet. The phone in the shop showed the list as it was before.
 *   afc1a70  the Mercure middleware read only `Create`/`Update`/`Delete`
 *            commands, so `Add` published nothing whatsoever.
 *
 * Two windows rather than two tabs of one context: headless Chromium freezes a
 * hidden tab, and a frozen tab stops rendering the moment the other is acted
 * on — both would then sit unchanged and the test would teach nothing about
 * real time. `expectRealtimeSync` adds the other half of that promise: it fails
 * if the observing tab navigated, because a tab that reloaded would show the
 * new line whether the hub works or not. That is exactly how `b16916d` reached
 * production.
 *
 * Nothing here ticks a line. `grocery-errand.spec.ts` owns ticking, because the
 * admin's end-of-errand deletes every ticked line on a list the whole suite
 * shares.
 *
 * Per-user scoping of the hub, and the admin's relative Mercure URL, are
 * asserted once for the whole app in `mercure.spec.ts`; the mobile client's
 * half of this — its topics and its DTOs, the two contract regressions the
 * ticket lists (`305ff70`, `0a281a7`) — is pinned by the contract suite
 * (MAG-104) until MAG-98 brings Maestro.
 */

interface Line {
  id: string
  label: string
  quantity: number | null
  checked: boolean
  store: { id: string; name: string; visitOrder: number } | null
  product: { id: string; name: string; category: string } | null
}

const GROCERY_TOPIC = '/api/grocery_lists/{id}'
const STORE = 'Supermarché Leclerc'

/** A label the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} essai ${retry}`
}

test('a line written in one window appears in the other without a reload', async ({ twoWindows, session }) => {
  const { actor, observer } = twoWindows
  const label = perAttempt('Levure MAG-101')

  const acting = new GroceryListPage(actor)
  const watching = new GroceryListPage(observer)

  await acting.open()
  // Subscribed, not merely loaded: the admin opens its EventSource from an
  // effect, so the page is on screen a beat before it is listening — and an
  // update published in that beat is never delivered. The topic is named
  // because the shell opens several and they register in whatever order their
  // effects run.
  await openSubscribed(observer, () => watching.open(), userTopic(session.user.id, GROCERY_TOPIC))

  await expect(watching.line(label), 'the line is on the list before anyone wrote it').toHaveCount(0)

  await expectRealtimeSync(
    observer,
    () => acting.addItem(label, { quantity: 3, store: STORE }),
    async () => {
      await expect(watching.line(label)).toBeVisible()
      // Drawn in the right aisle, from the payload alone. The grouping is the
      // part a client cannot rebuild without the items — which is what
      // `6ba9859` left it to do.
      await expect(watching.line(label)).toHaveAttribute('data-store', STORE)
    },
  )
})

test('the update carries the whole list, so no client has to re-read a stale index', async ({
  page,
  session,
}) => {
  // 6ba9859, stated as an assertion on the payload: the items, their labels,
  // their quantity, and the nested `store` and `product` objects the clients
  // group and label by. A payload holding only `@id` satisfies every
  // "something arrived" test ever written, and leaves every client refetching.
  const label = perAttempt('Semoule MAG-101')

  const grocery = new GroceryListPage(page)
  await grocery.open()

  const probe = await openMercureProbe(page, [
    userTopic(session.user.id, `/api/grocery_lists/${seedId('e2e_grocery_list')}`),
  ])

  try {
    await grocery.addItem(label, { quantity: 2, store: STORE })

    const update = await probe.waitFor((message) =>
      ((message.parsed?.items ?? []) as Line[]).some((item) => item.label === label),
    )

    const items = (update.parsed?.items ?? []) as Line[]
    const line = items.find((item) => item.label === label)

    expect(line?.quantity).toBe(2)
    expect(line?.checked).toBe(false)
    // Nested objects, not IRIs. The mobile model expected `product` as an IRI
    // where the API sends an object and silently read its own default
    // (`0a281a7`), and the admin groups by `store.visitOrder` — a string here
    // would make every line "Non assigné".
    expect(line?.store?.name).toBe(STORE)
    expect(typeof line?.store?.visitOrder).toBe('number')
    expect(line?.product?.name).toBe(label)

    // And the rest of the list with it: an update that carried only the line
    // that changed would still force a refetch to draw the others.
    expect(items.map((item) => item.label)).toEqual(expect.arrayContaining(['Tomate', 'Pâtes complètes']))
  } finally {
    await probe.close()
  }
})

test('adding a line goes through the endpoint the API really exposes', async ({ page }) => {
  // 305ff70: the admin posted to `/api/grocery_items`, a collection that does
  // not exist — `GroceryItem` is not an ApiResource, and the only write path is
  // `POST /api/grocery/add-item`. The screen showed the dialog closing and
  // nothing else, and no test noticed because none watched the wire.
  const label = perAttempt('Pois chiches MAG-101')
  const posts: string[] = []

  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/')) {
      posts.push(new URL(request.url()).pathname)
    }
  })

  const grocery = new GroceryListPage(page)
  await grocery.open()
  await grocery.addItem(label, { quantity: 1 })

  await expect(grocery.line(label)).toBeVisible()

  expect(posts, 'the add dialog never reached the API').toContain('/api/grocery/add-item')
  expect(posts, 'the admin posted to a collection the API does not expose — 305ff70').not.toContain(
    '/api/grocery_items',
  )
})

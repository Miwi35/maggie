import { test, expect, seedId, seedDate } from '../fixtures/index.js'
import type { APIRequestContext, Page } from '@playwright/test'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { expectRealtimeSync, openMercureProbe, openSubscribed, userTopic } from '../helpers/mercure.js'
import type { MercureProbe } from '../helpers/mercure.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * The errand: the aisles, the tick box, the end of the trip, the shop that
 * turned out to be closed, and what comes back on its own.
 *
 * This is the half of MAG-101 the owner does standing in a supermarket, and the
 * half the ticket says has already broken twice, each time for a different
 * reason:
 *
 *   6ba9859  the Mercure payload carried no items, so every client had to
 *            re-read a collection served from an Elasticsearch index that had
 *            not caught up. The phone in the shop showed the list as it was
 *            before.
 *   afc1a70  the Mercure and Elasticsearch middlewares recognised `Create`,
 *            `Update` and `Delete` only — so `Add`, `Check`, `End`, `Move`,
 *            `Remove` and `Generate` published nothing whatsoever. Every write
 *            a shopper actually makes was silent.
 *
 * ## Why this file runs as the second account, and serially
 *
 * Both of those are assertions about *publication*, and publication cannot be
 * asserted on a shared list. Every writer publishes the **whole** list on one
 * topic, `/users/{id}/api/grocery_lists/{listId}`, so "a payload arrived
 * showing the new state" is satisfied by another worker's add even with the
 * middleware mute — the hole that would let `afc1a70` back in through a test
 * written to catch it. `since()` therefore demands that the payload be the
 * **first** message after the action, and that is only sound on a list nobody
 * else writes to.
 *
 * So this file signs in as the second seeded account, whose grocery list the
 * seed gives it and which nothing else in the suite touches, and runs its tests
 * in order. Ending an errand needs the same thing for a blunter reason: it
 * deletes *every* ticked line on the list.
 *
 * Talking to Maggie as that account is part of the same decision:
 * `GET /agent/messages` is scoped to the user and returns the last twenty, and
 * `chat.spec.ts` says in so many words that six more exchanges on the owner's
 * history would push its own question off the page.
 *
 * `End`, `Move` and `Generate` have no screen at all — the admin's own
 * "Terminer les courses" deletes ticked lines one by one through `Remove`, and
 * nothing anywhere calls `move_to_fallback` or `generate_grocery_list`. Asking
 * is the only path, so the last three tests ask: scripted model, real tool
 * loop, real MCP server (MAG-95).
 *
 * The mobile half of real-time — its topics and its DTOs, the two contract
 * regressions the ticket lists (`305ff70`, `0a281a7`) — is pinned by the
 * contract suite (MAG-104) until MAG-98 brings Maestro.
 */

test.describe.configure({ mode: 'serial' })

interface Line {
  id: string
  label: string
  quantity: number | null
  checked: boolean
  source: string
  store: { id: string; name: string; visitOrder: number } | null
  product: { id: string; name: string; category: string } | null
}

interface StoredList {
  '@id': string
  items: Line[]
}

const SHOPS = {
  market: 'Halles du voisin',
  corner: 'Épicerie du coin',
  unassigned: 'Non assigné',
}

const GROCERY_TOPIC = '/api/grocery_lists/{id}'

/** A label the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} essai ${retry}`
}

async function storedList(api: APIRequestContext): Promise<StoredList> {
  const [list] = await getCollection<StoredList>(api, '/api/grocery_lists')

  expect(list, 'the shopper has no grocery list — did the seed run?').toBeDefined()

  return list
}

/**
 * Polls the API until the line labelled `label` satisfies `match`.
 *
 * Polled rather than read once: every write is dispatched to RabbitMQ and
 * indexed before the collection reflects it, so reading once is how a working
 * feature gets reported as broken (see `waitForIndexed`).
 */
async function expectLine(
  api: APIRequestContext,
  label: string,
  match: (line: Line | undefined) => boolean,
  what: string,
): Promise<void> {
  await expect
    .poll(async () => match((await storedList(api)).items.find((item) => item.label === label)), {
      message: what,
      timeout: 30_000,
    })
    .toBe(true)
}

/**
 * A window on the hub that starts now, and only accepts the *next* message.
 *
 * "The next one", not "any later one", and that is the whole point. A matcher
 * over every message since the snapshot is satisfied by somebody else's write —
 * every payload carries the whole list — so a mute middleware would still pass.
 * Demanding the first message makes the assertion exact; it is sound here
 * because this file owns the list it reads (see the header).
 *
 * The payload is read directly and never followed by a fetch: that a Mercure
 * update carries the whole list is the promise `6ba9859` broke.
 */
async function since(
  probe: MercureProbe,
): Promise<(match: (items: Line[]) => boolean, what: string) => Promise<Line[]>> {
  const alreadySeen = (await probe.messages()).length

  return async (match, what) => {
    let next: Line[] | null = null

    await expect
      .poll(
        async () => {
          const fresh = (await probe.messages()).slice(alreadySeen)

          if (0 === fresh.length) {
            return false
          }

          next = (fresh[0].parsed?.items ?? null) as Line[] | null

          return true
        },
        { message: `${what} (nothing was published at all)`, timeout: 20_000 },
      )
      .toBe(true)

    const items = next as Line[] | null
    expect(items, `${what} — the update carried no items (6ba9859)`).not.toBeNull()
    expect(match(items as Line[]), what).toBe(true)

    return items as Line[]
  }
}

const labelled = (label: string) => (items: Line[]) => items.some((item) => item.label === label)
const ticked = (label: string) => (items: Line[]) => items.some((item) => item.label === label && item.checked)
const absent = (label: string) => (items: Line[]) => !items.some((item) => item.label === label)

/** The probe every test here opens on its own list, before its first write. */
async function probeOn(page: Page, userId: string): Promise<MercureProbe> {
  return openMercureProbe(page, [userTopic(userId, `/api/grocery_lists/${seedId('e2e_other_grocery_list')}`)])
}

test('the list is grouped by shop, in the order the shopper walks them', async ({ otherUser }) => {
  const grocery = new GroceryListPage(otherUser.page)
  await grocery.open()

  const order = await grocery.storeOrder()

  // The shopper's two shops, in visit order, and the group for lines nobody
  // gave a shop last — the view sorts that one on PHP_INT_MAX rather than on
  // its name. Asserted as an exact list, which this file can do and a journey
  // on the shared list could not.
  expect(order).toEqual([SHOPS.market, SHOPS.corner, SHOPS.unassigned])

  // What makes the grouping worth anything: a line sits where its product's
  // preferred shop says, and a line with no product sits nowhere. `data-store`
  // is empty on the latter — the group it is drawn under is the view's own
  // label for "nowhere", not a shop the API knows.
  await expect(grocery.line('Poireau du voisin')).toHaveAttribute('data-store', SHOPS.market)
  await expect(grocery.line('Sacs du voisin')).toHaveAttribute('data-store', '')
})

test('a shopper ticks what is in the trolley, then ends the errand', async ({ otherUser }) => {
  const bought = perAttempt('Câpres MAG-101')
  const skipped = perAttempt('Cornichons MAG-101')
  const { api } = otherUser

  const grocery = new GroceryListPage(otherUser.page)
  await grocery.open()

  // Opened before the first write, and that order is not optional: nothing
  // published before the hub accepted the subscription is ever delivered, so a
  // probe opened afterwards is silent for a reason with nothing to teach.
  const probe = await probeOn(otherUser.page, otherUser.session.user.id)

  try {
    // --- Two lines, written the way the owner writes them -------------------
    const afterAdd = await since(probe)
    await grocery.addItem(bought, { quantity: 1, store: SHOPS.market })

    const published = await afterAdd(labelled(bought), 'adding a line published nothing — afc1a70')
    const line = published.find((item) => item.label === bought)
    expect(line?.checked, 'a line arrives on the list already in the trolley').toBe(false)
    expect(line?.quantity, 'the quantity typed in the dialog is not in the payload — 6ba9859').toBe(1)

    await grocery.addItem(skipped, { quantity: 2, store: SHOPS.market })
    await expect(grocery.line(skipped)).toBeVisible()

    // --- In the shop: one goes in the trolley -------------------------------
    const afterTick = await since(probe)
    await grocery.tickBox(bought).click()

    await afterTick(ticked(bought), 'ticking a line published nothing — afc1a70')
    await expect(grocery.tickBox(bought)).toBeChecked()
    await expectLine(api, bought, (item) => true === item?.checked, 'the ticked line in the database')

    // The line left alone stays as it was: ticking is one line's business.
    await expectLine(api, skipped, (item) => false === item?.checked, 'the untouched line still to buy')

    // --- At the till: the errand ends ---------------------------------------
    const afterErrand = await since(probe)
    await grocery.endErrand()

    // What was bought is carried home and off the list; what was not is
    // offered, so the owner can decide whether it still matters.
    await expect(grocery.remainingLine(skipped)).toBeVisible()
    await expect(grocery.remainingLine(bought), 'a line already in the trolley is offered again').toHaveCount(0)

    await afterErrand(absent(bought), 'clearing the trolley published nothing — afc1a70')
    await expectLine(api, bought, (item) => undefined === item, 'the bought line gone from the database')

    // --- "Retirer": the owner gives up on the rest --------------------------
    const afterDrop = await since(probe)
    await grocery.remainingLine(skipped).getByRole('button', { name: 'Retirer' }).click()

    await expect(grocery.remainingLine(skipped)).toHaveCount(0)
    await afterDrop(absent(skipped), 'dropping a line published nothing — afc1a70')
    await expectLine(api, skipped, (item) => undefined === item, 'the dropped line gone from the database')
  } finally {
    await probe.close()
  }
})

test('the update carries the whole list, so no client has to re-read a stale index', async ({ otherUser }) => {
  // 6ba9859, stated as an assertion on the payload: the items, their labels,
  // their quantity, and the nested `store` and `product` objects the clients
  // group and label by. A payload holding only `@id` satisfies every
  // "something arrived" test ever written, and leaves every client refetching.
  const label = perAttempt('Semoule MAG-101')

  const grocery = new GroceryListPage(otherUser.page)
  await grocery.open()

  const probe = await probeOn(otherUser.page, otherUser.session.user.id)

  try {
    const afterAdd = await since(probe)
    await grocery.addItem(label, { quantity: 2, store: SHOPS.market })

    const items = await afterAdd(labelled(label), 'adding a line published nothing — afc1a70')
    const line = items.find((item) => item.label === label)

    expect(line?.quantity).toBe(2)
    expect(line?.checked).toBe(false)
    // Nested objects, not IRIs. The mobile model expected `product` as an IRI
    // where the API sends an object and silently read its own default
    // (`0a281a7`), and the admin groups by `store.visitOrder` — a string here
    // would make every line "Non assigné".
    expect(line?.store?.name).toBe(SHOPS.market)
    expect(typeof line?.store?.visitOrder).toBe('number')
    expect(line?.product?.name).toBe(label)

    // And the rest of the list with it: an update carrying only the line that
    // changed would still force a refetch to draw the others.
    expect(items.map((item) => item.label)).toEqual(
      expect.arrayContaining(['Poireau du voisin', 'Sacs du voisin']),
    )
  } finally {
    await probe.close()
  }
})

test('a line written in one window appears in the other without a reload', async ({ otherUser }) => {
  const label = perAttempt('Levure MAG-101')
  const observerPage = await otherUser.secondWindow()

  const acting = new GroceryListPage(otherUser.page)
  const watching = new GroceryListPage(observerPage)

  await acting.open()
  // Subscribed, not merely loaded: the admin opens its EventSource from an
  // effect, so the page is on screen a beat before it is listening — and an
  // update published in that beat is never delivered. The topic is named
  // because the shell opens several and they register in whatever order their
  // effects run.
  await openSubscribed(
    observerPage,
    () => watching.open(),
    userTopic(otherUser.session.user.id, GROCERY_TOPIC),
  )

  await expect(watching.line(label), 'the line is on the list before anyone wrote it').toHaveCount(0)

  // The navigation guard is the point: a tab that reloaded would show the new
  // line whether the hub works or not, which is how `b16916d` reached
  // production.
  await expectRealtimeSync(
    observerPage,
    () => acting.addItem(label, { quantity: 3, store: SHOPS.market }),
    async () => {
      await expect(watching.line(label)).toBeVisible()
      // Drawn in the right aisle, from the payload alone — the part a client
      // cannot rebuild without the items.
      await expect(watching.line(label)).toHaveAttribute('data-store', SHOPS.market)
    },
  )
})

test('adding a line goes through the endpoint the API really exposes', async ({ otherUser }) => {
  // 305ff70: the admin posted to `/api/grocery_items`, a collection that does
  // not exist — `GroceryItem` is not an ApiResource, and the only write path is
  // `POST /api/grocery/add-item`. The screen showed the dialog closing and
  // nothing else, and no test noticed because none watched the wire.
  const label = perAttempt('Pois chiches MAG-101')
  const posts: string[] = []

  otherUser.page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/')) {
      posts.push(new URL(request.url()).pathname)
    }
  })

  const grocery = new GroceryListPage(otherUser.page)
  await grocery.open()
  await grocery.addItem(label, { quantity: 1 })

  await expect(grocery.line(label)).toBeVisible()

  expect(posts, 'the add dialog never reached the API').toContain('/api/grocery/add-item')
  expect(posts, 'the admin posted to a collection the API does not expose — 305ff70').not.toContain(
    '/api/grocery_items',
  )
})

test('"I have finished the shopping" clears the trolley, and leaves the rest', async ({ otherUser }) => {
  // `end_errand` is MCP-only: the admin's own button does the same thing one
  // `Remove` at a time, so this tool — and the `End` command `afc1a70` left
  // mute — is only ever reached by asking.
  const bought = perAttempt('Anchois MAG-101')
  const kept = perAttempt('Olives MAG-101')
  const { api } = otherUser

  const grocery = new GroceryListPage(otherUser.page)
  await grocery.open()

  await grocery.addItem(bought, { quantity: 1, store: SHOPS.market })
  await grocery.addItem(kept, { quantity: 1, store: SHOPS.market })
  await grocery.tickBox(bought).click()
  await expectLine(api, bought, (item) => true === item?.checked, 'the ticked line before asking Maggie')

  const probe = await probeOn(otherUser.page, otherUser.session.user.id)

  try {
    const afterErrand = await since(probe)
    const chat = new ChatPanel(otherUser.page)
    const events = await chat.send("J'ai terminé mes courses")

    expect(calledTools(events)).toContain('end_errand')
    // The round's own outcome, not only that it was asked for: a tool that
    // threw is reported here, and `TOOL_CALL_START` alone would be green
    // either way.
    expect(toolResults(events)).toEqual(
      expect.arrayContaining([expect.objectContaining({ toolName: 'end_errand', status: 'success' })]),
    )

    await afterErrand(absent(bought), 'the End command published nothing — afc1a70')

    // The data, not her wording: the fake does not read tool results, so the
    // scripted sentence would say "c'est rangé" whatever happened.
    await expectLine(api, bought, (item) => undefined === item, 'the bought line carried home')
    await expectLine(api, kept, (item) => false === item?.checked, 'the line still to buy, left alone')
  } finally {
    await probe.close()
  }
})

test('a shop that turned out to be closed sends its items to their fallback', async ({ otherUser }) => {
  // The fallback store has no UI anywhere — not in the admin, not on mobile —
  // so asking Maggie is the only path a journey can take. The id is in what
  // the shopper says because a ULID differs on every seed and no fixture can
  // name one; `43-grocery-fallback.yaml` captures it and carries it into the
  // real call (see agent/fixtures/fake-llm/README.md on ids).
  const market = seedId('e2e_other_store_market')
  const { api } = otherUser

  const grocery = new GroceryListPage(otherUser.page)
  await grocery.open()

  // The seeded leek is the one line of this shopper's whose product carries a
  // fallback: the market by preference, the corner shop when it is shut. The
  // control is written below rather than assumed — a handler that moved
  // *everything*, or nothing, would pass on the leek alone.
  await expectLine(api, 'Poireau du voisin', (item) => item?.store?.name === SHOPS.market, 'the leek before the move')
  await expectLine(api, 'Sacs du voisin', (item) => null === item?.store, 'the control line, in no shop')

  const probe = await probeOn(otherUser.page, otherUser.session.user.id)

  try {
    const afterMove = await since(probe)
    const chat = new ChatPanel(otherUser.page)
    const events = await chat.send(`Le magasin ${market} est fermé, déplace ce qu'il me faut ailleurs`)

    expect(calledTools(events)).toContain('move_to_fallback')
    expect(toolResults(events)).toEqual(
      expect.arrayContaining([expect.objectContaining({ toolName: 'move_to_fallback', status: 'success' })]),
    )

    await afterMove(
      (items) => items.some((item) => item.label === 'Poireau du voisin' && item.store?.name === SHOPS.corner),
      'the Move command published nothing — afc1a70',
    )

    await expectLine(
      api,
      'Poireau du voisin',
      (item) => item?.store?.name === SHOPS.corner,
      'the leek moved to its fallback shop',
    )
    await expectLine(
      api,
      'Sacs du voisin',
      (item) => null === item?.store,
      'a line with no fallback was moved anyway',
    )

    // And the screen says so without being told twice: the aisle is read from
    // the payload the move published.
    await expect(grocery.line('Poireau du voisin')).toHaveAttribute('data-store', SHOPS.corner)
  } finally {
    await probe.close()
  }
})

test('asking Maggie to prepare the week puts the recurring items on the list, once', async ({ otherUser }) => {
  // `generate_grocery_list` is the only path a `RecurringGroceryItem` ever
  // takes to the list: no cron, no screen, no button. It is also the last of
  // the commands `afc1a70` left mute.
  //
  // A window six weeks out, with no meal in it: the only thing generation can
  // add there is what comes back on its own, which is what this test is about.
  const from = seedDate(40)
  const to = seedDate(41)
  const rice = 'Riz du voisin'
  const { api } = otherUser

  const grocery = new GroceryListPage(otherUser.page)
  await grocery.open()

  // The seed deliberately leaves the weekly rice *off* the list, so its
  // appearance is the whole assertion.
  await expect(grocery.line(rice)).toHaveCount(0)

  const probe = await probeOn(otherUser.page, otherUser.session.user.id)

  try {
    const afterGenerate = await since(probe)
    const chat = new ChatPanel(otherUser.page)
    const events = await chat.send(`Prépare les courses du ${from} au ${to}`)

    expect(calledTools(events)).toContain('generate_grocery_list')
    expect(toolResults(events)).toEqual(
      expect.arrayContaining([expect.objectContaining({ toolName: 'generate_grocery_list', status: 'success' })]),
    )

    await afterGenerate(labelled(rice), 'the Generate command published nothing — afc1a70')
    await expectLine(api, rice, (item) => item?.source === 'recurring', 'the line does not say where it came from')
    await expectLine(api, rice, (item) => item?.quantity === 500, 'the recurring quantity')

    // Asked twice, and the answer is the same list: generation is a top-up, not
    // an append. It used to double the shopping (MAG-116), and a shopper asking
    // again because Maggie was slow to answer is the normal case.
    const again = await chat.send(`Prépare les courses du ${from} au ${to}`)
    expect(calledTools(again)).toContain('generate_grocery_list')

    await expect
      .poll(async () => (await storedList(api)).items.filter((item) => item.label === rice).length, {
        message: 'generating twice doubled the shopping',
        timeout: 30_000,
      })
      .toBe(1)
  } finally {
    await probe.close()
  }
})

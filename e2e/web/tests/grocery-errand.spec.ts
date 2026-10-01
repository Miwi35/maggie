import { test, expect, seedId } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { calledTools, toolResults } from '../helpers/agui.js'
import { getCollection } from '../helpers/api.js'
import { openMercureProbe, userTopic } from '../helpers/mercure.js'
import type { MercureProbe } from '../helpers/mercure.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * The errand itself: the tick box in the shop, the end of the trip, and the
 * shop that turned out to be closed.
 *
 * This is the half of MAG-101 the owner does standing in a supermarket, and the
 * half the ticket says has already broken, twice, both times in real time and
 * each time for a different reason:
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
 * So the probe is not decoration: the trip runs with one open, and each step
 * asserts on **what the hub sent for that step** — which is what `since()` is
 * for. Without it, "the line is gone" would be satisfied by a payload
 * published before the line ever existed, and the test would pass with the
 * middleware mute.
 *
 * `End` and `Move` have no screen at all. The admin's own "Terminer les
 * courses" deletes the ticked lines one by one through `Remove`, and nothing
 * anywhere calls `move_to_fallback` — so the only path to them is asking
 * Maggie, which is what the last two tests do, on the real MCP server with the
 * model scripted (MAG-95).
 *
 * **Serial, and that is load-bearing.** Ending an errand deletes *every*
 * ticked line on the list, and the whole suite shares one list — so the file
 * that ticks has to be the only one that ticks, and its own tests must not
 * overlap either. Everything else addresses lines by a label of its own and
 * asserts no counts.
 */

test.describe.configure({ mode: 'serial' })

interface Line {
  id: string
  label: string
  checked: boolean
  quantity: number | null
  store: { id: string; name: string } | null
}

interface StoredList {
  '@id': string
  items: Line[]
}

const STORES = {
  supermarket: 'Supermarché Leclerc',
  greengrocer: 'Primeur du marché',
}

/** A label the current attempt alone will write — CI retries once without reseeding. */
function perAttempt(base: string): string {
  const { retry } = test.info()

  return 0 === retry ? base : `${base} essai ${retry}`
}

async function storedList(api: APIRequestContext): Promise<StoredList> {
  const [list] = await getCollection<StoredList>(api, '/api/grocery_lists')

  expect(list, 'the signed-in user has no grocery list — did the seed run?').toBeDefined()

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
 * A window on the hub that starts now.
 *
 * Returns a waiter over the messages published *after* this call, so every step
 * is judged on its own update. The payload is read directly and never followed
 * by a fetch: that a Mercure update carries the whole list is the promise
 * `6ba9859` broke.
 */
async function since(
  probe: MercureProbe,
): Promise<(match: (items: Line[]) => boolean, what: string) => Promise<Line[]>> {
  const alreadySeen = (await probe.messages()).length

  return async (match, what) => {
    let matched: Line[] = []

    await expect
      .poll(
        async () => {
          const fresh = (await probe.messages()).slice(alreadySeen)

          for (const message of fresh) {
            const items = (message.parsed?.items ?? null) as Line[] | null

            if (null !== items && match(items)) {
              matched = items

              return true
            }
          }

          return false
        },
        { message: what, timeout: 20_000 },
      )
      .toBe(true)

    return matched
  }
}

const labelled = (label: string) => (items: Line[]) => items.some((item) => item.label === label)
const ticked = (label: string) => (items: Line[]) => items.some((item) => item.label === label && item.checked)
const absent = (label: string) => (items: Line[]) => !items.some((item) => item.label === label)

test('a shopper ticks what is in the trolley, then ends the errand', async ({ page, api, session }) => {
  const bought = perAttempt('Câpres MAG-101')
  const skipped = perAttempt('Cornichons MAG-101')

  const grocery = new GroceryListPage(page)
  await grocery.open()

  // Opened before the first write, and that order is not optional: nothing
  // published before the hub accepted the subscription is ever delivered, so a
  // probe opened afterwards is silent for a reason with nothing to teach.
  const probe = await openMercureProbe(page, [
    userTopic(session.user.id, `/api/grocery_lists/${seedId('e2e_grocery_list')}`),
  ])

  try {
    // --- Two lines, written the way the owner writes them -------------------
    const afterAdd = await since(probe)
    await grocery.addItem(bought, { quantity: 1, store: STORES.supermarket })

    const published = await afterAdd(labelled(bought), 'adding a line published nothing — afc1a70')
    const line = published.find((item) => item.label === bought)
    expect(line?.checked, 'a line arrives on the list already in the trolley').toBe(false)
    expect(line?.quantity, 'the quantity typed in the dialog is not in the payload — 6ba9859').toBe(1)

    await grocery.addItem(skipped, { quantity: 2, store: STORES.supermarket })
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

test('"I have finished the shopping" clears the trolley, and says what is left', async ({ page, api }) => {
  // `end_errand` is MCP-only: the admin's own button does the same thing one
  // `Remove` at a time, so this tool — and the `End` command `afc1a70` left
  // mute — is only ever reached by asking. Scripted model, real tool loop,
  // real MCP server (MAG-95).
  const bought = perAttempt('Anchois MAG-101')
  const kept = perAttempt('Olives MAG-101')

  const grocery = new GroceryListPage(page)
  await grocery.open()

  await grocery.addItem(bought, { quantity: 1, store: STORES.supermarket })
  await grocery.addItem(kept, { quantity: 1, store: STORES.supermarket })
  await grocery.tickBox(bought).click()
  await expectLine(api, bought, (item) => true === item?.checked, 'the ticked line before asking Maggie')

  const chat = new ChatPanel(page)
  const events = await chat.send("J'ai terminé mes courses")

  expect(calledTools(events)).toContain('end_errand')
  // The round's own outcome, not only that it was asked for: a tool that threw
  // is reported here, and `TOOL_CALL_START` alone would be green either way.
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'end_errand', status: 'success' })]),
  )

  // The data, not her wording: the fake does not read tool results, so the
  // scripted sentence would say "c'est rangé" whatever happened.
  await expectLine(api, bought, (item) => undefined === item, 'the bought line carried home')
  await expectLine(api, kept, (item) => false === item?.checked, 'the line still to buy, left alone')
})

test('a shop that turned out to be closed sends its items to their fallback', async ({ page, api }) => {
  // The fallback store has no UI anywhere — not in the admin, not on mobile —
  // so asking Maggie is the only path a journey can take. The id is in what
  // the owner says because a ULID differs on every seed and no fixture can
  // name one; `43-grocery-fallback.yaml` captures it and carries it into the
  // real call (see agent/fixtures/fake-llm/README.md on ids).
  const greengrocer = seedId('e2e_store_greengrocer')
  const noFallback = perAttempt('Radis MAG-101')

  const grocery = new GroceryListPage(page)
  await grocery.open()

  // The control, written first: a line in the same shop whose product has no
  // fallback at all. Without it, a handler that moved *everything* somewhere —
  // or nothing — would pass on the tomato alone.
  await grocery.addItem(noFallback, { quantity: 1, store: STORES.greengrocer })
  await expectLine(api, noFallback, (item) => item?.store?.name === STORES.greengrocer, 'the control line')

  // The seeded tomato is the one line whose product carries a fallback: the
  // greengrocer by preference, the supermarket when it is shut.
  await expectLine(api, 'Tomate', (item) => item?.store?.name === STORES.greengrocer, 'the tomato before the move')

  const chat = new ChatPanel(page)
  const events = await chat.send(`Le magasin ${greengrocer} est fermé, déplace ce qu'il me faut ailleurs`)

  expect(calledTools(events)).toContain('move_to_fallback')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'move_to_fallback', status: 'success' })]),
  )

  await expectLine(
    api,
    'Tomate',
    (item) => item?.store?.name === STORES.supermarket,
    'the tomato moved to its fallback shop',
  )
  await expectLine(
    api,
    noFallback,
    (item) => item?.store?.name === STORES.greengrocer,
    'a line whose product has no fallback was moved anyway',
  )

  // And the screen says so without being told twice: the aisle is read from
  // the payload the move published.
  await expect(grocery.line('Tomate')).toHaveAttribute('data-store', STORES.supermarket)
})

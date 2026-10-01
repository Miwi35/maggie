import { test, expect } from '../fixtures/index.js'
import {
  assistantText,
  calledTools,
  contextAction,
  contextLabel,
  countEvents,
  deltaCount,
  isUnscripted,
  messageIds,
  toolResults,
} from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed } from '../helpers/mercure.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * Talking to Maggie from the browser (MAG-97, then MAG-99).
 *
 * `LLM_PROVIDER=fake` (MAG-95) scripts the model's side and nothing else: the
 * AG-UI gateway, the tool loop and the MCP client are the production ones. So
 * what is asserted here is the chain, not the prose — which tool ran, that the
 * answer streamed rather than arrived whole, that the write reached the
 * database. Judgement is the eval suite's job, on the real model.
 *
 * MAG-93 counted ten past regressions in this module and none of them was
 * about what Maggie said. Three of them are `176c40c`, which fixed the same
 * symptom — the same message shown twice — in three independent places: a
 * React StrictMode double-invoke of an impure state updater, a tool loop that
 * emitted one `TEXT_MESSAGE_START`/`END` cycle per round, and the user's own
 * message doubled in the model's context. So this file counts: one message id
 * per run, one bubble per message, and the same again after a reload. A fourth
 * (`a7b08cf`) had the agent writing tool *markup as text* when the MCP list
 * came up empty at boot — which looks like activity and writes nothing, so the
 * tool tests here end on the database rather than on the stream.
 *
 * Serial, because the conversation is stateful: the context router opens a
 * context on the first message and every later one joins it, until a message
 * deliberately changes the subject. The last test leans on that on purpose — a
 * thread has to be long before Maggie summarizes it (MAG-11), and the tests
 * above are what makes it long.
 *
 * And `retries: 0`, which the rest of the suite does not do. A serial group
 * replays whole, and nothing reseeds between the attempts — so the second one
 * starts on the conversation the first one wrote: the history already holds
 * the messages the counts below expect once, 2099 already holds an
 * appointment, and a second "Budget e2e" context is opened. Every one of those
 * fails as a duplicate, which is exactly the bug this file exists to catch, on
 * a run where nothing is wrong. A flake here has to read as a flake.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const AGENDA_QUESTION = "Qu'est-ce que j'ai de prévu aujourd'hui ?"
const AGENDA_ANSWER = 'Vous avez un déjeuner avec Alex et votre cours de piano cette semaine.'

/** 35-create-event.yaml — a far-future literal, so it cannot mix with the seed. */
const APPOINTMENT = { question: 'Note-moi un dentiste le 12 mars 2099 à 14h', title: 'Dentiste' }
const APPOINTMENTS_URL = '/api/events?startAt%5Bafter%5D=2099-01-01'

/** 05-context-router-new-topic.yaml + 70-budget-question.yaml — the change of subject. */
const OTHER_SUBJECT = 'Parlons de mes finances, où en est mon budget ?'

/** 12-context-summary.yaml — what the fake writes whenever Maggie summarizes a thread. */
const THREAD_SUMMARY = "Résumé e2e : l'utilisateur organise sa semaine avec Maggie."

/** 71-context-summary-recall.yaml — only answerable once that summary is in the system prompt. */
const RECALL = {
  question: 'De quoi parlions-nous au juste ?',
  answer: "Nous parlions de l'organisation de ta semaine.",
}

/** 80-proaction-bin-night.yaml — what a scheduled proaction would say. */
const PROACTION = { prompt: 'Rappelle-lui de sortir les poubelles', message: 'Petit rappel : les poubelles sortent ce soir.' }

test('a scripted question runs its tool and streams the answer back', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(AGENDA_QUESTION)

  const answer = assistantText(events)
  expect(isUnscripted(answer), `no scenario matched — Maggie said: ${answer}`).toBe(false)

  // The tool really ran, against the real MCP server and the seeded database.
  expect(calledTools(events)).toContain('get_upcoming_events')

  // More than one delta: a single one would mean the gateway buffered the
  // whole answer, which is the bug streaming exists to avoid.
  expect(deltaCount(events), 'the answer did not stream').toBeGreaterThan(1)

  // One answer, one bubble — however many tool rounds it took. A cycle per
  // round is half of 176c40c, and the stream is where it starts.
  expect(messageIds(events), 'the run opened more than one assistant message').toHaveLength(1)
  expect(countEvents(events, 'TEXT_MESSAGE_START')).toBe(1)
  expect(countEvents(events, 'TEXT_MESSAGE_END')).toBe(1)

  // And the context router opened a context, rather than finding one. It can
  // be exact because `task e2e:web` reseeds first, which empties the agent's
  // own database: this is the first thing anyone has said to her.
  expect(contextAction(events)).toBe('created')

  await expect(chat.message(/déjeuner avec Alex/i)).toBeVisible()

  // The other half of 176c40c, on the screen this time: the user's message is
  // added optimistically *and* echoed back, and the panel has to recognise its
  // own echo. One bubble, not two.
  await expect(chat.bubbles(AGENDA_QUESTION)).toHaveCount(1)
  await expect(chat.bubbles(AGENDA_ANSWER)).toHaveCount(1)
})

test('an unscripted message says so rather than improvising', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send("une question que personne n'a scriptée depuis le navigateur")

  // The assertion that proves the switch is in effect at all: the real model
  // would never produce this sentence.
  expect(isUnscripted(assistantText(events))).toBe(true)
})

interface SeededEvent {
  id?: string
  summary?: string
}

test('what Maggie books shows up in the Mind panel, in the database and in the agenda', async ({
  page,
  api,
}) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  // Nothing lives in 2099 until Maggie puts it there, which is what makes the
  // assertion below about her and not about the seed.
  expect(await getCollection<SeededEvent>(api, APPOINTMENTS_URL)).toEqual([])

  const chat = new ChatPanel(page)
  const events = await chat.send(APPOINTMENT.question)

  // Asked for *and* reported successful. `TOOL_CALL_START` alone would be
  // green for a7b08cf, where the agent wrote tool markup as plain text.
  expect(toolResults(events)).toContainEqual({ toolName: 'create_event', status: 'success' })

  // The Mind panel is where an owner sees that, and its state lives in
  // `Layout`, above the widget — so the events being right does not make the
  // panel right.
  await chat.openMind()
  await expect(chat.toolCall('create_event')).toHaveAttribute('data-status', 'success')

  // The data, not her wording: the fake does not read tool results, so a
  // scripted sentence cannot prove anything landed. Polled, because events are
  // served from Elasticsearch and the write is indexed through RabbitMQ — the
  // row exists before it is findable.
  const booked = await waitForIndexed<SeededEvent>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === APPOINTMENT.title,
    { what: `The ${APPOINTMENT.title} appointment Maggie booked` },
  )

  // And it reaches the screen, at its own date: the deep link the admin's own
  // search uses moves the grid there and opens the card.
  const calendar = new CalendarPage(page)
  await calendar.openEvent(String(booked.id), APPOINTMENT.title)
})

test('changing the subject opens a second context', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(OTHER_SUBJECT)

  // `created`, not `matched`. Every message so far joined the context the
  // first one opened — that is what 10-context-router-existing.yaml scripts —
  // and this is the one that must not.
  expect(contextAction(events), 'the change of subject joined the context already open').toBe('created')
  expect(contextLabel(events)).toBe('Budget e2e')

  await chat.openMind()

  // Two, exactly. "More than one" would pass just as happily on the failure
  // 10-context-router-existing.yaml exists to prevent — a router that opens a
  // context per message — and by this point that would be five. The count is
  // knowable: the first test opened one, the two after it joined it, this one
  // opened the second.
  await expect(chat.contextItems).toHaveCount(2)
  await expect(chat.context('Budget e2e')).toBeVisible()

  // And the first thread is still open beside it. `GET /agent/contexts` only
  // returns active and dormant ones, so a context that had been closed would
  // simply be missing — which is the same absence as never having existed.
  await expect(chat.context('Conversation e2e')).toHaveAttribute('data-status', /active|dormant/)
})

test('a thread is still there after a reload, and only once', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  // A fresh tab: what is on screen now came from `GET /agent/messages`, not
  // from the stream that produced it. That is "resume a thread".
  //
  // It reads the last 20 messages and the exchanges above account for eight,
  // so six more inserted before this one would push the agenda question off
  // the page and turn these counts into zero — with a failure message blaming
  // persistence. Paginate the history if the file grows that far.
  const chat = new ChatPanel(page)
  await expect(chat.bubbles(AGENDA_QUESTION)).toHaveCount(1)
  await expect(chat.bubbles(AGENDA_ANSWER)).toHaveCount(1)

  // And again through a reload, because the third cause behind 176c40c was the
  // user's message stored twice: the stream path would look clean and the
  // history would come back doubled.
  await page.reload()
  await dashboard.expectReady()
  await expect(chat.bubbles(AGENDA_QUESTION)).toHaveCount(1)
  await expect(chat.bubbles(AGENDA_ANSWER)).toHaveCount(1)
})

test('a proaction reaches an open chat without anyone reloading', async ({ page, api, session }) => {
  const dashboard = new DashboardPage(page)
  const chat = new ChatPanel(page)

  // Subscribed to the chat's own topic before anything is published: the panel
  // opens several subscriptions and they are registered in whatever order
  // their effects run, so waiting for "the first one" waits for the wrong one.
  await openSubscribed(page, () => dashboard.open(), `/chat/${session.user.id}`)
  await chat.open()

  // `POST /agent/proaction` is what the scheduler calls: no conversation
  // history, no context routing, and the answer is persisted *and published*
  // — unlike the streamed path, where the client already holds it. That
  // publication is the only thing under test here, which is why the scenario
  // calls no tool.
  await expectRealtimeSync(
    page,
    async () => {
      const response = await api.post('/agent/proaction', { data: { message: PROACTION.prompt } })
      expect(response.status(), `the proaction failed: ${await response.text()}`).toBe(200)
    },
    async () => {
      await expect(chat.bubbles(PROACTION.message)).toHaveCount(1)
    },
  )

  // Once more, after a window. `toHaveCount(1)` returns on the first poll that
  // matches, so a second copy arriving a beat later is invisible to it — and
  // one copy per delivery is the only thing an unsolicited message has to
  // prove. Proving an absence needs a window and there is no event to wait on
  // instead, which is the bargain `expectSilence` makes too.
  // eslint-disable-next-line playwright/no-wait-for-timeout
  await page.waitForTimeout(1_000)
  await expect(chat.bubbles(PROACTION.message)).toHaveCount(1)
})

interface GroceryList {
  items?: Array<{ label?: string }>
}

/**
 * The voice path is not here, and that is the stack's doing rather than a gap
 * in this file. `navigator.mediaDevices` only exists in a trustworthy origin;
 * the e2e stack answers on plain `http://traefik`, so the property is
 * undefined, `useVoiceRecorder` reports "Accès au microphone refusé", and
 * nothing in Playwright can work around it — not the microphone permission,
 * not Chromium's fake capture device, not
 * `--unsafely-treat-insecure-origin-as-secure`, which this build ignores even
 * with a persistent profile. Dictation is therefore asserted over HTTP in
 * `e2e/smoke/smoke.sh` (step 9), which drives WireMock's Whisper stub, the
 * cleanup scenario and the write it ends on; MAG-145 gives the stack an origin
 * the browser trusts, and brings the step back here.
 */

test('asking for an item writes it to the grocery list', async ({ page, api }) => {
  const grocery = new GroceryListPage(page)
  await grocery.open()

  // Basil is a seeded ingredient the seed deliberately leaves *off* the list,
  // so its appearance there proves the write happened.
  await expect(grocery.item('Basilic')).toBeHidden()

  const chat = new ChatPanel(page)
  const events = await chat.send('Ajoute du basilic à ma liste de courses')

  expect(calledTools(events)).toContain('add_grocery_item')

  // The data, not her wording: the fake does not read tool results, so a
  // scripted sentence cannot prove anything landed. Polled, because the
  // collection is served from Elasticsearch and the write is indexed through
  // RabbitMQ — the row exists before it is findable.
  await waitForIndexed<GroceryList>(
    api,
    '/api/grocery_lists',
    (list) => (list.items ?? []).some((item) => item.label?.toLowerCase() === 'basilic'),
    { what: 'The basil Maggie added' },
  )

  // And it reaches the screen the owner actually looks at.
  await grocery.expectItemEventually('Basilic')
})

interface AgentContext {
  label: string
  summary?: string | null
}

/**
 * MAG-11, and the reason it is last in this file: everything above it is the
 * long conversation this test needs. The stack runs with
 * `CONTEXT_SUMMARY_EVERY_MESSAGES=4`, so by now Maggie has summarized the thread
 * the first message opened — several times over, each pass reading only what
 * came in since the one before.
 *
 * Three steps, and only the third proves the feature. A summary stored and never
 * injected is worth nothing: the model's system prompt is not observable from a
 * browser, so the proof is a scenario that *cannot match* unless the summary is
 * in it (`system_contains`, 71-context-summary-recall.yaml). The first two steps
 * are there so a failure says which half broke.
 */
test('a long thread is summarized, and the summary reaches Maggie and the Mind panel', async ({
  page,
  api,
}) => {
  // Written in the background, after the stream that triggered it closed — so it
  // is polled, not read once. Reading once here is how a working feature gets
  // reported as broken.
  await expect
    .poll(
      async () => {
        const response = await api.get('/agent/contexts')
        expect(response.status(), 'the contexts endpoint refused the journey').toBe(200)
        const contexts = (await response.json()) as AgentContext[]
        return contexts.find((context) => context.summary)?.summary ?? null
      },
      {
        message: 'no thread was ever summarized — did the summarizer scenario match?',
        timeout: 30_000,
      },
    )
    // Exactly the scripted summary: `[fake-llm] aucun scénario…` stored as a
    // summary would satisfy "non-empty" and then poison every later system prompt.
    .toBe(THREAD_SUMMARY)

  // A fresh tab: what the panel shows now came from `GET /agent/contexts`, which
  // is the path an owner opening the app takes.
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  await chat.openMind()
  await expect(chat.contextSummaries.first()).toHaveText(THREAD_SUMMARY)

  // Back to the conversation first: the Mind tab has no input, and `send()` would
  // reach for the AppBar button, which closes the panel rather than switching tab.
  await chat.openChat()

  // And the step that proves the injection. This scenario declares the summary's
  // own text as `system_contains`, so it is unreachable unless
  // `_build_system_prompt` really put it in front of the model.
  const events = await chat.send(RECALL.question)
  const answer = assistantText(events)
  expect(
    isUnscripted(answer),
    `the summary never reached the system prompt — Maggie said: ${answer}`,
  ).toBe(false)
  expect(answer).toContain(RECALL.answer)
})

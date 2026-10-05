import { test, expect, seedId } from '../fixtures/index.js'
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

/** 36-create-event-retry.yaml — three steps, the first tool call fails, only the last step is the answer. */
const RETRY = {
  question: 'Note-moi le concert des Black Wizards le 3 novembre 2099 à 19h',
  answer: "C'est noté : les Black Wizards en concert le 3 novembre 2099 à 19h, pour deux heures.",
  announce: 'Je prends une durée standard de 2 heures.',
  excuse: "Il y a un souci technique avec l'identifiant de votre agenda.",
  title: 'Black Wizards',
}

/** 37-create-event-named-agenda.yaml — the agenda is named, never identified. */
const CONCERT = {
  question: "Enregistre le concert des Mouettes le 2 novembre 2099 à 19 h à l'UBU, dans l'agenda famille",
  title: 'Concert des Mouettes',
}

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

/** 81-proaction-thread-recall.yaml — only answerable once the thread summaries reach a proaction. */
const PROACTION_RECALL = {
  prompt: 'Fais le point sur sa semaine',
  message: 'Tu organises ta semaine avec moi — je te relance là-dessus.',
}

/** 82-greeting.yaml — the exchange one window sends and the other must see. */
const GREETING = {
  question: 'Bonjour Maggie',
  answer: "Bonjour ! Je suis là, dis-moi ce qu'il te faut.",
}

/** 60-behavior-preference.yaml + 61-behavior-applied.yaml — a preference about how she answers. */
const BEHAVIOR = {
  request: 'Tutoie-moi et évite les emojis',
  directive: 'Tutoie-moi et évite les emojis',
  question: 'Dis-moi bonjour',
  answer: "Bonjour ! Je te réponds sans emoji, comme tu me l'as demandé.",
}

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

test('an event asked for in a named agenda lands in that agenda in one tool call', async ({ page, api }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  expect(await getCollection<SeededEvent>(api, APPOINTMENTS_URL)).not.toContainEqual(
    expect.objectContaining({ summary: CONCERT.title }),
  )

  const chat = new ChatPanel(page)
  const events = await chat.send(CONCERT.question)

  // One call, and it worked: the name was resolved by the tool, with no
  // `manage_agendas` round trip and no error to recover from (MAG-230).
  expect(calledTools(events).filter((tool) => tool === 'create_event')).toHaveLength(1)
  expect(calledTools(events)).not.toContain('manage_agendas')
  expect(toolResults(events)).toContainEqual({ toolName: 'create_event', status: 'success' })

  // The data: it is in « Famille », not in the default agenda.
  const booked = await waitForIndexed<SeededEvent & { agenda?: string }>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === CONCERT.title,
    { what: `The ${CONCERT.title} Maggie booked` },
  )
  expect(booked.agenda).toBe(`/api/agendas/${seedId('e2e_agenda_shared')}`)
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
  // knowable: the first test opened one, the three after it joined it, this one
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
  // It reads the last 20 messages and the exchanges above account for ten,
  // so five more inserted before this one would push the agenda question off
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

  // `POST /agent/proaction` is what the scheduler calls: the answer is
  // persisted *and published*, with no stream of its own for the client to
  // have read it from. That publication is the only thing under test here,
  // which is why the scenario calls no tool. What the proaction knows of the
  // conversation, and the thread it lands in, is the last test in this file.
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

interface AgentInstruction {
  content: string
  kind: string
}

/**
 * MAG-22, and the same shape as the summary test above it, for the same reason:
 * a preference stored and never injected is worth nothing, and a system prompt
 * is not observable from a browser.
 *
 * Three steps. The owner asks to be spoken to differently; the directive has to
 * land as a `behavior` one, because a `planning` one is only ever read by the
 * daily proaction planning — which is exactly the bug this ticket fixed, and a
 * bug no assertion on her wording could see. Then the proof: the second
 * message's scenario declares the preference's own text as `system_contains`,
 * so it is unreachable unless `_build_system_prompt` put it in front of the
 * model.
 *
 * Last in the file, after the thread summary: it adds two exchanges, and the
 * tests above count the messages their own assertions depend on.
 */
test('a preference about how Maggie answers is stored, then applied to the next message', async ({
  page,
  api,
}) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const asked = await chat.send(BEHAVIOR.request)

  const confirmation = assistantText(asked)
  expect(isUnscripted(confirmation), `no scenario matched — Maggie said: ${confirmation}`).toBe(
    false,
  )
  expect(calledTools(asked)).toContain('add_instruction')

  // The row, not her wording: the fake does not read tool results, so a scripted
  // "c'est noté" proves nothing landed. And `?kind=behavior` is the assertion
  // that matters — filed as a planning rule, the directive would be stored,
  // listed in the admin, and still never reach a single answer.
  const response = await api.get('/agent/instructions?kind=behavior')
  expect(response.status(), 'the instructions endpoint refused the journey').toBe(200)
  const directives = (await response.json()) as AgentInstruction[]
  expect(directives.map((directive) => directive.content)).toContain(BEHAVIOR.directive)
  expect(new Set(directives.map((directive) => directive.kind))).toEqual(new Set(['behavior']))

  // And the step that proves the injection.
  const applied = await chat.send(BEHAVIOR.question)
  const answer = assistantText(applied)
  expect(
    isUnscripted(answer),
    `the preference never reached the system prompt — Maggie said: ${answer}`,
  ).toBe(false)
  expect(answer).toContain(BEHAVIOR.answer)
  await expect(chat.bubbles(BEHAVIOR.answer)).toHaveCount(1)
})

interface AgentMessage {
  content: string
  contextId?: string | null
}

/**
 * MAG-14, and last in the file for the same reason as the summary test: it needs
 * a thread Maggie has already summarized, and everything above is that thread.
 *
 * A proaction used to run with no history and be stored with no thread. Two
 * halves, and neither is observable from her wording alone:
 *
 *  - what she knows. The proaction's system prompt is not reachable from a
 *    browser, so the proof is a scenario that *cannot match* unless the open
 *    threads' summaries are in it (`system_contains`,
 *    81-proaction-thread-recall.yaml).
 *  - where it lands. The message comes back from the endpoint with the thread it
 *    was stored in, and that thread has to be one of the user's open ones — a
 *    reply to the reminder is then routed against the same conversation instead
 *    of opening a context of its own.
 */
test('a proaction is written from the thread in progress, and stored in it', async ({ api }) => {
  const fired = await api.post('/agent/proaction', { data: { message: PROACTION_RECALL.prompt } })
  expect(fired.status(), `the proaction failed: ${await fired.text()}`).toBe(200)

  const body = (await fired.json()) as { response: string; messages: AgentMessage[] }
  expect(
    isUnscripted(body.response),
    `the thread summaries never reached the proaction — Maggie said: ${body.response}`,
  ).toBe(false)
  expect(body.response).toContain(PROACTION_RECALL.message)

  // The thread it was stored in. `null` here is the bug: the message would be an
  // orphan in the conversation, and the Mind panel would show a thread that never
  // heard Maggie speak.
  const [stored] = body.messages
  expect(stored?.content).toContain(PROACTION_RECALL.message)
  expect(stored?.contextId, 'the proaction was stored outside any thread').toBeTruthy()

  // And it is one of the user's own open threads, not an id from nowhere.
  const contexts = await api.get('/agent/contexts')
  expect(contexts.status(), 'the contexts endpoint refused the journey').toBe(200)
  const open = (await contexts.json()) as Array<{ id: string }>
  expect(open.map((context) => context.id)).toContain(stored?.contextId)
})

/** 62-last-exchange-known.yaml — only answerable once the last conversation is in the system prompt. */
const LAST_EXCHANGE = {
  question: "Combien de temps s'est écoulé depuis notre dernier échange ?",
  answer: 'Rebonjour ! On a parlé il y a quelques instants, je te laisse reprendre le fil.',
}

/** 04 + 72-thread-history-recall.yaml — picking an older thread back up. */
const THREAD_RECALL = {
  question: 'Reprends le fil de mon budget, s\'il te plaît',
  answer: "On parlait de ton budget du mois — je reprends le fil là où on s'était arrêtés.",
}

/**
 * MAG-10: the hour to the minute and the last conversation reach her system prompt.
 *
 * Not observable from her wording, so the proof is a scenario that cannot match
 * unless the « Dernière conversation : … » line is in the system prompt
 * (`system_contains`, 62-last-exchange-known.yaml). Last in the file: it needs
 * earlier messages to exist, and everything above is what wrote them. And the
 * message being answered must not be taken for the last conversation — with it,
 * the line would exist on a first message too, which the unit tests pin.
 */
test('Maggie is told when the last conversation happened', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(LAST_EXCHANGE.question)

  const answer = assistantText(events)
  expect(
    isUnscripted(answer),
    `the last conversation never reached the system prompt — Maggie said: ${answer}`,
  ).toBe(false)
  expect(answer).toContain(LAST_EXCHANGE.answer)
  await expect(chat.bubbles(LAST_EXCHANGE.answer)).toHaveCount(1)
})

/**
 * MAG-13: the history is the routed thread's own messages, not the last messages of
 * everything.
 *
 * Next to last in the file, and it needs every test above it. « Budget e2e » was opened five tests
 * ago by the change of subject and has not been spoken in since; every message after it
 * joined « Conversation e2e ». So the Budget thread is *old*, and the stack runs with
 * `RECENT_HISTORY_MESSAGES=2` — the global window holds the previous exchange and nothing
 * else.
 *
 * The proof is a scenario that cannot match otherwise: 72-thread-history-recall.yaml
 * declares « où en est mon budget » — a sentence said only in the Budget thread — as its
 * `history_contains`. Verified load-bearing: with `CONTEXT_HISTORY_MESSAGES=0`, so that
 * only the global window is sent, this is the one test in the file that goes red, and it
 * goes red on `[fake-llm] aucun scénario…`, which names its own cause. Raise
 * `RECENT_HISTORY_MESSAGES` past that exchange and it would pass for the wrong reason.
 */
test('picking an older thread back up sends that thread, not the last messages of everything', async ({
  page,
}) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(THREAD_RECALL.question)

  const answer = assistantText(events)
  expect(
    isUnscripted(answer),
    `the thread's own messages never reached the history — Maggie said: ${answer}`,
  ).toBe(false)
  expect(answer).toContain(THREAD_RECALL.answer)

  // And it went back into the Budget thread rather than opening a third one. `matched` on
  // its label is the half that says the routing ran first: the history above could only be
  // built from a thread that was already resolved.
  expect(contextAction(events)).toBe('matched')
  expect(contextLabel(events)).toBe('Budget e2e')

  await expect(chat.bubbles(THREAD_RECALL.answer)).toHaveCount(1)
})

/**
 * MAG-229: a run of several steps used to glue every step's text into one bubble —
 * « …la modifier.Je m'excuse, monsieur. Il y a un souci technique… » — and read the
 * whole thing aloud. Only the step after the last tool call is the answer.
 *
 * 36-create-event-retry.yaml scripts the case that was reported: an announcement, a
 * first create_event that fails, an excuse and a second try, then the closing
 * sentence. Last in the file so the threads the tests above count are not disturbed.
 */
test('a run whose first tool call fails answers with its last step alone', async ({ page, api }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(RETRY.question)

  expect(isUnscripted(assistantText(events)), `no scenario matched — Maggie said: ${assistantText(events)}`).toBe(false)

  // The retry really happened: the first call failed, the second landed.
  expect(toolResults(events)).toEqual([
    { toolName: 'create_event', status: 'error' },
    { toolName: 'create_event', status: 'success' },
  ])

  // One message, closed once, holding the last step and nothing of the others.
  expect(messageIds(events), 'the run opened more than one assistant message').toHaveLength(1)
  expect(countEvents(events, 'TEXT_MESSAGE_END')).toBe(1)
  expect(assistantText(events)).toBe(RETRY.answer)
  expect(deltaCount(events), 'the answer did not stream').toBeGreaterThan(1)

  await expect(chat.bubbles(RETRY.answer)).toHaveCount(1)
  await expect(chat.message(RETRY.announce)).toHaveCount(0)
  await expect(chat.message(RETRY.excuse)).toHaveCount(0)

  // The data, not the wording: the event is in the agenda once.
  await waitForIndexed<SeededEvent>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === RETRY.title,
    { what: `The ${RETRY.title} concert Maggie booked on her second try` },
  )
})

// A streamed exchange used to be published to nobody — the window that streamed
// it already held it — so a second window, or the phone, only saw it after a
// reload (MAG-109). Last in the file because it adds an exchange to the thread,
// which the counts above would otherwise have to know about.
test('an exchange streamed in one window shows up in the other, once, without a reload', async ({
  twoWindows,
  session,
}) => {
  const { actor, observer } = twoWindows
  const actorChat = new ChatPanel(actor)
  const observerChat = new ChatPanel(observer)

  await new DashboardPage(actor).open()
  await openSubscribed(observer, () => new DashboardPage(observer).open(), `/chat/${session.user.id}`)
  await observerChat.open()

  await expectRealtimeSync(
    observer,
    async () => {
      await actorChat.send(GREETING.question)
    },
    async () => {
      await expect(observerChat.bubbles(GREETING.question)).toHaveCount(1)
      await expect(observerChat.bubbles(GREETING.answer)).toHaveCount(1)
    },
  )

  // And once on the window that streamed it: it receives its own echo too.
  await expect(actorChat.bubbles(GREETING.question)).toHaveCount(1)
  await expect(actorChat.bubbles(GREETING.answer)).toHaveCount(1)

  // A window to prove an absence: a late echo is a second copy.
  // eslint-disable-next-line playwright/no-wait-for-timeout
  await observer.waitForTimeout(1_000)
  await expect(observerChat.bubbles(GREETING.answer)).toHaveCount(1)
  await expect(actorChat.bubbles(GREETING.answer)).toHaveCount(1)
})

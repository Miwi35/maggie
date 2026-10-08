import { test, expect, parisDay, parisTime, seedId } from '../fixtures/index.js'
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
import { adminUrl, ROUTES } from '../pages/routes.js'

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
  answer: "J'ai ajouté le concert des Black Wizards le 3 novembre 2099 à 19h à votre agenda, pour deux heures.",
  announce: 'Je prends une durée standard de 2 heures.',
  excuse: "Il y a un souci technique avec l'identifiant de votre agenda.",
  title: 'Black Wizards',
}

/** 37-create-event-named-agenda.yaml — the agenda is named, never identified. */
const CONCERT = {
  question: "Enregistre le concert des Mouettes le 2 novembre 2099 à 19 h à l'UBU, dans l'agenda famille",
  title: 'Concert des Mouettes',
}

/** 38-create-event-timezone.yaml — converts from Fort-de-France with `date_time`, then books the converted hour. */
const TIMEZONE_CALL = {
  question: "Rappelle-moi d'appeler Kévin le 14 juillet 2099 à 10 h chez lui, il vit à Fort-de-France",
  title: 'Appeler Kévin',
  // 10:00 in Fort-de-France is 14:00 UTC, which is 16:00 in Paris.
  startsAt: '2099-07-14T14:00:00.000Z',
}

/** 35-create-event-several-days.yaml — « du X au Y » is one whole-day event, the last day included (MAG-317). */
const HOLIDAY = {
  question: 'Ajoute les vacances du 22 décembre 2099 au 3 janvier 2100',
  title: "Vacances d'hiver",
  // Midnight in Paris (UTC+1 in winter): the first day, and the midnight after the last one.
  startsAt: '2099-12-21T23:00:00.000Z',
  endsAt: '2100-01-03T23:00:00.000Z',
}

/**
 * 31 to 34-create-event-*.yaml — MAG-150: the agenda **nobody named**. Each question
 * sends `create_event` with no `agenda_id` at all, so where the event lands is the API's
 * deduction and nothing else. Dated 2099 like the writes above, which also keeps them out
 * of the thirty-day window the deduction reads.
 */
const DEDUCED = {
  /** « un concert » against an agenda called « Concerts ». */
  byName: {
    question: 'Ajoute le concert de Stromae le 12 novembre 2099 à 20 h',
    title: 'Concert de Stromae',
    agenda: 'e2e_agenda_concerts',
  },
  /** Nothing names an agenda; the two past appointments with Paul are in « Boulot ». */
  byHistory: {
    question: 'Ajoute un rendez-vous avec Paul le 13 novembre 2099 à 10 h',
    title: 'Rendez-vous avec Paul',
    agenda: 'e2e_agenda_work',
  },
  /** Camille is lunched with in « Famille » and in « Boulot »: she has to ask. */
  ambiguous: {
    question: 'Ajoute un déjeuner avec Camille le 14 novembre 2099 à 12 h 30',
    reply: 'Mets-le dans Boulot',
    title: 'Déjeuner avec Camille',
    agenda: 'e2e_agenda_work',
  },
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

/** 87-learning-forgotten-once.yaml — a rule announced as learned before anything stored it. */
const RULE = {
  request: 'Quand je demande un rappel, je veux une notification',
  skill: 'rappel-avec-notification',
  answer: "J'ai créé la compétence « Rappel avec notification »",
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
  startAt?: string
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

test('a call for someone in another timezone is converted by the date_time tool, then booked', async ({
  page,
  api,
}) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(TIMEZONE_CALL.question)

  // `date_time` ran natively in the tool loop and answered without an error —
  // an unknown place or a broken handler would end it in `error` — before the
  // write. The order matters: the conversion is what the booking is made from.
  expect(calledTools(events).filter((tool) => ['date_time', 'create_event'].includes(tool))).toEqual([
    'date_time',
    'create_event',
  ])
  expect(toolResults(events)).toContainEqual({ toolName: 'date_time', status: 'success' })
  expect(toolResults(events)).toContainEqual({ toolName: 'create_event', status: 'success' })

  const booked = await waitForIndexed<SeededEvent>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === TIMEZONE_CALL.title,
    { what: `The ${TIMEZONE_CALL.title} reminder Maggie booked` },
  )
  expect(new Date(String(booked.startAt)).toISOString()).toBe(TIMEZONE_CALL.startsAt)
})

test('a stretch of days asked for is one whole-day event, not a series', async ({ page, api }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  expect(await getCollection<SeededEvent>(api, APPOINTMENTS_URL)).not.toContainEqual(
    expect.objectContaining({ summary: HOLIDAY.title }),
  )

  const chat = new ChatPanel(page)
  const events = await chat.send(HOLIDAY.question)

  // One call and it worked: no duration tried first, no recurrence to patch it up.
  expect(calledTools(events).filter((tool) => tool === 'create_event')).toHaveLength(1)
  expect(calledTools(events)).not.toContain('update_event')
  expect(toolResults(events)).toContainEqual({ toolName: 'create_event', status: 'success' })

  const booked = await waitForIndexed<SeededEvent & { allDay?: boolean; endAt?: string; rrule?: string | null }>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === HOLIDAY.title,
    { what: `The ${HOLIDAY.title} Maggie booked` },
  )
  expect(booked.allDay).toBe(true)
  expect(booked.rrule ?? null).toBeNull()
  expect(new Date(String(booked.startAt)).toISOString()).toBe(HOLIDAY.startsAt)
  expect(new Date(String(booked.endAt)).toISOString()).toBe(HOLIDAY.endsAt)

  // Only one row: a daily series, or thirteen events, would be found here.
  const sameTitle = (await getCollection<SeededEvent>(api, APPOINTMENTS_URL)).filter(
    (event) => event.summary === HOLIDAY.title,
  )
  expect(sameTitle).toHaveLength(1)

  const calendar = new CalendarPage(page)
  await calendar.openEvent(String(booked.id), HOLIDAY.title)
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
  // context per message — and by this point that would be seven. The count is
  // knowable: the first test opened one, the five after it joined it, this one
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
  items?: Array<{ label?: string; quantity?: number | null }>
}

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

/**
 * 38-weather.yaml, and the Open-Meteo stub it reads
 * (`.docker/e2e/wiremock/mappings/open-meteo.json`) — MAG-156. No place is given:
 * the tool takes the default city of the seeded preferences, Rennes.
 */
const WEATHER = {
  question: 'Quel temps demain ?',
  answer: 'Demain à Rennes, il fera 18,5 °C au plus haut et 9,5 °C au plus bas, avec un peu de pluie.',
}

test('asking for the weather runs get_weather against the simulated Open-Meteo', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(WEATHER.question)

  expect(isUnscripted(assistantText(events)), `no scenario matched — Maggie said: ${assistantText(events)}`).toBe(false)

  // `success` is the proof the stub answered: without a city, a place or a
  // forecast the tool returns an `error`, and the stream reports `error`.
  expect(calledTools(events)).toContain('get_weather')
  expect(toolResults(events)).toContainEqual({ toolName: 'get_weather', status: 'success' })

  await expect(chat.bubbles(WEATHER.answer)).toHaveCount(1)
})

/**
 * The voice path, from the microphone to the database (MAG-145).
 *
 * It needs a browser that has a microphone to offer, which `http://traefik` never
 * was: a trustworthy origin is what makes `navigator.mediaDevices` exist, and the
 * journeys now browse `http://localhost` (see `playwright.config.ts`). The first
 * assertion is that precondition, so that a regression of the origin says so
 * instead of showing up as "Accès au microphone refusé".
 *
 * The two stand-ins have to agree: WireMock dictates one fixed sentence
 * (`.docker/e2e/wiremock/mappings/openai.json`), 20-transcription-cleanup.yaml
 * returns it tidied, and 41-grocery-add-dictated.yaml is what Maggie does with
 * that. Each is plausible alone, so only driving all three catches one being
 * edited without the others. `e2e/smoke/smoke.sh` still asserts the same path
 * over HTTP, without the browser.
 */
test('a dictated sentence reaches the input cleaned, and sending it writes to the grocery list', async ({
  page,
  api,
}) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const secure = await page.evaluate(() => ({
    secureContext: window.isSecureContext,
    mediaDevices: typeof navigator.mediaDevices,
  }))
  expect(secure, 'the origin is not trustworthy, so the browser has no microphone').toEqual({
    secureContext: true,
    mediaDevices: 'object',
  })

  // Summed over the lines: the tool may fold the four into the seeded line or
  // open a second one (it does when the unit differs), and either way the owner
  // has more tomatoes to buy.
  const tomatoes = async (): Promise<number> => {
    const [list] = await getCollection<GroceryList>(api, '/api/grocery_lists')

    return (list?.items ?? [])
      .filter((item) => item.label?.toLowerCase() === 'tomate')
      .reduce((total, item) => total + (item.quantity ?? 0), 0)
  }
  const before = await tomatoes()
  expect(before, 'the seed puts tomatoes on the list; the assertion below needs a starting point').toBeGreaterThan(0)

  const chat = new ChatPanel(page)
  const { raw, clean } = await chat.dictate()

  // Whisper is WireMock, and the model's cleanup is a scenario: the sentence
  // that reaches the input is the cleaned one, not the hesitation-laden raw one.
  expect(clean, 'no scenario cleaned the dictation — is 20-transcription-cleanup.yaml matching?').not.toContain(
    '[fake-llm]',
  )
  expect(clean).not.toBe(raw)
  await expect(chat.input).toHaveValue(clean)

  const events = await chat.submit()
  expect(calledTools(events)).toContain('add_grocery_item')
  expect(toolResults(events)).toContainEqual({ toolName: 'add_grocery_item', status: 'success' })

  // The data, not her wording: the quantity went up. Polled, because the
  // collection is served from Elasticsearch and the write is indexed through
  // RabbitMQ — the row exists before it is findable.
  await expect
    .poll(tomatoes, { message: 'the dictated tomatoes never reached the list', timeout: 30_000 })
    .toBeGreaterThan(before)
})

/**
 * A recording with no speech in it produces no text (retour de recette MAG-222).
 *
 * The owner saw « Thank you for watching » appear in his chat without having said a
 * word: given silence, Whisper writes the subtitle boilerplate it was trained on. The
 * refusal happens in the agent, which reads Whisper's own `no_speech_prob` and
 * `avg_logprob` and filters the known credits, so the proof lives at the route — the
 * same one the browser's « Dicter » and the phone both post to.
 *
 * Reproducible without a sound card, and without depending on the order the journeys
 * run in: WireMock answers the hallucination for a clip whose bytes carry
 * `MAGGIE_E2E_SILENCE` and the real sentence for everything else
 * (`.docker/e2e/wiremock/mappings/openai.json`). Both are asserted here, so a mapping
 * that stopped matching says which one.
 */
test('a clip with no speech in it comes back with no text at all', async ({ api }) => {
  const transcribe = async (bytes: string) =>
    api.post('/agent/transcribe', {
      multipart: {
        audio: { name: 'voice.wav', mimeType: 'audio/wav', buffer: Buffer.from(bytes) },
        cleanup: 'none',
      },
    })

  const silent = await transcribe('MAGGIE_E2E_SILENCE')
  expect(silent.ok(), await silent.text()).toBeTruthy()
  expect(await silent.json(), 'a transcript invented over silence must not reach the chat').toEqual({
    raw: '',
    clean: '',
  })

  const spoken = await transcribe('a clip with a voice in it')
  expect(spoken.ok(), await spoken.text()).toBeTruthy()
  const heard = await spoken.json()
  expect(heard.raw, 'the gate must not refuse speech — is the stub still answering verbose JSON?').toContain(
    'tomates',
  )
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

/** 85-proaction-skill-applied.yaml — only answerable once the skill's own text is in the proaction's prompt. */
const MOMENT_SKILL = {
  name: 'delivrer-un-rappel-e2e',
  description: 'Quand un rappel arrive',
  tags: ['rappel', 'moment:proaction'],
  content: 'Consigne e2e : une notification push et rien dans le chat.',
  prompt: 'Rappel e2e à délivrer : appeler votre mère',
  message: "Il est l'heure d'appeler votre mère, je vous l'envoie en notification.",
}

/**
 * MAG-345: a skill learnt for « a reminder comes due » is given in full when one does.
 *
 * With its index line alone, Maggie loaded it two reminders out of five. The proof is
 * a scenario that cannot match unless the skill's content is in the system prompt.
 */
test('a skill tagged for the proaction moment is in the proaction prompt in full', async ({ api }) => {
  const { prompt, message, ...skill } = MOMENT_SKILL
  const created = await api.post('/agent/skills', { data: skill })
  expect(created.status(), `the skill was refused: ${await created.text()}`).toBe(201)

  try {
    const fired = await api.post('/agent/proaction', { data: { message: prompt } })
    expect(fired.status(), `the proaction failed: ${await fired.text()}`).toBe(200)

    const body = (await fired.json()) as { response: string }
    expect(isUnscripted(body.response), `the skill never reached the proaction — Maggie said: ${body.response}`).toBe(
      false,
    )
    expect(body.response).toContain(message)
  } finally {
    // Skills are global: one left behind would reach every later prompt of the run.
    await api.delete(`/agent/skills/${skill.name}`)
  }
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
 * sentence. Near the end of the file, after everything that counts threads and bubbles —
 * so is every test below it.
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
// reload (MAG-109). Near the end of the file because it adds an exchange to the
// thread, which the counts above would otherwise have to know about.
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

/**
 * MAG-150: the agenda nobody named.
 *
 * Until this ticket every event Maggie created went to the default agenda, whatever it was
 * about — « Concerts » in production, because the fallback before MAG-149 was alphabetical.
 * `create_event` now works the agenda out from the event itself, from the agendas' names
 * and from where the user filed similar events before, and refuses to choose when two fit.
 *
 * The three below are at the **end of the file**, after the tests that count contexts,
 * bubbles and the twenty messages the chat panel reloads: four more exchanges inserted
 * above would push the agenda question off that page and the counts would fail blaming
 * persistence.
 *
 * What they assert is the agenda the row landed in, read back from the API — never her
 * wording. The fake does not read tool results, so every sentence here is a fixture; which
 * agenda the event is in is not.
 */
test('an event nobody placed lands in the agenda its own words point at', async ({ page, api }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(DEDUCED.byName.question)

  expect(isUnscripted(assistantText(events)), `no scenario matched — Maggie said: ${assistantText(events)}`).toBe(
    false,
  )

  // One call, no agenda named in it, and no `manage_agendas` round trip to find one: the
  // API decided (MAG-230 bought the single call, MAG-150 the decision).
  expect(calledTools(events).filter((tool) => tool === 'create_event')).toHaveLength(1)
  expect(calledTools(events)).not.toContain('manage_agendas')
  expect(toolResults(events)).toContainEqual({ toolName: 'create_event', status: 'success' })

  const booked = await waitForIndexed<SeededEvent & { agenda?: string }>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === DEDUCED.byName.title,
    { what: `The ${DEDUCED.byName.title} Maggie booked` },
  )
  expect(booked.agenda, 'the concert did not land in « Concerts »').toBe(
    `/api/agendas/${seedId(DEDUCED.byName.agenda)}`,
  )
})

test('an appointment nobody placed lands where the past ones went', async ({ page, api }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(DEDUCED.byHistory.question)

  expect(isUnscripted(assistantText(events)), `no scenario matched — Maggie said: ${assistantText(events)}`).toBe(
    false,
  )
  expect(toolResults(events)).toContainEqual({ toolName: 'create_event', status: 'success' })

  // Nothing in « Rendez-vous avec Paul » names an agenda and no agenda is called anything
  // like it: the only thing pointing at « Boulot » is the user's own history, seeded in
  // `api/fixtures/e2e/20-calendar.yaml`. The default agenda is « Perso ».
  const booked = await waitForIndexed<SeededEvent & { agenda?: string }>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === DEDUCED.byHistory.title,
    { what: `The appointment with Paul Maggie booked` },
  )
  expect(booked.agenda, 'the appointment did not land in « Boulot »').toBe(
    `/api/agendas/${seedId(DEDUCED.byHistory.agenda)}`,
  )
})

test('an event two agendas fit is not created until the owner says which one', async ({ page, api }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const asked = await chat.send(DEDUCED.ambiguous.question)

  expect(isUnscripted(assistantText(asked)), `no scenario matched — Maggie said: ${assistantText(asked)}`).toBe(false)

  // The tool ran and refused — one call, come back `error`. That is the assertion: the
  // question Maggie then asks is scripted, so it could not prove anything by itself.
  expect(toolResults(asked)).toEqual([{ toolName: 'create_event', status: 'error' }])

  // And nothing was written. Read once rather than polled, on purpose: a row that does not
  // exist never becomes findable, so there is nothing to wait for — the refusal above is
  // what makes this absence mean something, and the write below is what proves the
  // collection would have shown it.
  expect(await getCollection<SeededEvent>(api, APPOINTMENTS_URL)).not.toContainEqual(
    expect.objectContaining({ summary: DEDUCED.ambiguous.title }),
  )

  const answered = await chat.send(DEDUCED.ambiguous.reply)

  expect(toolResults(answered)).toEqual([{ toolName: 'create_event', status: 'success' }])
  const booked = await waitForIndexed<SeededEvent & { agenda?: string }>(
    api,
    APPOINTMENTS_URL,
    (event) => event.summary === DEDUCED.ambiguous.title,
    { what: `The lunch with Camille Maggie booked once told which agenda` },
  )
  expect(booked.agenda, 'the lunch did not land in the agenda the owner named').toBe(
    `/api/agendas/${seedId(DEDUCED.ambiguous.agenda)}`,
  )
})

/**
 * 39-update-event-shift.yaml — MAG-321: shifting an evening to the next day used to put it at
 * midnight with no length. Wednesday 19:00–00:00 in Paris, then Thursday 19:00–00:00 (UTC+1).
 * 2098, so the 2099 assertions above never see it.
 */
const SHIFT = {
  title: 'Soirée avec Julie (MAG-321)',
  before: { startAt: '2098-03-19T18:00:00.000Z', endAt: '2098-03-19T23:00:00.000Z' },
  after: { startAt: '2098-03-20T18:00:00.000Z', endAt: '2098-03-20T23:00:00.000Z' },
}

test('"décale la soirée à jeudi" moves the evening to Thursday 19:00–00:00 and nothing stays on Wednesday', async ({
  page,
  api,
}) => {
  const created = await api.post('/api/events', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: {
      summary: SHIFT.title,
      startAt: SHIFT.before.startAt,
      endAt: SHIFT.before.endAt,
      agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
    },
  })
  expect(created.status()).toBe(201)
  const eventId = ((await created.json()) as { id: string }).id

  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(`décale la soirée à jeudi (${eventId})`)

  expect(isUnscripted(assistantText(events)), `no scenario matched — Maggie said: ${assistantText(events)}`).toBe(false)
  expect(toolResults(events)).toContainEqual({ toolName: 'update_event', status: 'success' })

  // The data, not her wording: Thursday from 19:00, ending at midnight — a start and an end,
  // with the length kept. Polled, because the search index follows the write.
  const shifted = await waitForIndexed<SeededEvent & { endAt?: string }>(
    api,
    '/api/events?startAt%5Bafter%5D=2098-01-01&startAt%5Bbefore%5D=2098-12-31',
    (event) => event.summary === SHIFT.title && new Date(String(event.startAt)).toISOString() === SHIFT.after.startAt,
    { what: `The ${SHIFT.title} event, shifted to Thursday` },
  )
  expect(new Date(String(shifted.endAt)).toISOString()).toBe(SHIFT.after.endAt)

  // And no longer on Wednesday: it is the same event, so one row carries the title.
  const sameTitle = (await getCollection<SeededEvent>(api, '/api/events?startAt%5Bafter%5D=2098-01-01&startAt%5Bbefore%5D=2098-12-31')).filter(
    (event) => event.summary === SHIFT.title,
  )
  expect(sameTitle).toHaveLength(1)

  const calendar = new CalendarPage(page)
  await calendar.goToEventDate(eventId, SHIFT.title)
})

interface AgentSkill {
  name: string
}

/**
 * MAG-340, near the end so the counts above keep their twenty-message page. In
 * production Maggie answered « C'est noté » to a rule and called nothing. The fixture
 * scripts that answer; the claim guard sends it back, and only then does the fake call
 * `create_skill`. So the assertion is the row and the admin tab, never her wording.
 */
test('a rule announced as learned without a tool is stored as a skill, and shown in the admin', async ({
  page,
  api,
}) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const taught = await chat.send(RULE.request)

  expect(isUnscripted(assistantText(taught)), `no scenario matched — Maggie said: ${assistantText(taught)}`).toBe(
    false,
  )
  expect(toolResults(taught)).toContainEqual({ toolName: 'create_skill', status: 'success' })
  // The fixture's last turn, only reachable through the guard's relaunch.
  expect(assistantText(taught)).toContain(RULE.answer)

  const response = await api.get('/agent/skills')
  expect(response.status(), 'the skills endpoint refused the journey').toBe(200)
  const skills = (await response.json()) as AgentSkill[]
  expect(skills.map((skill) => skill.name)).toContain(RULE.skill)

  await page.goto(adminUrl(ROUTES.agentSettings))
  await page.getByRole('tab', { name: 'Compétences' }).click()
  await expect(page.getByText(RULE.skill)).toBeVisible()
})

/** 89-agenda-unread-once.yaml — « quand je vois Julie » answered from memory, then from the agenda. */
const EVENING = {
  question: 'Quand est-ce que je vois Julie ?',
  title: 'Soirée à Rennes avec Julie',
  wrongAnswer: 'Demain, vendredi 3 octobre',
  answer: 'Vous voyez Julie ce soir à 19 h',
}

/**
 * MAG-349: on 8 Oct. Maggie answered « demain, vendredi 3 octobre, de 19 h à minuit » to this
 * question without opening the agenda — the evening was that night. The fixture scripts that
 * answer first; the claim guard sends it back, and only then does the fake read the agenda.
 * So the assertion is the tool that ran and the answer that survived, never the wording of the
 * first attempt.
 */
test('"quand est-ce que je vois Julie ?" is answered from the agenda, not from memory', async ({ page, api }) => {
  const today = parisDay()
  const created = await api.post('/api/events', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: {
      summary: EVENING.title,
      startAt: parisTime(today, '19:00:00'),
      endAt: parisTime(today, '23:00:00'),
      agenda: `/api/agendas/${seedId('e2e_agenda_personal')}`,
    },
  })
  expect(created.status()).toBe(201)

  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const events = await chat.send(EVENING.question)

  expect(isUnscripted(assistantText(events)), `no scenario matched — Maggie said: ${assistantText(events)}`).toBe(
    false,
  )
  expect(toolResults(events)).toContainEqual({ toolName: 'get_upcoming_events', status: 'success' })
  expect(assistantText(events)).toContain(EVENING.answer)
  expect(assistantText(events)).not.toContain(EVENING.wrongAnswer)
})

/** 01 to 03-context-router-birthday-*.yaml and 90 to 92-birthday-*.yaml — one discussion (MAG-341). */
const ONE_DISCUSSION = {
  first: "Je prépare l'anniversaire de Lucie samedi",
  followUps: ['et du coup, on commence par quoi ?', "tu te souviens pour qui c'était ?"],
  thread: 'Anniversaire e2e',
  recall: "Bien sûr : c'est pour l'anniversaire de Lucie, samedi.",
}

/**
 * MAG-341: on 7 Oct. one discussion was split into five threads in nine minutes, and Maggie
 * forgot what she had been told a minute before.
 *
 * Three linked messages, sent back to back. The two follow-ups name no subject, so the router
 * can only keep them in the discussion if it is shown that discussion:
 * 02-context-router-birthday-follow-up.yaml answers only then, and
 * 03-context-router-birthday-lost.yaml otherwise opens « Sujet perdu e2e », as the model did
 * that evening. And the third answer, 92-birthday-recall.yaml, needs the first message in its
 * history — out of reach of `RECENT_HISTORY_MESSAGES=2` unless all three share a thread.
 * Last in the file: it opens a thread, and the tests above count them.
 */
test('three linked messages stay in one thread, and the third is answered from the first', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()

  const chat = new ChatPanel(page)
  const opened = await chat.send(ONE_DISCUSSION.first)
  expect(contextAction(opened)).toBe('created')
  expect(contextLabel(opened)).toBe(ONE_DISCUSSION.thread)

  let answer = ''
  for (const followUp of ONE_DISCUSSION.followUps) {
    const events = await chat.send(followUp)
    expect(contextAction(events), `« ${followUp} » left the discussion`).toBe('matched')
    expect(contextLabel(events)).toBe(ONE_DISCUSSION.thread)
    answer = assistantText(events)
  }

  expect(isUnscripted(answer), `the first message never reached the third answer — Maggie said: ${answer}`).toBe(false)
  expect(answer).toBe(ONE_DISCUSSION.recall)
})

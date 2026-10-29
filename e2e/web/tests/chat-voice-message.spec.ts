import { randomUUID } from 'node:crypto'
import { test, expect } from '../fixtures/index.js'
import { calledTools, parseAgUiStream } from '../helpers/agui.js'
import { openMercureProbe } from '../helpers/mercure.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * Maggie sends a voice message (MAG-685, extends MAG-99).
 *
 * The fake model decides to call `send_voice_message` (99-voice-message.yaml); the tool really
 * runs — the fake TTS says the text, the fake bucket keeps the MP3 — and the message lands in
 * the thread with its audio attachment, on Mercure and in the history. Its URL answers the owner
 * with a short signed URL that plays; another user gets a 403, nobody signed in a 401 or 403.
 *
 * MAG-686: the panel draws the message as a voice bubble — duration, play, speed, transcript.
 *
 * An account of its own: nothing else writes its chat.
 */

test.use({ journeyAccount: 'empty' })

interface Audio {
  type: string
  durationMs: number
  size: number
  url: string
  transcript: string
}

interface AgentMessage {
  id: string
  role: string
  content: string
  audio?: Audio
}

test('a voice message lands in the thread with an audio only its owner can play', async ({
  page,
  api,
  session,
  otherUser,
  playwright,
  baseURL,
}) => {
  const ref = randomUUID().slice(0, 8)
  await new HomePage(page).open()
  const probe = await openMercureProbe(page, [`/chat/${session.user.id}`])

  // Given the fake model that calls the voice tool, When the owner asks for a vocal…
  const body = await page.evaluate(
    async ({ message, token }) => {
      const response = await fetch('/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ message, idempotency_key: `e2e-voice-${Math.random().toString(36).slice(2)}` }),
      })
      return response.text()
    },
    { message: `Envoie-moi un vocal qui résume ma journée (réf ${ref})`, token: session.token },
  )
  expect(calledTools(parseAgUiStream(body))).toEqual(['send_voice_message'])

  // …Then the message received through Mercure carries an audio attachment.
  const received = await probe.waitFor((m) => {
    const audio = (m.parsed as unknown as AgentMessage | null)?.audio
    return audio !== undefined && audio.transcript.includes(`réf ${ref}`)
  })
  const message = received.parsed as unknown as AgentMessage
  const audio = message.audio!
  expect(message.role).toBe('assistant')
  expect(audio.type).toBe('audio/mpeg')
  expect(audio.size).toBeGreaterThan(0)
  expect(audio.durationMs).toBeGreaterThan(0)
  expect(audio.url).toBe(`/agent/messages/${message.id}/audio`)
  await probe.close()

  // The history carries it too.
  const history = (await (await api.get('/agent/messages?limit=10')).json()) as AgentMessage[]
  expect(history.find((m) => m.id === message.id)?.audio).toEqual(audio)

  // With my token, the URL answers a signed URL that plays.
  const signed = await api.get(audio.url)
  expect(signed.status()).toBe(200)
  const { url } = (await signed.json()) as { url: string }
  const played = await api.get(url)
  expect(played.status()).toBe(200)
  expect(played.headers()['content-type']).toBe('audio/mpeg')
  expect(await played.body()).toHaveLength(audio.size)

  // With another user's token, 403; with none, 401 or 403.
  expect((await otherUser.api.get(audio.url)).status()).toBe(403)
  const anonymous = await playwright.request.newContext({ baseURL })
  expect([401, 403]).toContain((await anonymous.get(audio.url)).status())
  await anonymous.dispose()
})

test('the voice message shows as a bubble the owner plays, speeds up and reads', async ({ page }) => {
  const ref = randomUUID().slice(0, 8)
  await new HomePage(page).open()
  const chat = new ChatPanel(page)

  // Given a voice message received…
  await chat.send(`Envoie-moi un vocal qui résume ma journée (réf ${ref})`)
  const bubble = chat.panel.getByTestId('voice-bubble').last()
  await expect(bubble).toBeVisible()

  // …Then its duration shows, and a click on play starts it.
  await expect(bubble.getByTestId('voice-duration')).toHaveText(/^\d+:\d{2}$/)
  await bubble.getByRole('button', { name: 'Lecture' }).click()
  await expect(bubble.getByRole('button', { name: 'Pause' })).toBeVisible()

  // The speed goes ×1 → ×1,5 → ×2.
  await bubble.getByRole('button', { name: 'Vitesse ×1', exact: true }).click()
  await bubble.getByRole('button', { name: 'Vitesse ×1,5' }).click()
  await expect(bubble.getByRole('button', { name: 'Vitesse ×2' })).toBeVisible()

  // The transcript unfolds, without voice tags.
  await bubble.getByRole('button', { name: 'Transcription' }).click()
  await expect(bubble.getByTestId('voice-transcript')).toContainText(`réf ${ref}`)
  await expect(bubble.getByTestId('voice-transcript')).not.toContainText('[')
})

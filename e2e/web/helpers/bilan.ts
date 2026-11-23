import { expect } from '@playwright/test'
import type { APIRequestContext } from '@playwright/test'
import { e2eNow } from '../fixtures/index.js'
import { callMcpTool } from './mcp.js'

export interface BilanMessage {
  id: string
  role: string
  content: string
  blocks?: { type: string; day?: string; route?: string }[]
}

const PROMPT = "Fais le bilan de la journée avec l'utilisateur (compétence bilan-soir, ouverture par le rendez-vous du soir)."

/**
 * Makes the evening review due and waits for the message the executor writes (MAG-826): the review time set to 00:00,
 * the proaction filed with `schedule_proaction` and moved to a minute ago, as `agent-digest.spec.ts` does.
 */
export async function playEveningReview(api: APIRequestContext): Promise<BilanMessage> {
  const preferences = await api.patch('/api/user_preferences/me', {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { eveningReviewEnabled: true, eveningReviewTime: '00:00' },
  })
  expect(preferences.ok(), `PATCH /api/user_preferences/me answered ${preferences.status()}: ${await preferences.text()}`).toBe(true)

  const tomorrow = new Date(e2eNow().getTime() + 86_400_000).toISOString().slice(0, 10)
  const scheduled = await callMcpTool(api, 'schedule_proaction', { prompt: PROMPT, summary: 'Bilan de la journée', scheduled_at: `${tomorrow}T21:30:00` })
  expect(scheduled.success, JSON.stringify(scheduled)).toBe(true)
  const id = (scheduled.proaction as { id: string }).id

  const moved = await api.patch(`/api/events/${id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { startAt: new Date(e2eNow().getTime() - 60_000).toISOString() },
  })
  expect(moved.ok(), `PATCH /api/events/${id} answered ${moved.status()}: ${await moved.text()}`).toBe(true)

  const review = async () => {
    const messages = (await (await api.get('/agent/messages?limit=20')).json()) as BilanMessage[]

    return messages.find((m) => m.role === 'assistant' && m.blocks?.some((b) => b.type === 'bilan'))
  }
  await expect.poll(async () => (await review()) !== undefined, { message: 'the review message was never written', timeout: 180_000, intervals: [2_000] }).toBe(true)

  return (await review())!
}

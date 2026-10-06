import { test, expect, seedId } from '../fixtures/index.js'
import { toolResults } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import type { APIRequestContext } from '@playwright/test'

/**
 * An action Maggie may not take alone waits for the owner's answer (MAG-6).
 *
 * `delete_*` is `ask` in `agent/data/policy.yaml` (MAG-4): the tool call is
 * held, the chat shows a card, and only *Autoriser* runs it. So what is
 * asserted is the database on both sides of the click — the event is still
 * there while the card waits, gone after approval, still there after a refusal.
 * The announcement is the model's prose, scripted (91-delete-event-approved
 * .yaml): its presence proves the resume ran, not that the deletion did.
 *
 * Extends the chat journey (MAG-99).
 */

const AGENDA = 'e2e_agenda_personal'
const ANNOUNCEMENT = "C'est fait : l'événement est supprimé."

async function createEvent(api: APIRequestContext, summary: string): Promise<string> {
  const created = await api.post('/api/events', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' },
    data: {
      summary,
      startAt: '2030-02-15T10:00:00+00:00',
      endAt: '2030-02-15T11:00:00+00:00',
      agenda: `/api/agendas/${seedId(AGENDA)}`,
    },
  })
  expect(created.status()).toBe(201)

  return ((await created.json()) as { id: string }).id
}

async function eventStatus(api: APIRequestContext, id: string): Promise<number> {
  return (await api.get(`/api/events/${id}`, { headers: { Accept: 'application/ld+json' } })).status()
}

test('Autoriser runs the held deletion and Maggie announces it', async ({ page, api }) => {
  const id = await createEvent(api, 'Recette validation MAG-6 autorisée')

  await new DashboardPage(page).open()
  const chat = new ChatPanel(page)
  const events = await chat.send(`Supprime l'événement ${id}`)

  // Held, not failed: a third outcome next to success and error.
  expect(toolResults(events)).toContainEqual({ toolName: 'delete_event', status: 'pending_approval' })

  const card = chat.approvalCard(id)
  await expect(card).toHaveAttribute('data-status', 'pending')
  expect(await eventStatus(api, id), 'the event was deleted before anyone allowed it').toBe(200)

  // The Mind tab counts what waits, and the activity list shows the held call.
  await expect(chat.mindTab).toContainText('1')
  await chat.openMind()
  await expect(chat.toolCall('delete_event')).toHaveAttribute('data-status', 'pending_approval')
  await chat.openChat()

  await card.getByRole('button', { name: 'Autoriser' }).click()

  await expect(card).toHaveAttribute('data-status', 'approved')
  await expect
    .poll(() => eventStatus(api, id), { timeout: 30_000, message: 'The approved deletion should reach the database' })
    .toBe(404)

  await expect(chat.bubbles(ANNOUNCEMENT)).toHaveCount(1)
})

test('Refuser leaves the event in place', async ({ page, api }) => {
  const id = await createEvent(api, 'Recette validation MAG-6 refusée')

  await new DashboardPage(page).open()
  const chat = new ChatPanel(page)
  await chat.send(`Supprime l'événement ${id}`)

  const card = chat.approvalCard(id)
  await expect(card).toHaveAttribute('data-status', 'pending')

  await card.getByRole('button', { name: 'Refuser' }).click()

  await expect(card).toHaveAttribute('data-status', 'denied')
  await expect(card.getByRole('button', { name: 'Autoriser' })).toHaveCount(0)
  expect(await eventStatus(api, id), 'a refused deletion ran anyway').toBe(200)
  await expect(chat.bubbles(ANNOUNCEMENT)).toHaveCount(0)
})

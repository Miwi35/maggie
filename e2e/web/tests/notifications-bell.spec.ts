import { test, expect } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { callMcpTool } from '../helpers/mcp.js'
import { openSubscribed } from '../helpers/mercure.js'
import { NotificationBell } from '../pages/NotificationBell.js'

/**
 * The bell follows Mercure, not the search index (MAG-343, extends MAG-103).
 *
 * Notifications are listed from Elasticsearch, indexed after the write by the
 * worker, while the Mercure event leaves with the write: a bell that re-read
 * the list on the event showed the notification only after a reload. Titles are
 * unique to the attempt so a retry, or another journey, never counts.
 */

const LIVE = 5_000

test('the open bell follows what Maggie and the other clients do to notifications, without a reload', async ({ page, api }) => {
  const attempt = test.info().retry
  const listedTitle = `Dentiste demain, essai ${attempt}`
  const liveTitle = `Votre film commence, essai ${attempt}`
  const bell = new NotificationBell(page)

  // One notification the bell loads with the page, so it is known by the list rather than by an event.
  expect((await callMcpTool(api, 'manage_notifications', { action: 'create', type: 'reminder', title: listedTitle })).error).toBeUndefined()
  const listed = await waitForIndexed<{ id: string }>(
    api,
    '/api/notifications?itemsPerPage=100',
    (n) => (n as { title?: string }).title === listedTitle,
    { what: `The notification "${listedTitle}"` },
  )

  await openSubscribed(page, () => bell.goto('/'))
  // The bell shows the 20 latest; wait for it to have loaded them before counting.
  const latest = await getCollection<{ readAt: string | null }>(api, '/api/notifications?itemsPerPage=20&order[createdAt]=desc')
  const before = latest.filter((n) => !n.readAt).length
  await bell.expectUnreadCount(before, LIVE)
  await bell.open()
  await expect(bell.item(listedTitle)).toBeVisible()

  // Created while the bell is open: the index does not hold it yet.
  expect((await callMcpTool(api, 'manage_notifications', { action: 'create', type: 'proaction', title: liveTitle })).error).toBeUndefined()
  await bell.expectUnreadCount(before + 1, LIVE)
  // Maggie speaking first covers the screen (MAG-311) and hides the list from the accessibility tree: put it off.
  const interruption = page.getByRole('alertdialog').filter({ hasText: liveTitle })
  await expect(interruption).toBeVisible({ timeout: LIVE })
  await interruption.getByRole('button', { name: 'Plus tard' }).click()
  await expect(interruption).toBeHidden()
  await expect(bell.item(liveTitle)).toBeVisible({ timeout: LIVE })

  // Opened from the bell: it is marked read through the record it was given by the event.
  await bell.item(liveTitle).click()
  await bell.expectUnreadCount(before, LIVE)

  // Read on another client (the phone), then deleted.
  const read = await api.patch(`/api/notifications/${listed.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { readAt: new Date().toISOString() },
  })
  expect(read.ok()).toBe(true)
  await bell.expectUnreadCount(before - 1, LIVE)

  expect((await api.delete(`/api/notifications/${listed.id}`)).ok()).toBe(true)
  await expect(bell.item(listedTitle)).toHaveCount(0, { timeout: LIVE })
})

/**
 * Emptying the bell (MAG-362, extends MAG-103).
 *
 * Signs in as the second account: clearing deletes every notification of the
 * user, and the seeded one is read by the journey above and by others running
 * beside this one. Reminders, not proactions: a proaction would cover the
 * screen with Maggie's interruption (MAG-311).
 */
test('the bell deletes one notification, then empties itself after a confirmation, and stays empty after a reload', async ({ otherUser }) => {
  const { api, page } = otherUser
  const attempt = test.info().retry
  const first = `Dentiste demain, effacer ${attempt}`
  const second = `Pharmacie à midi, effacer ${attempt}`
  const bell = new NotificationBell(page)

  for (const title of [first, second]) {
    expect((await callMcpTool(api, 'manage_notifications', { action: 'create', type: 'reminder', title })).error).toBeUndefined()
    await waitForIndexed(api, '/api/notifications?itemsPerPage=100', (n) => (n as { title?: string }).title === title, {
      what: `The notification "${title}"`,
    })
  }

  await openSubscribed(page, () => bell.goto('/'))
  await bell.open()
  await expect(bell.item(first)).toBeVisible()
  await expect(bell.item(second)).toBeVisible()

  await bell.delete(first)
  await expect(bell.item(first)).toHaveCount(0, { timeout: LIVE })
  await expect(bell.item(second)).toBeVisible()

  await bell.clearAll()
  await expect(bell.item(second)).toHaveCount(0, { timeout: LIVE })
  await expect(bell.popover.getByText('Aucune notification')).toBeVisible()

  // The list is served from the index, which drops the documents after the write.
  await expect
    .poll(async () => (await getCollection(api, '/api/notifications?itemsPerPage=100')).length, { timeout: 30_000 })
    .toBe(0)
  await page.reload()
  await bell.open()
  await expect(bell.popover.getByText('Aucune notification')).toBeVisible()
})

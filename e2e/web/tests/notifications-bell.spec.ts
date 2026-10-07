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
 * the list on the event showed the notification only after a reload. The title
 * is unique to the attempt so a retry, or another journey, never counts.
 */

const LIVE = 5_000

test('a notification Maggie creates shows in the open bell, unread, without a reload', async ({ page, api }) => {
  const title = `Votre film commence, essai ${test.info().retry}`
  const bell = new NotificationBell(page)

  await openSubscribed(page, () => bell.goto('/'))
  await expect(bell.bell).toBeVisible()
  // The bell shows the 20 latest; wait for it to have loaded them before counting.
  const latest = await getCollection<{ readAt: string | null }>(api, '/api/notifications?itemsPerPage=20&order[createdAt]=desc')
  const before = latest.filter((n) => !n.readAt).length
  await bell.expectUnreadCount(before, LIVE)

  const created = await callMcpTool(api, 'manage_notifications', { action: 'create', type: 'proaction', title })
  expect(created.error, 'the tool must create it').toBeUndefined()

  await bell.expectUnreadCount(before + 1, LIVE)
  await bell.open()
  await expect(bell.item(title)).toBeVisible({ timeout: LIVE })

  // Read from another client (the phone): the bell must follow.
  const stored = await waitForIndexed<{ id: string; title: string }>(
    api,
    '/api/notifications?itemsPerPage=100',
    (n) => (n as { title?: string }).title === title,
    { what: `The notification "${title}"` },
  )
  const read = await api.patch(`/api/notifications/${stored.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json' },
    data: { readAt: new Date().toISOString() },
  })
  expect(read.ok()).toBe(true)
  await bell.expectUnreadCount(before, LIVE)

  await api.delete(`/api/notifications/${stored.id}`)
  await expect(bell.item(title)).toHaveCount(0, { timeout: LIVE })
})

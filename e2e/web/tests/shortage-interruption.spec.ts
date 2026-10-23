import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { HomePage } from '../pages/HomePage.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { NotificationBell } from '../pages/NotificationBell.js'
import { getCollection } from '../helpers/api.js'
import { openSubscribed, publishOnHub } from '../helpers/mercure.js'

/**
 * « Il manque » (MAG-570, extends MAG-97 / MAG-99): the dinner planned tonight
 * needs parmesan, which is neither in stock nor on the list. Maggie interrupts
 * with two answers; « Plus tard » leaves the notification in the list,
 * « Ajouter à la liste » puts the parmesan on it and the notification goes by
 * itself.
 *
 * Its own account, serially: the interruption covers the whole screen of every
 * window of its user. The alert and its notification are seeded
 * (`api/fixtures/e2e/65-shortage-alert.yaml`); the cron that files them is run by
 * the smoke journey, which no browser can. The delivery is the notification's
 * update, sent here on the hub — the journey is about what the admin does with it.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const TITLE = 'Il manque : Parmesan'

interface StoredNotification {
  id: string
  title: string
  [key: string]: unknown
}

const shortage = async (api: APIRequestContext) =>
  (await getCollection<StoredNotification>(api, '/api/notifications?itemsPerPage=100')).find((n) => n.title === TITLE)

const parmesanLines = async (api: APIRequestContext) => {
  const [list] = await getCollection<{ items: { label: string }[] }>(api, '/api/grocery_lists')

  return (list?.items ?? []).filter((item) => item.label === 'Parmesan')
}

test('« Plus tard » closes the interruption and leaves the notification in the list', async ({ shortageUser }) => {
  const { page, api, session } = shortageUser
  const bell = new NotificationBell(page)
  const dashboard = new HomePage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  const stored = await shortage(api)
  expect(stored, 'the seeded « Il manque » notification').toBeDefined()
  expect(stored!.actions).toEqual(['add'])
  await publishOnHub(page, `/users/${session.user.id}/api/notifications/${stored!.id}`, { '@id': `/api/notifications/${stored!.id}`, ...stored })

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toBeVisible()
  await expect(interruption).toContainText(TITLE)
  await expect(interruption).toContainText('Gratin du soir, ce soir')
  await expect(interruption.getByRole('button', { name: 'Ajouter à la liste' })).toBeVisible()
  await interruption.getByRole('button', { name: 'Plus tard' }).click()
  await expect(interruption).toBeHidden()

  // Still there, unread, and nothing was added.
  await bell.open()
  await expect(bell.item(TITLE)).toBeVisible()
  expect(await parmesanLines(api)).toHaveLength(0)
})

test('« Ajouter à la liste » puts the parmesan on the list, and the notification goes by itself', async ({ shortageUser }) => {
  const { page, api, session } = shortageUser
  const bell = new NotificationBell(page)
  const dashboard = new HomePage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  await new ChatPanel(page).ensureClosed()

  const stored = await shortage(api)
  expect(stored).toBeDefined()
  await publishOnHub(page, `/users/${session.user.id}/api/notifications/${stored!.id}`, { '@id': `/api/notifications/${stored!.id}`, ...stored })

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toContainText(TITLE)
  const answered = page.waitForRequest((request) => request.method() === 'POST' && request.url().includes('/api/shortage-alerts/'))
  await interruption.getByRole('button', { name: 'Ajouter à la liste' }).click()
  expect(JSON.parse((await answered).postData() ?? '{}')).toEqual({ action: 'add' })
  await expect(interruption).toBeHidden()

  // The parmesan is on the list…
  await expect.poll(async () => (await parmesanLines(api)).length, { message: 'the parmesan never reached the list', timeout: 30_000 }).toBe(1)

  // …and the notification is gone, from the API and from the open bell, without a reload.
  await expect.poll(async () => await shortage(api), { message: 'the notification stayed', timeout: 30_000 }).toBeUndefined()
  await bell.open()
  await expect(bell.item(TITLE)).toHaveCount(0)

  // The alert answers once.
  const again = await api.post(`${stored!.relatedEntityIri as string}/action`, { data: { action: 'add' } })
  expect(again.status()).toBe(409)
})

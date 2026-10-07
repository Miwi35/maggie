import { test, expect } from '../fixtures/index.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { DashboardPage } from '../pages/DashboardPage.js'
import { openSubscribed, publishOnHub } from '../helpers/mercure.js'

/**
 * Maggie speaking first (MAG-311, extends MAG-97 / MAG-99).
 *
 * A proaction that completes reaches the open admin as an interruption: the
 * message, the action it proposes and « Plus tard ». The delivery is the
 * agent's `/proactions/{userId}` update, published here straight on the hub —
 * the journey is about what the admin does with it.
 */

const MESSAGE = 'Petit rappel : les poubelles sortent ce soir.'

const proaction = (userId: string, id: string) => ({
  id,
  userId,
  prompt: 'Rappelle-lui de sortir les poubelles',
  status: 'completed',
  response: MESSAGE,
  error: null,
  scheduledAt: '2026-10-07T18:00:00+00:00',
  createdAt: '2026-10-07T17:00:00+00:00',
  completedAt: '2026-10-07T18:00:01+00:00',
})

test('a proaction that completes interrupts the open admin, and « Plus tard » closes it', async ({
  page,
  session,
}) => {
  const dashboard = new DashboardPage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  // An open conversation already shows what Maggie says: nothing to interrupt.
  await new ChatPanel(page).ensureClosed()

  await publishOnHub(page, `/proactions/${session.user.id}`, proaction(session.user.id, 'e2e-interruption-1'))

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toBeVisible()
  await expect(interruption).toContainText(MESSAGE)
  await expect(interruption.getByRole('button', { name: 'Plus tard' })).toBeVisible()

  await interruption.getByRole('button', { name: 'Plus tard' }).click()
  await expect(interruption).toBeHidden()
})

test('Esc is « Plus tard »', async ({ page, session }) => {
  const dashboard = new DashboardPage(page)
  await openSubscribed(page, () => dashboard.open(), `/proactions/${session.user.id}`)
  // An open conversation already shows what Maggie says: nothing to interrupt.
  await new ChatPanel(page).ensureClosed()

  await publishOnHub(page, `/proactions/${session.user.id}`, proaction(session.user.id, 'e2e-interruption-2'))

  const interruption = page.getByRole('alertdialog')
  await expect(interruption).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(interruption).toBeHidden()
})

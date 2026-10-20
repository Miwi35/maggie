import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { TaskOrderSettingsPage, TaskSettingsPage } from '../pages/TaskSettingsPage.js'

/**
 * « Réglages › Tâches » sets when Maggie alerts about a task, and « Ce qui
 * passe en premier » how tasks are ordered (MAG-407, extends MAG-103). Both
 * save on every change.
 *
 * The phone reads the same settings, so each journey ends on the API: what the
 * admin saved is what `GET /api/task-settings/me` hands the app.
 *
 * Signed in as the neighbour: the settings are the user's own, and the
 * journeys that read task alerts on the owner's account must not see them move.
 */

// One mutable settings row on one account: the journeys take turns.
test.describe.configure({ mode: 'serial' })

interface TaskSettings {
  morningTime: string
  tone: string
  reminderDailyCap: number
  orderLevels: Record<string, number>
  alertSchedule: Record<string, { daysBefore: number[]; dueDay: boolean; overdueEveryDays: number | null }>
  defaults: Record<string, unknown>
}

async function readSettings(api: APIRequestContext): Promise<TaskSettings> {
  const response = await api.get('/api/task-settings/me', { headers: { Accept: 'application/json' } })
  expect(response.ok(), `GET /api/task-settings/me answered ${response.status()}`).toBe(true)

  return (await response.json()) as TaskSettings
}

async function resetToDefaults(api: APIRequestContext): Promise<void> {
  const { defaults } = await readSettings(api)
  const response = await api.put('/api/task-settings/me', { data: defaults })
  expect(response.ok(), `PUT /api/task-settings/me answered ${response.status()}`).toBe(true)
}

test.beforeEach(async ({ otherUser }) => {
  await resetToDefaults(otherUser.api)
})

test.afterEach(async ({ otherUser }) => {
  await resetToDefaults(otherUser.api)
})

test('the morning time and the critical reminders hold after a reload and reach the API', async ({ otherUser }) => {
  const { page, api } = otherUser
  const settings = new TaskSettingsPage(page)
  await settings.open()
  await expect(settings.morningTime).toHaveValue('08:00')
  await expect(settings.day('critical', 'J-3')).toHaveAttribute('aria-pressed', 'true')
  await expect(settings.day('critical', 'J-1')).toHaveAttribute('aria-pressed', 'true')

  await settings.setMorningTime('07:30')
  await settings.toggleDay('critical', 'J-3')
  await expect(settings.content.getByText('Enregistré', { exact: true })).toBeVisible()

  await page.reload()
  await settings.expectReady()
  await expect(settings.morningTime).toHaveValue('07:30')
  await expect(settings.day('critical', 'J-3')).toHaveAttribute('aria-pressed', 'false')
  await expect(settings.day('critical', 'J-1')).toHaveAttribute('aria-pressed', 'true')
  await expect(settings.day('critical', 'Jour J')).toHaveAttribute('aria-pressed', 'true')

  const saved = await readSettings(api)
  expect(saved.morningTime).toBe('07:30')
  expect(saved.alertSchedule.critical.daysBefore).toEqual([1])
})

test('« La gravité » pushed to « Énormément » holds after a reload and reaches the API', async ({ otherUser }) => {
  const { page, api } = otherUser
  const order = new TaskOrderSettingsPage(page)
  await order.open()
  await expect(order.level('criticality')).toHaveAttribute('aria-valuetext', 'Normal')

  await order.setToMax('criticality')
  await expect(order.level('criticality')).toHaveAttribute('aria-valuetext', 'Énormément')

  await page.reload()
  await order.expectReady()
  await expect(order.level('criticality')).toHaveAttribute('aria-valuetext', 'Énormément')

  const saved = await readSettings(api)
  expect(saved.orderLevels.criticality).toBe(4)
})

/**
 * « Rappels et récompenses » (MAG-510): how many reminders a day at most, and
 * the tone of their words. The defaults the hooks put back hold the reminder
 * fields too, so the neighbour ends on 6 and « Doux » whatever the attempt.
 */
test('« Au plus, par jour » brought down to 4 and the tone « Drôle » hold after a reload and reach the API', async ({ otherUser }) => {
  const { page, api } = otherUser
  const settings = new TaskSettingsPage(page)
  await settings.open()
  await expect(settings.dailyCap).toHaveText('6')
  await expect(settings.tone('Doux')).toHaveAttribute('aria-pressed', 'true')

  await settings.stepDailyCap('Moins', 2)
  await expect(settings.dailyCap).toHaveText('4')
  await settings.chooseTone('Drôle')
  await expect(settings.tone('Drôle')).toHaveAttribute('aria-pressed', 'true')

  await page.reload()
  await settings.expectReady()
  await expect(settings.dailyCap).toHaveText('4')
  await expect(settings.tone('Drôle')).toHaveAttribute('aria-pressed', 'true')
  await expect(settings.tone('Doux')).toHaveAttribute('aria-pressed', 'false')

  const saved = await readSettings(api)
  expect([saved.reminderDailyCap, saved.tone]).toEqual([4, 'drole'])
})

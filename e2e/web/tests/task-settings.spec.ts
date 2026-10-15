import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { TaskSettingsPage } from '../pages/TaskSettingsPage.js'

/**
 * « Réglages › Tâches » sets when Maggie alerts about a task (MAG-407, extends MAG-103).
 *
 * The phone reads the same settings, so the journey ends on the API: what the
 * admin saved is what `GET /api/task-settings/me` hands the app.
 *
 * Signed in as the neighbour: the settings are the user's own, and the
 * journeys that read task alerts on the owner's account must not see them move.
 */

// One mutable settings row on one account: the journeys take turns.
test.describe.configure({ mode: 'serial' })

interface TaskSettings {
  morningTime: string
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
  await expect(settings.daysBefore('critical')).toHaveValue('3, 1')

  await settings.morningTime.fill('07:30')
  await settings.daysBefore('critical').fill('1')
  await settings.save()

  await page.reload()
  await settings.expectReady()
  await expect(settings.morningTime).toHaveValue('07:30')
  await expect(settings.daysBefore('critical')).toHaveValue('1')
  await expect(settings.dueDay('critical')).toBeChecked()

  const saved = await readSettings(api)
  expect(saved.morningTime).toBe('07:30')
  expect(saved.alertSchedule.critical.daysBefore).toEqual([1])
})

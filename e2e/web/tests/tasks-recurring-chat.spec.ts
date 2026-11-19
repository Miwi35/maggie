import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { waitForIndexed } from '../helpers/api.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * A reminder that comes back is one recurring task (MAG-870, extends MAG-99).
 *
 * Given an account without any task,
 * When the owner writes « Rappelle-moi un mardi sur deux de sortir la poubelle jaune »,
 * Then Maggie calls `create_task` alone, and one open task « Sortir la poubelle jaune »
 *   is stored on the Tuesday of next week, with the rule FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;
 * When that task is checked,
 * Then the next one is open fourteen days later with the same rule, and the done one
 *   no longer carries it — the series is held by its only open task.
 *
 * Only the model is scripted (`74-task-recurring.yaml`, its `${next_tuesday}` being
 * the Tuesday of the Monday-Sunday week after the stack's Paris day); the call goes
 * through the real MCP. The account is created for the test: it owns no task, so
 * the title alone names the rows.
 */

test.use({ journeyAccount: 'empty' })

const TITLE = 'Sortir la poubelle jaune'
const RULE = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU'
const TASKS = '/api/tasks?itemsPerPage=100'

interface StoredTask {
  id: string
  title: string
  scheduledDate: string | null
  completedAt: string | null
  rrule: string | null
}

/** `day` (YYYY-MM-DD) moved by `offset` days, by calendar arithmetic. */
function daysFrom(day: string, offset: number): string {
  const [year, month, date] = day.split('-').map(Number)

  return new Date(Date.UTC(year, month - 1, date + offset)).toISOString().slice(0, 10)
}

/** The Tuesday of the week after the current Paris one, as the fake LLM computes it. */
function nextTuesday(): string {
  const today = parisDay()
  const weekday = new Date(`${today}T00:00:00Z`).getUTCDay()
  const daysToNextMonday = 7 - (0 === weekday ? 6 : weekday - 1)

  return daysFrom(today, daysToNextMonday + 1)
}

async function task(api: APIRequestContext, id: string): Promise<StoredTask> {
  const response = await api.get(`/api/tasks/${id}`, { headers: { Accept: 'application/ld+json' } })
  expect(response.ok(), `GET /api/tasks/${id} answered ${response.status()}`).toBe(true)

  return (await response.json()) as StoredTask
}

test('« un mardi sur deux » creates one recurring task, and checking it opens the next one fourteen days later', async ({
  page,
  api,
}) => {
  const first = nextTuesday()

  // When the owner asks Maggie…
  await new HomePage(page).open()
  const chat = new ChatPanel(page)
  const events = await chat.send('Rappelle-moi un mardi sur deux de sortir la poubelle jaune')

  // …she creates one task, and the call went through.
  expect(calledTools(events)).toEqual(['create_task'])
  expect(toolResults(events)).toEqual([expect.objectContaining({ toolName: 'create_task', status: 'success' })])
  expect(assistantText(events)).toContain('un mardi sur deux')

  // Then one open task, on the Tuesday of next week, carries the rule.
  const created = await waitForIndexed<StoredTask>(api, TASKS, (t) => TITLE === t.title, {
    what: `The task "${TITLE}"`,
  })
  expect(created.completedAt ?? null).toBeNull()
  expect(created.scheduledDate?.slice(0, 10)).toBe(first)
  expect(created.rrule).toBe(RULE)

  // When it is checked, as the task screen does…
  const checked = await api.patch(`/api/tasks/${created.id}`, {
    headers: { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
    data: { completedAt: e2eNow().toISOString() },
  })
  expect(checked.ok(), `PATCH /api/tasks/${created.id} answered ${checked.status()}: ${await checked.text()}`).toBe(true)

  // …then the next one is open fourteen days later, with the same rule…
  const next = await waitForIndexed<StoredTask>(
    api,
    TASKS,
    (t) => TITLE === t.title && created.id !== t.id && null === (t.completedAt ?? null),
    { what: `The next "${TITLE}"` },
  )
  expect(next.scheduledDate?.slice(0, 10)).toBe(daysFrom(first, 14))
  expect(next.rrule).toBe(RULE)

  // …and the done one no longer carries it.
  const done = await task(api, created.id)
  expect(done.completedAt).not.toBeNull()
  expect(done.rrule ?? null).toBeNull()
})

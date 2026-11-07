import { test, expect } from '../fixtures/index.js'
import { assistantText, calledTools, parseAgUiStream, toolResults } from '../helpers/agui.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * « On fait le point » opens the evening review — MAG-749 (Bilan du soir, part 1, extends MAG-99).
 *
 * Given the fake model and the scenario `98-bilan-soir-on-demand.yaml`, When I write « on fait le point »,
 * Then Maggie loads the skill, reads the status, writes the step and the day, and opens the Journée step
 * — and `activity-status/bilan` reads back `journee`, through the real MCP and the real API.
 */

test.use({ journeyAccount: 'empty' })

test('« on fait le point » opens the review at the Journée step and writes the step', async ({ page, api, session }) => {
  await new HomePage(page).open()

  // Nothing in progress before.
  expect((await api.get('/api/activity-status/bilan')).status()).toBe(404)

  // When I ask to take stock of the day.
  const body = await page.evaluate(
    async ({ token }) => {
      const response = await fetch('/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ message: 'On fait le point', idempotency_key: `e2e-bilan-${Math.random().toString(36).slice(2)}` }),
      })
      return response.text()
    },
    { token: session.token },
  )
  const events = parseAgUiStream(body)

  // Then the review ran through its tools, every one successfully.
  const called = calledTools(events)
  expect(called).toContain('get_activity_status')
  expect(called).toContain('save_bilan_step')
  expect(called).toContain('get_tasks')
  expect(toolResults(events).every((result) => result.status === 'success')).toBe(true)
  expect(assistantText(events)).toContain('la journée')

  // And the status of the bilan is at the Journée step.
  const status = await api.get('/api/activity-status/bilan')
  expect(status.status(), await status.text()).toBe(200)
  const stored = (await status.json()) as { activity: string; origin: { kind: string }; state: { step: string } }
  expect(stored.activity).toBe('bilan')
  expect(stored.origin.kind).toBe('day')
  expect(stored.state.step).toBe('journee')
})

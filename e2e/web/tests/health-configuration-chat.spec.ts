import { test, expect } from '../fixtures/index.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { ROUTES } from '../pages/routes.js'

/**
 * « Mon objectif de pas, c'est 10 000 » — MAG-707.
 *
 * The step goal is a field of the health profile: `97-health-step-goal.yaml` scripts the model's side,
 * what is proved is the chain through the real `update_health_profile` and the goal the API stored.
 * The signed-in account is the one this journey owns, so no other journey's profile moves.
 */
test('Maggie sets the step goal of Santé › Configuration — MAG-707', async ({ otherUser }) => {
  const { api } = otherUser

  const shell = new AdminShell(otherUser.page)
  await shell.goto(ROUTES.calendar)
  await shell.expectLoaded()
  const events = await new ChatPanel(otherUser.page).send("Mon objectif de pas, c'est 10 000")

  expect(calledTools(events), 'the profile tool never ran — did the scenario stop matching?').toEqual(['update_health_profile'])
  expect(toolResults(events)).toEqual([expect.objectContaining({ toolName: 'update_health_profile', status: 'success' })])

  await expect
    .poll(async () => ((await (await api.get('/api/health/profile')).json()) as { stepGoal?: number }).stepGoal, {
      message: 'the step goal was not stored',
      timeout: 30_000,
    })
    .toBe(10000)
})

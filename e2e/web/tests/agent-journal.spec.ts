import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The journal of the plannings and proactions — MAG-688 (digest du matin, part 4, extends MAG-103).
 *
 * Given a planning instruction and the fake planning model (`06-planning-journal.yaml`, which
 * schedules tonight's « Point du soir » once it has read that instruction), When the owner clicks
 * « Relancer la planification maintenant » in Maggie's settings, Then the journal shows the run
 * within a minute: the instruction it read and the proaction it scheduled.
 *
 * An account of its own: the planning reads its instructions, and no other journey's must match.
 */

test.use({ journeyAccount: 'standard' })

const INSTRUCTION = 'Chaque soir à 23 h, fais le point avec moi (journal MAG-688).'

test('« Relancer la planification » shows the run in the journal with the instruction read and the proaction scheduled', async ({ page, api }) => {
  const created = await api.post('/agent/instructions', { data: { content: INSTRUCTION, kind: 'planning' } })
  expect(created.status(), await created.text()).toBe(201)

  const shell = new AdminShell(page)
  await shell.goto(ROUTES.agentSettings)
  await page.getByRole('tab', { name: 'Journal' }).click()
  await expect(page.getByText('Rien au journal pour le moment.')).toBeVisible()

  await page.getByRole('button', { name: 'Relancer la planification maintenant' }).click()

  // « Programme les proactions du jour en moins d'une minute »: the run is on top, unfolded.
  await expect(page.getByText('Planification du jour')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('1 consigne(s) · 1 proaction(s)')).toBeVisible()
  await expect(page.getByText(INSTRUCTION)).toBeVisible()
  await expect(page.getByText('23:00 · Point du soir')).toBeVisible()

  // Reloaded, the journal still has it: it is kept, not only answered.
  await page.reload()
  await page.getByRole('tab', { name: 'Journal' }).click()
  await expect(page.getByText('Planification du jour')).toBeVisible()
})

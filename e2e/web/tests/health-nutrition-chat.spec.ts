import { test, expect, parisDay } from '../fixtures/index.js'
import { getCollection } from '../helpers/api.js'
import { calledTools, toolResults } from '../helpers/agui.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { HealthNutritionPage } from '../pages/HealthNutritionPage.js'

/**
 * « J'ai mangé une salade César à midi » — MAG-748.
 *
 * Given Santé › Nutrition ouvert, When je dis à Maggie « J'ai mangé une salade César à midi »,
 * Then `manage_food_intakes` note « Salade César » aujourd'hui, source conversation, et le
 * journal l'affiche sans recharger. `97-health-food-intake.yaml` scripts the model's side
 * (12:00 today, 450 kcal); what is proved is the chain through the real tool and what the API
 * stored. An account of its own: nothing else writes its journal.
 */

interface IntakeRow {
  '@id': string
  id: string
  label: string
  kcal: number
  source: string
}

test.use({ journeyAccount: 'empty' })

test('Maggie records a dish told in conversation in the food journal — MAG-748', async ({ page, api }) => {
  const today = parisDay()
  const journalOfToday = `/api/health/food-intakes?date[after]=${today}&date[before]=${today}`

  try {
    const nutrition = new HealthNutritionPage(page)
    await nutrition.open()

    const events = await new ChatPanel(page).send("J'ai mangé une salade César à midi")

    expect(calledTools(events), 'the food journal tool never ran — did the scenario stop matching?').toEqual(['manage_food_intakes'])
    expect(toolResults(events)).toEqual([expect.objectContaining({ toolName: 'manage_food_intakes', status: 'success' })])

    // The API holds it for today, told in conversation…
    let salad: IntakeRow | undefined
    await expect
      .poll(
        async () => {
          salad = (await getCollection<IntakeRow>(api, journalOfToday)).find((row) => row.label === 'Salade César')

          return salad && { kcal: salad.kcal, source: salad.source }
        },
        { message: 'the Caesar salad was not stored in today’s journal', timeout: 30_000 },
      )
      .toEqual({ kcal: 450, source: 'conversation' })

    // …and the journal shows it without a reload, from Maggie.
    await expect(nutrition.intakeRow(salad?.id ?? '').getByTestId('intake-source')).toHaveText('Maggie')
  } finally {
    for (const intake of await getCollection<IntakeRow>(api, journalOfToday)) {
      const removed = await api.delete(intake['@id'])
      expect(removed.ok() || removed.status() === 404, `DELETE ${intake['@id']} answered ${removed.status()}`).toBeTruthy()
    }
  }
})

import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { HealthConfigurationPage } from '../pages/HealthConfigurationPage.js'
import { ROUTES } from '../pages/routes.js'

/**
 * Santé › Configuration, the profile and the day's need — MAG-707.
 *
 * Given le seed sans profil (des pesées, aucune aujourd'hui),
 * When je règle Homme, 38 ans, 178 cm, 72 kg, Sédentaire dans Santé › Configuration,
 * Then « 1 980 kcal » et « métabolisme de base 1 648 kcal » s'affichent, le poids
 * saisi est une pesée d'aujourd'hui, et la fiche d'une recette montre « % du besoin du jour ».
 *
 * Nouveau parcours Santé › Configuration: Santé › Suivi (MAG-706) is its neighbour,
 * which the weight typed here feeds.
 */

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface StoredProfile {
  id: string | null
  sex: string | null
  age: number | null
  heightCm: number | null
  weightKg: number | null
  activityLevel: string
  mode: string
  bmrKcal: number | null
  needKcal: number | null
}

// The account of this journey alone: the profile it writes is no other journey's.
test.use({ journeyAccount: 'standard' })

test('Santé › Configuration computes 1 980 kcal for Homme, 38 ans, 178 cm, 72 kg, Sédentaire, and a recipe sheet shows its share — MAG-707', async ({
  page,
  api,
}) => {
  const { retry } = test.info()
  const suffix = 0 === retry ? '' : ` essai ${retry}`

  // Given no profile yet.
  const before = await api.get('/api/health/profile')
  expect(before.status(), `GET /api/health/profile answered ${before.status()}`).toBe(200)
  expect(((await before.json()) as StoredProfile).id).toBeNull()

  const configuration = new HealthConfigurationPage(page)
  await configuration.goto('/')
  await configuration.expectLoaded()

  // When I open Santé › Configuration, the last part of Santé…
  await configuration.navigateTo('Configuration', 'Santé')
  await expect(configuration.profile).toBeVisible()
  await expect(configuration.mode('Calculé')).toHaveAttribute('aria-checked', 'true')
  await expect(configuration.needKcal).toHaveText('— kcal')

  // …and set Homme, 38 ans, 178 cm, 72 kg, Sédentaire.
  await configuration.pick('Sexe', 'Homme')
  await configuration.type('Âge', '38')
  await configuration.type('Taille', '178')
  await configuration.type('Poids', '72')
  await configuration.pick('Activité', 'Modérée · × 1,55')
  await configuration.pick('Activité', 'Sédentaire · × 1,2')

  // Then the day's need is 1 980 kcal over a basal metabolism of 1 648 kcal…
  await expect(configuration.needKcal).toHaveText(/^1\s980 kcal$/)
  await expect(configuration.bmrKcal).toHaveText(/^métabolisme de base 1\s648 kcal$/)
  await expect(configuration.need.getByText('× 1,2', { exact: true })).toBeVisible()

  // …the API holds the profile, its weight the weighing just recorded…
  const stored = (await (await api.get('/api/health/profile')).json()) as StoredProfile
  expect(stored).toMatchObject({ sex: 'male', age: 38, heightCm: 178, weightKg: 72, activityLevel: 'sedentary', mode: 'computed', bmrKcal: 1648, needKcal: 1980 })
  expect(stored.id).not.toBeNull()
  await expect
    .poll(async () => {
      const read = await api.get('/api/body-measurements?order[measuredAt]=desc&itemsPerPage=1', { headers: { Accept: 'application/ld+json' } })
      const [latest] = ((await read.json()) as { member: { weightKg: number | null; source: string }[] }).member

      return latest && { weightKg: latest.weightKg, source: latest.source }
    })
    .toEqual({ weightKg: 72, source: 'manual' })

  // …and a recipe sheet shows a serving as a share of that need: 396 kcal of 1 980 is 20 %.
  const ingredient = await api.post('/api/ingredients', {
    headers: jsonLd,
    data: { name: `Lentilles MAG-707${suffix}`, category: 'other', kcalPer100g: 200, proteinPer100g: 9, carbsPer100g: 20, fatPer100g: 1 },
  })
  expect(ingredient.status(), `POST /api/ingredients answered ${ingredient.status()}: ${await ingredient.text()}`).toBe(201)
  const recipe = await api.post('/api/recipes', {
    headers: jsonLd,
    data: {
      name: `Dahl MAG-707${suffix}`,
      servings: 1,
      ingredients: [{ ingredient: ((await ingredient.json()) as { '@id': string })['@id'], quantity: 198, unit: 'g' }],
    },
  })
  expect(recipe.status(), `POST /api/recipes answered ${recipe.status()}: ${await recipe.text()}`).toBe(201)
  const iri = ((await recipe.json()) as { '@id': string })['@id']

  const sheet = new AdminShell(page)
  await sheet.goto(`${ROUTES.recipes}/${encodeURIComponent(iri)}/show`)
  await expect(sheet.content.getByTestId('nutrition-energy')).toHaveText('≈ 396 kcal · 20 % du besoin du jour')
})

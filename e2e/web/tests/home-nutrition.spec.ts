import { test, expect } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The Nutrition card of the Accueil (MAG-564, extends MAG-99): the meals of the
 * day, lunch then dinner, and what the stock lacks for them.
 *
 * An account of its own, `e2e-nutrition@maggie.local` (65-nutrition-home.yaml):
 * tonight's dinner has two recipes, tomorrow's lunch asks for a parmesan the
 * user is out of, tomorrow's dinner is empty.
 */
test('the Nutrition card shows tonight’s meal with its recipes, then tomorrow’s lunch and what it lacks', async ({
  nutritionUser,
}) => {
  const { page } = nutritionUser
  const home = new HomePage(page)
  const card = page.getByTestId('home-card-nutrition')
  const slot = (name: 'lunch' | 'dinner') => card.getByTestId(`home-nutrition-${name}`)

  // Given tonight's dinner with two recipes, when I open the Accueil…
  await home.open()

  // …the card shows that meal and its two recipes, and « — » for lunch.
  await expect(slot('dinner').getByTestId('home-nutrition-meal')).toHaveCount(1)
  await expect(slot('dinner').getByTestId('home-nutrition-recipes')).toHaveText('Pâtes au pesto · Salade de saison')
  await expect(slot('dinner').getByTestId('home-nutrition-missing')).toHaveCount(0)
  await expect(slot('lunch').getByTestId('home-nutrition-empty')).toHaveText('—')

  // When I switch to Demain, tomorrow's lunch comes with what is missing, and dinner is empty.
  await home.day('Demain').click()

  await expect(slot('lunch').getByTestId('home-nutrition-recipes')).toHaveText('Soupe de potiron')
  await expect(slot('lunch').getByTestId('home-nutrition-missing')).toHaveText('manque : Parmesan')
  await expect(slot('dinner').getByTestId('home-nutrition-empty')).toHaveText('—')
})

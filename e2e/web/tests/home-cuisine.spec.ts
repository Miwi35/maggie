import { test, expect } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The Cuisine card of the Accueil (MAG-564, extends MAG-99): the meals of the
 * day, lunch then dinner, and what the stock lacks for them.
 *
 * An account of its own, `e2e-cuisine@maggie.local` (65-cuisine-home.yaml):
 * tonight's dinner has two recipes, tomorrow's lunch asks for a parmesan the
 * user is out of, tomorrow's dinner is empty.
 */
test('the Cuisine card shows tonight’s meal with its recipes, then tomorrow’s lunch and what it lacks', async ({
  cuisineUser,
}) => {
  const { page } = cuisineUser
  const home = new HomePage(page)
  const card = page.getByTestId('home-card-cuisine')
  const slot = (name: 'lunch' | 'dinner') => card.getByTestId(`home-cuisine-${name}`)

  // Given tonight's dinner with two recipes, when I open the Accueil…
  await home.open()

  // …the card shows that meal and its two recipes, and « — » for lunch.
  await expect(slot('dinner').getByTestId('home-cuisine-meal')).toHaveCount(1)
  await expect(slot('dinner').getByTestId('home-cuisine-recipes')).toHaveText('Pâtes au pesto · Salade de saison')
  await expect(slot('dinner').getByTestId('home-cuisine-missing')).toHaveCount(0)
  await expect(slot('lunch').getByTestId('home-cuisine-empty')).toHaveText('—')

  // When I switch to Demain, tomorrow's lunch comes with what is missing, and dinner is empty.
  await home.day('Demain').click()

  await expect(slot('lunch').getByTestId('home-cuisine-recipes')).toHaveText('Soupe de potiron')
  await expect(slot('lunch').getByTestId('home-cuisine-missing')).toHaveText('manque : Parmesan')
  await expect(slot('dinner').getByTestId('home-cuisine-empty')).toHaveText('—')
})

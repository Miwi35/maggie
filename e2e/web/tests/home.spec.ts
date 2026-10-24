import { test, expect } from '../fixtures/index.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The Accueil (MAG-560): the trail says where we are and a selector picks the
 * day every card shows. The selector lives in the URL, so a reload or a shared
 * link keeps it. The cards come with their own tickets and journeys.
 */
test('the Accueil shows its trail and a day selector that switches to « Demain » @responsive', async ({ page }) => {
  const home = new HomePage(page)
  await home.open()

  await expect(home.day("Aujourd'hui")).toHaveAttribute('aria-pressed', 'true')
  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'false')

  await home.day('Demain').click()

  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
  await expect(page).toHaveURL(/day=tomorrow/)

  // A reload keeps the day.
  await page.reload()
  await home.expectReady()
  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
})

test('a link to « Demain » opens the Accueil on it', async ({ page }) => {
  const home = new HomePage(page)
  await home.open('tomorrow')

  await expect(home.day('Demain')).toHaveAttribute('aria-pressed', 'true')
})

/**
 * The Courses card (MAG-563, extends MAG-99): the open list by shop, five lines
 * of it and « + n » for the rest. Nothing on it is ticked from here — touching it
 * opens Courses.
 */
test('the Courses card shows five lines of a six-line list and « + 1 »', async ({ homeUser }) => {
  const home = new HomePage(homeUser.page)
  await home.open()

  const card = home.groceryCard
  await expect(card.getByTestId('home-grocery-store')).toHaveCount(1)
  await expect(card.getByTestId('home-grocery-store')).toContainText("Biocoop de l'Accueil")
  await expect(card.getByTestId('home-grocery-item')).toHaveCount(5)
  await expect(card.getByTestId('home-grocery-more')).toHaveText('+ 1')

  // The card does not follow the selector, and it is read-only.
  await home.day('Demain').click()
  await expect(card.getByTestId('home-grocery-item')).toHaveCount(5)
  await expect(card.getByRole('checkbox')).toHaveCount(0)
})

/**
 * The Finance card (MAG-565, extends MAG-102): the week and the month as rings with their
 * rhythm. The Accueil's account has a 1 000 € envelope and 10 € spent on the 1st, under the pace
 * whatever the day. Touching the card opens Finance › Dashboard.
 */
test('the Finance card says « Dans le rythme » and opens the Dashboard', async ({ homeUser }) => {
  const home = new HomePage(homeUser.page)
  await home.open()

  const card = home.financeCard
  await expect(card.getByTestId('home-finance-ring')).toHaveCount(2)
  await expect(card.getByTestId('home-finance-status').first()).toContainText('Dans le rythme')
  await expect(card.getByTestId('home-finance-status').last()).toContainText('Dans le rythme')

  // It does not follow the selector.
  await home.day('Demain').click()
  await expect(card.getByTestId('home-finance-ring')).toHaveCount(2)

  await card.getByRole('link', { name: 'Finance' }).first().click()
  await expect(homeUser.page).toHaveURL(/\/finance\/dashboard/)
})

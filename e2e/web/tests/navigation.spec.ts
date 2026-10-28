import { test, expect } from '../fixtures/index.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The sidebar of the mockup (MAG-620, extends MAG-99): the modules in one flat
 * column, the sub-entries only under the module the page belongs to, and « Tâches »
 * reachable from the menu.
 */

test('the menu lists the modules of the mockup and « Tâches » opens the Tâches page @responsive', async ({ page }) => {
  const shell = new AdminShell(page)

  await shell.goto(ROUTES.home)
  await shell.expectLoaded()
  await shell.openMenu()

  const avatar = page.getByTestId('menu-header').getByTestId('maggie-avatar')
  await expect(avatar).toBeVisible()
  await expect(avatar.locator('img')).toBeVisible()

  for (const module of ['Accueil', 'Agenda', 'Tâches', 'Courses', 'Cuisine', 'Finance', 'Réglages']) {
    await expect(shell.menuItem(module), module).toBeVisible()
  }

  for (const gone of ['Chat', 'Calendrier', 'Paramètres', 'Nutrition', 'Données brutes', 'Santé', 'Loisirs']) {
    await expect(shell.menuItem(gone), gone).toHaveCount(0)
  }

  await shell.navigateTo('Tâches')

  await expect(page).toHaveURL(new RegExp(`#${ROUTES.tasks}$`))
  await expect(shell.content.getByRole('heading', { level: 1, name: "Aujourd'hui", exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: "Fil d'Ariane" })).toContainText('Tâches')
})

test('only the current module opens its entries, and « Agenda » opens the agenda @responsive', async ({ page }) => {
  const shell = new AdminShell(page)

  await shell.goto(ROUTES.home)
  await shell.expectLoaded()
  await shell.openMenu()
  await expect(shell.menuItem('Magasins')).toHaveCount(0)

  await shell.navigateTo('Courses')
  await shell.openMenu()
  await expect(shell.menuItem('Magasins')).toBeVisible()
  await expect(shell.menuItem('Recettes')).toHaveCount(0)

  await shell.navigateTo('Agenda')
  await expect(page).toHaveURL(new RegExp(`#${ROUTES.calendar}$`))
  await shell.openMenu()
  await expect(shell.menuItem('Magasins')).toHaveCount(0)
})

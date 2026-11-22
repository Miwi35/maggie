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

  for (const module of ['Accueil', 'Agenda', 'Tâches', 'Courses', 'Cuisine', 'Finance', 'Santé', 'Réglages']) {
    await expect(shell.menuItem(module), module).toBeVisible()
  }

  for (const gone of ['Chat', 'Calendrier', 'Paramètres', 'Nutrition', 'Données brutes', 'Loisirs']) {
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

/** The admin installs as an app (MAG-894): a French page, a manifest named « Maggie », a service worker on `/admin/`. */
test.describe('PWA installable', () => {
  test.use({ serviceWorkers: 'allow' })

  test('the admin page is French and titled Maggie', async ({ page }) => {
    const shell = new AdminShell(page)

    await shell.goto(ROUTES.home)
    await shell.expectLoaded()

    await expect(page.locator('html')).toHaveAttribute('lang', 'fr')
    await expect(page).toHaveTitle('Maggie')
    await expect(page.locator('link[rel="manifest"]')).toHaveCount(1)
  })

  test('the manifest names Maggie with its 192 and 512 icons', async ({ page, request }) => {
    const shell = new AdminShell(page)

    await shell.goto(ROUTES.home)
    await shell.expectLoaded()

    const href = await page.locator('link[rel="manifest"]').getAttribute('href')
    const manifestUrl = new URL(href ?? '', page.url()).toString()
    const response = await request.get(manifestUrl)
    expect(response.status()).toBe(200)

    const manifest = await response.json()
    expect(manifest).toMatchObject({
      name: 'Maggie',
      short_name: 'Maggie',
      lang: 'fr',
      display: 'standalone',
      start_url: '/admin/',
      scope: '/admin/',
    })

    for (const sizes of ['192x192', '512x512']) {
      const icon = manifest.icons.find((i: { sizes: string }) => i.sizes === sizes)
      expect(icon, sizes).toBeDefined()
      expect((await request.get(new URL(icon.src, manifestUrl).toString())).status(), sizes).toBe(200)
    }
  })

  test('a service worker controls /admin/', async ({ page }) => {
    const shell = new AdminShell(page)

    await shell.goto(ROUTES.home)
    await shell.expectLoaded()

    const scope = await page.evaluate(async () => (await navigator.serviceWorker.ready).scope)
    expect(scope).toMatch(/\/admin\/$/)
  })
})

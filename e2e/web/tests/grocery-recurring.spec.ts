import { test, expect } from '../fixtures/index.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * Recurring purchases, from where the owner works (MAG-369).
 *
 * The generated « Articles récurrents » pages were refused at acceptance: no
 * way to make a product recurring from the list or the product sheet, and the
 * collection lived in a menu entry of its own. The setting now lives on the
 * line and on the product, and the items on a « Récurrents » tab of the list.
 */

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

test('a line is made recurring, shows up in the Récurrents tab with its next date, and is removed from there', async ({
  page,
}) => {
  const label = `Lait MAG-369 ${Date.now()}`
  const grocery = new GroceryListPage(page)
  await grocery.open()

  await expect(grocery.tab('Liste')).toHaveAttribute('aria-selected', 'true')

  await grocery.addItem(label)
  await expect(grocery.line(label)).toBeVisible()

  try {
    await grocery.setLineRecurrence(label, 'Toutes les semaines')
    await expect(grocery.line(label).getByRole('button', { name: `Récurrence de ${label} : toutes les semaines` })).toBeVisible()

    await grocery.openRecurringTab()
    const row = grocery.recurringRow(label)
    await expect(row).toBeVisible()
    await expect(row).toContainText('Prochaine échéance : ')
    await expect(row).toContainText(/\d{2}\/\d{2}\/\d{4}/)

    await grocery.removeRecurring(label)
    await expect(row).toBeHidden()

    await grocery.tab('Liste').click()
    await expect(grocery.line(label)).toBeVisible()
    await expect(grocery.line(label).getByRole('button', { name: `Rendre ${label} récurrent` })).toBeVisible()
  } finally {
    await grocery.tab('Liste').click()
    await grocery.deleteLine(label)
  }
})

test('the product sheet sets the recurrence, and the Récurrents tab and the list line follow', async ({ page, api }) => {
  const name = `Beurre MAG-369 ${Date.now()}`
  const created = await api.post('/api/products', { headers: LD, data: { name, category: 'other' } })
  expect(created.status()).toBe(201)
  const iri = ((await created.json()) as { '@id': string })['@id']

  try {
    const shell = new AdminShell(page)
    await shell.goto(`${ROUTES.products}/${encodeURIComponent(iri)}`)

    const written = page.waitForResponse(
      (response) => response.url().includes('/api/recurring_grocery_items') && response.request().method() === 'POST',
    )
    await shell.productPanel.getByRole('combobox', { name: 'Récurrent' }).click()
    await page.getByRole('option', { name: 'Toutes les 2 semaines' }).click()
    expect((await written).status()).toBe(201)

    const grocery = new GroceryListPage(page)
    await grocery.open()
    await grocery.openRecurringTab()
    const row = grocery.recurringRow(name)
    await expect(row).toBeVisible()
    await expect(row).toContainText('Prochaine échéance : ')

    await grocery.removeRecurring(name)
    await expect(row).toBeHidden()
  } finally {
    await api.delete(iri)
  }
})

import { test, expect, e2eNow } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The Produits page as the mockup « Admin-Produits » draws it (MAG-622): the
 * pastilles narrow the products, which stay grouped by rayon with its French
 * name, and a click on a product opens its fiche in a panel on the right.
 *
 * Written on the owner's data: everything is created here, under names no other
 * journey uses, and removed in `finally`.
 */

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

/** The month of the pinned instant, in Paris — the user's timezone, so the one the API reads. */
function parisMonth(): number {
  return Number(new Intl.DateTimeFormat('en', { timeZone: 'Europe/Paris', month: 'numeric' }).format(e2eNow()))
}

async function create(api: APIRequestContext, data: Record<string, unknown>): Promise<string> {
  const response = await api.post('/api/products', { headers: LD, data })
  expect(response.status(), `POST /api/products answered ${response.status()}: ${await response.text()}`).toBe(201)

  return ((await response.json()) as { '@id': string })['@id']
}

test('« De saison » keeps the products in season, by rayon, and a click opens the fiche in a panel — MAG-622', async ({
  page,
  api,
}) => {
  const stamp = Date.now()
  const strawberries = `Fraises MAG-622 ${stamp}`
  const pumpkins = `Courges MAG-622 ${stamp}`
  const butter = `Beurre MAG-622 ${stamp}`
  const month = parisMonth()
  const cleanup: string[] = []

  try {
    // --- Given: products of two rayons, one of them out of season -----------
    cleanup.push(await create(api, { name: strawberries, category: 'produce', seasonMonths: [month] }))
    cleanup.push(await create(api, { name: pumpkins, category: 'produce', seasonMonths: [(month % 12) + 1] }))
    cleanup.push(await create(api, { name: butter, category: 'dairy' }))
    await waitForIndexed<{ name: string }>(api, '/api/products?itemsPerPage=100', (row) => row.name === butter, {
      what: 'The three products',
    })

    // --- When: I open Produits and click « De saison » ----------------------
    const shell = new AdminShell(page)
    await shell.goto(ROUTES.products)
    const pastille = (name: string) => shell.content.getByRole('button', { name, exact: true })
    for (const name of ['Tous', 'De saison', 'Conditionnés', 'Stock bas']) await expect(pastille(name)).toBeVisible()
    await expect(shell.content.getByText(butter)).toBeVisible()
    await expect(shell.content.getByRole('heading', { level: 3, name: /^Produits laitiers · \d+$/ })).toBeVisible()

    await pastille('De saison').click()

    // --- Then: only the products in season remain, under their rayon ---------
    await expect(pastille('De saison')).toHaveAttribute('aria-pressed', 'true')
    await expect(shell.content.getByText(strawberries)).toBeVisible()
    await expect(shell.content.getByText(pumpkins)).toHaveCount(0)
    await expect(shell.content.getByText(butter)).toHaveCount(0)
    await expect(shell.content.getByRole('heading', { level: 3, name: /^Fruits & Légumes · \d+$/ })).toBeVisible()

    // --- When: I click a product ---------------------------------------------
    await shell.content.getByText(strawberries).click()

    // --- Then: its fiche opens in the panel on the right ---------------------
    const panel = page.getByRole('dialog', { name: strawberries })
    await expect(panel).toBeVisible()
    await expect(panel.getByLabel('Nom', { exact: false })).toHaveValue(strawberries)
    const box = await panel.boundingBox()
    const viewport = page.viewportSize()
    if (box && viewport && viewport.width >= 900) expect(box.x + box.width).toBeGreaterThan(viewport.width - 4)

    await panel.getByRole('button', { name: 'Fermer' }).click()
    await expect(panel).toBeHidden()
    await expect(shell.content.getByText(strawberries)).toBeVisible()
  } finally {
    for (const iri of cleanup) await api.delete(iri)
  }
})

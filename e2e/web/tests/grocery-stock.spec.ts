import { test, expect } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { expectRealtimeSync, openSubscribed } from '../helpers/mercure.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * Stock et courses au plus juste (MAG-292, extends MAG-101): what a product is
 * bought in.
 *
 * On n'achète jamais la quantité exacte d'une recette : 300 g de riz, c'est un
 * paquet de 500 g. Everything the project builds next reads this packaging, so
 * the journey checks the three places it has to hold: the form that sets it,
 * the reopened form after a reload, and a second window that sees it arrive
 * through Mercure without reloading.
 */

const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

type ProductRow = {
  name: string
  packagingUnit?: string | null
  packagingSize?: number | null
  packagingSizeUnit?: string | null
}

test('the packaging chosen in the product form is saved, read back, and seen live in a second window', async ({
  twoWindows,
  api,
}) => {
  const name = `Riz MAG-292 ${Date.now()}`
  const created = await api.post('/api/products', { headers: LD, data: { name, category: 'grain' } })
  expect(created.status()).toBe(201)
  const iri = ((await created.json()) as { '@id': string })['@id']

  const { actor, observer } = twoWindows
  const acting = new AdminShell(actor)
  const watching = new AdminShell(observer)

  try {
    // Given a product « Riz » without packaging, listed in the second window.
    await waitForIndexed<ProductRow>(api, '/api/products?itemsPerPage=200', (row) => row.name === name, {
      what: 'The new product',
    })
    await openSubscribed(observer, () => watching.goto(ROUTES.products))
    const row = watching.content.getByRole('row').filter({ hasText: name })
    await expect(row).toBeVisible()
    await expect(row).not.toContainText('paquet de 500 g')

    await acting.goto(`${ROUTES.products}/${encodeURIComponent(iri)}`)
    await expect(acting.content.getByText(/Pas de conditionnement/)).toBeVisible()

    // When I give it the packaging « paquet » of 500 g in the admin.
    await expectRealtimeSync(
      observer,
      async () => {
        await acting.content.getByLabel('Conditionnement', { exact: true }).click()
        await actor.getByRole('option', { name: 'paquet', exact: true }).click()
        await acting.content.getByLabel('Contenu', { exact: true }).fill('500')
        await acting.content.getByLabel('Unité du contenu').click()
        await actor.getByRole('option', { name: 'g', exact: true }).click()
        await expect(acting.content.getByText("On l'achète en : paquet de 500 g")).toBeVisible()

        const patched = actor.waitForResponse(
          (response) => response.url().includes(iri) && response.request().method() === 'PATCH',
        )
        await acting.content.getByRole('button', { name: 'Enregistrer' }).click()
        const response = await patched
        expect(response.status(), `the API refused the packaging: ${await response.text()}`).toBe(200)

        // The list the Mercure update refetches is served from Elasticsearch.
        await waitForIndexed<ProductRow>(
          api,
          '/api/products?itemsPerPage=200',
          (r) => r.name === name && r.packagingUnit === 'pack' && r.packagingSizeUnit === 'g',
          { what: 'The product with its packaging' },
        )
      },
      // Then the second window sees it arrive without reloading.
      async () => {
        await expect(row).toContainText('paquet de 500 g')
      },
    )

    // And the value holds after a reload (saving went back to the list).
    await acting.goto(`${ROUTES.products}/${encodeURIComponent(iri)}`)
    await actor.reload()
    await expect(acting.content.getByText("On l'achète en : paquet de 500 g")).toBeVisible()
    const stored = (await (await api.get(iri, { headers: { Accept: 'application/ld+json' } })).json()) as ProductRow
    expect(stored.packagingSize).toBe(500)
  } finally {
    await api.delete(iri)
  }
})

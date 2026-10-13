import { test, expect } from '../fixtures/index.js'
import type { APIRequestContext, Locator } from '@playwright/test'
import { openSubscribed } from '../helpers/mercure.js'
import { AdminShell } from '../pages/AdminShell.js'
import { ROUTES } from '../pages/routes.js'

/**
 * An open list follows the API without a reload (MAG-347, extends MAG-101,
 * MAG-102 and MAG-103).
 *
 * The lists are served from Elasticsearch, indexed by the worker after the
 * write, while the Mercure event leaves with the write: a list that read the
 * collection again on the event missed the change until a reload. Each list
 * applies the event itself. Names are unique to the attempt, and the writes are
 * the neighbour's, so no other journey's list counts them.
 */

const LIVE = 5_000
const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const MERGE_PATCH = { 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }

interface Created {
  '@id': string
}

async function create(api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<string> {
  const response = await api.post(path, { headers: LD, data })
  expect(response.status(), await response.text()).toBe(201)

  return ((await response.json()) as Created)['@id']
}

async function rename(api: APIRequestContext, iri: string, name: string): Promise<void> {
  const response = await api.patch(iri, { headers: MERGE_PATCH, data: { name } })
  expect(response.ok(), await response.text()).toBe(true)
}

async function remove(api: APIRequestContext, iri: string): Promise<void> {
  expect((await api.delete(iri)).ok()).toBe(true)
}

const LISTS = [
  { title: 'stores', route: '/stores', collection: '/api/stores', body: {} },
  { title: 'recipes', route: ROUTES.recipes, collection: '/api/recipes', body: { servings: 2 } },
  { title: 'categories', route: ROUTES.categories, collection: '/api/categories', body: { obligation: 'optional' } },
] as const

for (const list of LISTS) {
  test(`the open ${list.title} list shows what the API creates, renames and deletes, without a reload`, async ({ otherUser }) => {
    const { api, page } = otherUser
    const attempt = test.info().retry
    const created = `Live ${list.title} ${attempt} créé`
    const renamed = `Live ${list.title} ${attempt} renommé`
    const shell = new AdminShell(page)
    const row = (name: string): Locator => shell.content.getByText(name, { exact: true })

    await openSubscribed(page, () => shell.goto(list.route))
    const navigations: string[] = []
    page.on('framenavigated', (frame) => {
      if (frame.parentFrame() === null) navigations.push(frame.url())
    })

    const iri = await create(api, list.collection, { name: created, ...list.body })
    await expect(row(created)).toBeVisible({ timeout: LIVE })

    await rename(api, iri, renamed)
    await expect(row(renamed)).toBeVisible({ timeout: LIVE })
    await expect(row(created)).toHaveCount(0)

    await remove(api, iri)
    await expect(row(renamed)).toHaveCount(0, { timeout: LIVE })

    expect(navigations, 'the list reloaded — that proves nothing about real-time').toEqual([])
  })
}

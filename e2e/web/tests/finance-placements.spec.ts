import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { FinancePlacementsPage } from '../pages/FinancePlacementsPage.js'

/**
 * The simulated placements (MAG-555, extends MAG-102), in Finance ›
 * Planification › Indépendance.
 *
 * Seeded: « PEA » (2 000 € then 150 € a month at 6 % over 20 years),
 * « Assurance-vie » (1 200 € a year at 3,5 % over 15 years) and « Livret jeune
 * de Léo » (500 € once at 3 % over 10 years). The journey creates a placement
 * of 100 € a month at 5 % over 10 years: the API compounds it monthly at
 * (1,05)^(1/12) − 1, deposits at the end of each month, to 15 436 €.
 *
 * Only this file writes the placements, and only the one it creates:
 * `finally` deletes it, so a retry starts where the first attempt did.
 */

const NAME = 'Épargne de Léa'
const PATH = '/api/finance/placements'

interface StoredPlacement {
  id: string
  name: string
  initial: number
  deposit: number
  frequency: string
  annualRate: number
  years: number
  final: number
}

async function removeCreated(api: APIRequestContext): Promise<void> {
  for (const placement of await getCollection<StoredPlacement>(api, `${PATH}?pagination=false`)) {
    if (placement.name !== NAME) continue
    const deleted = await api.delete(`${PATH}/${placement.id}`)
    expect([204, 404], await deleted.text()).toContain(deleted.status())
  }
}

test('a placement of 100 € a month at 5 % over 10 years would reach 15 436 €', async ({ page, api }) => {
  await removeCreated(api)
  const screen = new FinancePlacementsPage(page)

  try {
    // --- Given: the three seeded placements ---------------------------------
    await screen.open()
    await expect(screen.row('PEA')).toContainText('150 € / mois · 2 000 € initial')
    await expect(screen.row('Assurance-vie')).toContainText('1 200 € / an')
    await expect(screen.row('Livret jeune de Léo')).toContainText('500 € une fois')
    await expect(screen.card).toContainText('250 € / mois de versements')

    // --- When: a placement of 100 € a month at 5 % over 10 years ------------
    const saved = page.waitForResponse((response) => response.url().endsWith(PATH) && response.request().method() === 'POST')
    await screen.create({ name: NAME, deposit: '100', rate: '5', years: '10' })
    expect((await saved).status()).toBe(201)

    // --- Then: its row, its final capital and its curve ----------------------
    const row = screen.row(NAME)
    await expect(row).toContainText('100 € / mois')
    await expect(row).toContainText('5 %')
    await expect(row).toContainText('10 ans')
    await expect(screen.finalCapital(NAME)).toHaveText('15 436 €')
    await expect(row).toHaveAttribute('aria-selected', 'true')
    await expect(screen.simulation().getByRole('img', { name: new RegExp(`^Courbe de ${NAME} sur 10 ans`) })).toBeVisible()
    await expect(screen.simulation()).toContainText('Retiré à 4 % par an, ce capital donnerait environ')
    await expect(screen.card).toContainText('350 € / mois de versements')

    // --- And: the database holds it, in cents and basis points -------------
    const stored = await waitForIndexed<StoredPlacement>(api, `${PATH}?pagination=false`, (placement) => placement.name === NAME, {
      what: `the placement « ${NAME} »`,
    })
    expect(stored).toMatchObject({ initial: 0, deposit: 10000, frequency: 'monthly', annualRate: 500, years: 10 })
    expect(Math.round(stored.final / 100)).toBe(15436)

    // --- And: it outlives a reload ------------------------------------------
    await page.reload()
    await expect(screen.finalCapital(NAME)).toHaveText('15 436 €')
  } finally {
    await removeCreated(api)
  }
})

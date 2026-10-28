import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { FinanceCushionPage } from '../pages/FinanceCushionPage.js'
import { FinanceProjectsPage } from '../pages/FinanceProjectsPage.js'

/**
 * The cushion's share of the capacity past its critical threshold (MAG-669, extends MAG-102).
 *
 * Given a cushion above 2 months of spending but under its target, and a project
 * When I open Planification › Projets
 * Then the project has a purchase date within the 36 months
 * When I set the cushion's share to 100 %
 * Then the project moves back to after the cushion is complete
 *
 * An account of its own (69-finance-cushion-share.yaml): 3 000 € of income, 1 000 € of
 * measured spending, so 2 000 € of capacity a month; 3 000 € on the cushion against a
 * 9 000 € target (6 000 € missing) and a 2 000 € critical threshold, already passed; one
 * project, « Moto », 1 500 €.
 *
 *   share 60 %   1 200 € to the cushion, 800 € to Moto a month → bought in M1
 *   share 100 %  2 000 € to the cushion in M0, M1 and M2, complete in M2 → Moto in M3
 *
 * The share is written back to 60 % through the API in `finally`, so a retry starts
 * from the seed.
 */

const MOTO = 'Moto'
const SEEDED_SHARE = 60

const setShare = async (api: APIRequestContext, percent: number): Promise<void> => {
  const response = await api.patch('/api/finance/cushion-config', {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    data: { cushionSharePercent: percent },
  })
  expect(response.ok(), `PATCH cushion-config answered ${response.status()}`).toBe(true)
}

test('a 100 % share gives the cushion the whole capacity and moves the project after it', async ({
  cushionShareUser,
}) => {
  const { page, api } = cushionShareUser
  // A first attempt that failed half-way may have left the share at 100 %.
  await setShare(api, SEEDED_SHARE)

  try {
    // --- Given / When: the planning with the seeded 60 % ---------------------
    const projects = new FinanceProjectsPage(page)
    await projects.open()
    await expect(projects.ganttRow(MOTO)).toBeVisible()
    await expect
      .poll(() => projects.purchaseAt(MOTO), { message: '800 € a month gathers the 1 500 € in M1' })
      .toBe(1)

    // --- When: the share set to 100 % on Trésorerie › matelas -----------------
    const cushion = new FinanceCushionPage(page)
    await cushion.open()
    await expect(cushion.shareRule).toHaveText(
      "Seuil critique atteint : 60 % de l'épargne va au matelas, le reste aux projets.",
    )
    await cushion.setSharePercent(100)
    await expect(cushion.shareRule).toHaveText(
      "Matelas d'abord : toute l'épargne va au matelas, les projets attendent qu'il soit complet.",
    )

    // --- Then: Moto comes the month after the cushion is complete -------------
    await projects.open()
    await expect(projects.ganttRow(MOTO)).toBeVisible()
    await expect
      .poll(() => projects.purchaseAt(MOTO), {
        message: '2 000 € a month fills the 6 000 € missing in M0–M2, Moto is bought in M3',
      })
      .toBe(3)
  } finally {
    await setShare(api, SEEDED_SHARE)
  }
})

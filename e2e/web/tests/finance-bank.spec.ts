import { test, expect, seedId } from '../fixtures/index.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { CONNECTION_STATUS_LABELS } from '../pages/financeLabels.js'
import { FinanceBanksPage } from '../pages/FinanceBanksPage.js'

/**
 * The bank link, from the admin (MAG-102).
 *
 * The provider is WireMock in this stack, so the bank list, the balances and
 * the movements all come from `.docker/e2e/wiremock/mappings/enablebanking.json`
 * — a request leaving the Docker network would be a bug, and the smoke journey
 * asserts that it does not happen (step 7).
 *
 * What a browser cannot drive is the consent itself: `connect()` hands the
 * window over to the bank's own screen, which here is `http://wiremock:8080/…`
 * — a different origin, and every context in this suite is cut off from
 * anything that is not the stack's router. The connect → consent → callback
 * round trip is therefore driven over HTTP in `e2e/smoke/smoke.sh` (step 12),
 * where the single-use state can be read back. This file covers the admin's
 * half: the picker is filled from the provider, and the fetch imports what is
 * new while recognising what it already holds.
 */

interface StoredTransaction {
  id?: string
  label?: string
  amountCents?: number
  bookedAt?: string
  category?: string | null
  categorySource?: string
}

test('the bank picker is filled by the provider, and the connection says where it stands', async ({
  page,
}) => {
  const banks = new FinanceBanksPage(page)
  await banks.open()

  expect(
    await banks.bankOptions(),
    'the picker lists what the provider answered, not a hard-coded set',
  ).toEqual(['Mock Bank', 'Other Mock Bank'])

  const connection = banks.connection('Mock Bank')
  await expect(connection).toContainText(CONNECTION_STATUS_LABELS.active)
  // The consent runs to 2099 in the fixture, deliberately: `isUsable()` reads
  // the real clock, so a near date would make the link look expired and the
  // fetch would quietly do nothing.
  await expect(connection).not.toContainText('Accès expiré')
})

/**
 * Fetching, and then fetching again.
 *
 * Re-importing has to be harmless — a bank reissues exports with the same
 * lines, and the sync deliberately overlaps the days it already has — so the
 * assertion is a count, made after each fetch: exactly one of each movement,
 * whatever happened before.
 *
 * Phrased as a count rather than as "absent, then present" on purpose: CI
 * retries once without reseeding, so a second attempt starts on the first
 * one's import — and "exactly one after fetching" is the contract either way.
 */
test('the fetch imports the movements, applies the rules, and recognises them next time', async ({
  page,
  api,
}) => {
  const banks = new FinanceBanksPage(page)
  await banks.open()
  await banks.fetchMovements()

  // The rules the owner already wrote apply to what the bank brings: the
  // statement's own spelling contains "LECLERC", so the seeded rule claims it
  // on the way in rather than leaving it for a catch-up pass.
  const groceries = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === 'LECLERC RENNES CB',
    { what: "The bank's grocery movement" },
  )

  expect(groceries.amountCents).toBe(-4290)
  expect(groceries.category).toBe(`/api/categories/${seedId('e2e_category_groceries')}`)
  expect(groceries.categorySource).toBe('rule')

  const travel = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.label === 'SNCF CONNECT',
    { what: "The bank's travel movement" },
  )

  // No rule spells SNCF, so it arrives with no category — which is what makes
  // it a candidate for the suggestions screen later on.
  expect(travel.amountCents).toBe(-1800)
  expect(travel.category ?? null).toBeNull()

  await expect(
    banks.connection('Mock Bank'),
    'a fetch that imported something says when it happened',
  ).toContainText('Dernière synchronisation :')

  // Fetch again: the provider answers with the same three lines, and not one
  // of them may be stored twice.
  await banks.fetchMovements()

  const after = await getCollection<StoredTransaction>(api, '/api/transactions?itemsPerPage=100')
  expect(after.filter((candidate) => candidate.label === 'LECLERC RENNES CB')).toHaveLength(1)
  expect(after.filter((candidate) => candidate.label === 'SNCF CONNECT')).toHaveLength(1)

  // The savings account's balance is read back from the bank as well, and the
  // stubbed one matches the fixture: the fetch updates balances, it does not
  // invent them.
  const accounts = await getCollection<{ name?: string; balanceCents?: number }>(
    api,
    '/api/accounts',
  )
  expect(accounts.find((account) => account.name === 'Livret A')?.balanceCents).toBe(420000)
  expect(accounts.find((account) => account.name === 'Compte courant')?.balanceCents).toBe(184550)
})

import { test, expect, e2eNow, parisDay } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { HealthJournalPage } from '../pages/HealthJournalPage.js'
import { HealthSuiviPage } from '../pages/HealthSuiviPage.js'

/**
 * Santé › Journal, mood, fatigue and a note on today's entry — MAG-750.
 *
 * Given une pesée de 71,6 kg aujourd'hui (créée par l'API : le seed n'en a
 * aucune aujourd'hui, ce que le parcours Santé › Suivi exige),
 * When j'ouvre Santé › Journal et je note moral 7, fatigue 4 et une note,
 * Then l'entrée du jour montre le poids 71,6 kg et mes notes, comme
 * `GET /api/health/days/{today}`, And Santé › Suivi affiche la carte
 * Moral · fatigue avec le point du jour.
 *
 * Étend le parcours web Santé (Suivi MAG-706, Nutrition MAG-748). The account
 * is this journey's own; the weighing is removed at the end all the same.
 */

const jsonLd = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

interface Entry {
  weightKg: number | null
  mood: number | null
  fatigue: number | null
  note: string | null
  notedBy: string | null
}

const entryOf = async (api: APIRequestContext, day: string): Promise<Entry> => {
  const response = await api.get(`/api/health/days/${day}`, { headers: { Accept: 'application/json' } })
  expect(response.status(), `GET /api/health/days/${day} answered ${response.status()}`).toBe(200)

  return (await response.json()) as Entry
}

/** The weighing this journey created: no other journey may find it. */
async function removeWeighing(api: APIRequestContext, iri: string | null): Promise<void> {
  if (iri === null) return
  const removed = await api.delete(iri)
  expect(removed.ok() || removed.status() === 404, `DELETE ${iri} answered ${removed.status()}`).toBeTruthy()
}

test.use({ journeyAccount: 'standard' })

test('Santé › Journal records mood, fatigue and a note next to today’s weight, and Suivi draws them — MAG-750', async ({ page, api }) => {
  const today = parisDay()
  const note = `Bonne journée, balade au parc (essai ${test.info().retry})`
  let weighing: string | null = null

  try {
    // Given a weighing of 71,6 kg today…
    const created = await api.post('/api/body-measurements', { headers: jsonLd, data: { measuredAt: e2eNow().toISOString(), weightKg: 71.6 } })
    expect(created.status(), `POST /api/body-measurements answered ${created.status()}: ${await created.text()}`).toBe(201)
    weighing = ((await created.json()) as { '@id': string })['@id']

    // …which the journal takes as the day's weight once the day is refreshed.
    await expect
      .poll(async () => (await entryOf(api, today)).weightKg, { message: 'the weighing never reached today’s journal entry', timeout: 30_000 })
      .toBe(71.6)

    // When I open Santé › Journal…
    const journal = new HealthJournalPage(page)
    await journal.goto('/')
    await journal.expectLoaded()
    await journal.navigateTo('Journal', 'Santé')
    await expect(journal.day).toHaveText(/^Aujourd’hui /)
    await expect(journal.weight).toHaveText('71,6 kg')

    // …and note mood 7, fatigue 4 and a note,
    await journal.rate('Moral', 7)
    await journal.rate('Fatigue', 4)
    await journal.writeNote(note)

    // Then the entry shows the weight and my notes, on screen…
    await expect(journal.weight).toHaveText('71,6 kg')
    await expect(journal.score('Moral', 7)).toHaveAttribute('aria-checked', 'true')
    await expect(journal.score('Fatigue', 4)).toHaveAttribute('aria-checked', 'true')
    await expect(journal.note).toHaveValue(note)
    await expect(journal.row(today)).toContainText('71,6 kg')
    await expect(journal.row(today)).toContainText(note.slice(0, 30))

    // …and in the API.
    expect(await entryOf(api, today)).toMatchObject({ weightKg: 71.6, mood: 7, fatigue: 4, note, notedBy: 'user' })

    // And Santé › Suivi draws today's point on the « Moral · fatigue » card.
    const suivi = new HealthSuiviPage(page)
    await suivi.navigateTo('Suivi', 'Santé')
    await expect(suivi.moodCard).toBeVisible()
    await expect(suivi.moodCard.getByTestId('mood-value')).toHaveText('7')
    await expect(suivi.moodCard.getByTestId('fatigue-value')).toHaveText('4')
    await expect(suivi.moodCard.getByRole('button', { name: 'Aujourd’hui : moral 7, fatigue 4', exact: true })).toBeVisible()
  } finally {
    await removeWeighing(api, weighing)
  }
})

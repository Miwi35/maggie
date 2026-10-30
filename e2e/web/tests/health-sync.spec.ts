import { test, expect, e2eNow, parisDay, parisTime } from '../fixtures/index.js'
import { getCollection } from '../helpers/api.js'
import { HealthSportPage } from '../pages/HealthSportPage.js'

/**
 * Santé › Sport receives what the phone read in Health Connect — MAG-708.
 *
 * Given un lot `POST /api/health/sync` envoyé par le test, When j'ouvre
 * Santé › Sport, Then la séance du lot apparaît avec « Montre ». Rejouer le lot
 * ne change rien. The phone side (Health Connect is not on the CI emulator) is
 * covered by the JVM tests of `HealthConnectSync`.
 *
 * Extends the Santé journey of MAG-705 (`health-sport.spec.ts`).
 */

interface WorkoutRow {
  id: string
  externalId: string | null
  type: string
  durationMinutes: number
  source: string
}

interface SyncCounts {
  created: number
  updated: number
  unchanged: number
}

// The account of this journey alone: the workouts it reads are the ones it sent.
test.use({ journeyAccount: 'empty' })

test('Santé › Sport shows a watch run sent by the Health Connect sync, and the same batch replayed changes nothing — MAG-708', async ({
  page,
  api,
}) => {
  const externalId = `hc-run-e2e-${test.info().retry}`
  const batch = {
    workouts: [
      {
        externalId,
        type: 'run',
        title: `Course montre ${test.info().retry}`,
        startedAt: parisTime(parisDay(e2eNow()), '00:20:00'),
        durationMinutes: 32,
        distanceMeters: 5400,
        source: 'watch',
      },
    ],
  }

  // Given a batch sent as the phone sends it…
  const sent = await api.post('/api/health/sync', { data: batch })
  expect(sent.status(), `POST /api/health/sync answered ${sent.status()}`).toBe(200)
  expect(((await sent.json()) as { workouts: SyncCounts }).workouts).toEqual({ created: 1, updated: 0, unchanged: 0 })

  const stored = (await getCollection<WorkoutRow>(api, '/api/workouts?source=watch')).find((w) => w.externalId === externalId)
  expect(stored, `no watch workout ${externalId} in the API`).toBeDefined()
  expect(stored).toMatchObject({ type: 'run', durationMinutes: 32, source: 'watch' })
  const id = stored?.id.split('/').pop() ?? ''

  // When I open Santé › Sport…
  const sport = new HealthSportPage(page)
  await sport.goto('/')
  await sport.expectLoaded()
  await sport.navigateTo('Sport', 'Santé')

  // Then the run is listed with « Montre ».
  await expect(sport.row(id)).toBeVisible()
  await expect(sport.row(id)).toContainText(batch.workouts[0].title)
  await expect(sport.row(id)).toContainText('Montre')

  // The same batch again: nothing new.
  const replayed = await api.post('/api/health/sync', { data: batch })
  expect(replayed.status()).toBe(200)
  expect(((await replayed.json()) as { workouts: SyncCounts }).workouts).toEqual({ created: 0, updated: 0, unchanged: 1 })
  const runs = (await getCollection<WorkoutRow>(api, '/api/workouts?source=watch')).filter((w) => w.externalId === externalId)
  expect(runs).toHaveLength(1)
})

import { test, expect, e2eNow, parisDay, seedDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { CalendarPage } from '../pages/CalendarPage.js'
import { FinanceReviewActivityPage } from '../pages/FinanceReviewActivityPage.js'
import { ROUTES } from '../pages/routes.js'

/**
 * The monthly review as an activity (MAG-456, extends MAG-102): launched from its
 * appointment of the 25th in the « Finance » agenda, quit after the first line,
 * relaunched — it resumes on the second, because where the review stands is the
 * API's.
 *
 * An account of its own, `e2e-activity@maggie.local` (59-finance-activity.yaml):
 * where its review stands is the user's, on every device, and no other file walks
 * a review on it. The seed gives it two lines to qualify and « Loisirs ». A retry
 * meets the review the previous attempt left, so the journey books two lines of
 * its own, labels unique per attempt, and records every other line of the queue
 * as handled first: the two it walks are then the only ones left, in whatever
 * order the queue puts them.
 */

interface QueueItem {
  id: string
  label: string
}

interface StoredEvent {
  '@id'?: string
  id?: string
  summary?: string
  activity?: string | null
}

const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre']

/** The appointment's title, as both apps show it: « Revue d'octobre », « Revue de novembre ». */
const reviewTitle = (month: string): string => {
  const name = MONTHS[Number(month.slice(5, 7)) - 1]

  return /^[aeiouyéèâàô]/.test(name) ? `Revue d'${name}` : `Revue de ${name}`
}

const letters = (length: number): string =>
  Array.from({ length }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join('')

test('a review quit after the first line resumes on the second', async ({ activityUser }) => {
  const { api, page } = activityUser
  const month = parisDay(e2eNow()).slice(0, 7)
  const title = reviewTitle(month)

  // Reading the progress lays the review's appointment down, the first time.
  const progress = await api.get(`/api/finance/review/progress?month=${month}`, { headers: { Accept: 'application/json' } })
  expect(progress.status(), await progress.text()).toBe(200)

  // Every line the queue holds now is set aside: the journey walks its own two.
  const queued = await api.get(`/api/finance/review/queue?month=${month}`, { headers: { Accept: 'application/json' } })
  expect(queued.status(), await queued.text()).toBe(200)
  const earlier = ((await queued.json()) as { items: QueueItem[] }).items.map((item) => item.id)
  const reset = await api.put('/api/finance/review/progress', {
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    data: { month, step: 'review', handledTransactionIds: earlier },
  })
  expect(reset.status(), await reset.text()).toBe(200)

  const merchant = `ACTIVITE ${letters(8)}`
  const labels = [`${merchant} 1111`, `${merchant} 2222`]
  for (const label of labels) {
    const created = await api.post('/api/transactions', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: {
        account: `/api/accounts/${seedId('e2e_activity_checking')}`,
        label,
        amountCents: -1890,
        currency: 'EUR',
        bookedAt: seedDate(0),
        status: 'spent',
      },
    })
    expect(created.status(), await created.text()).toBe(201)
  }

  // Given 2 transactions to qualify
  await expect
    .poll(
      async () => {
        const res = await api.get(`/api/finance/review/queue?month=${month}`, { headers: { Accept: 'application/json' } })
        const items = ((await res.json()) as { items: QueueItem[] }).items

        return labels.filter((label) => items.some((item) => item.label === label)).length
      },
      { message: 'the two lines reach the review queue' },
    )
    .toBe(2)

  // When I launch the review from its appointment
  const appointment = await waitForIndexed<StoredEvent>(
    api,
    '/api/events?itemsPerPage=100',
    (event) => event.activity === 'finance-review',
    { what: 'The review appointment' },
  )
  // The deep link the search uses (see CalendarPage.openEvent), without its wait on an
  // agenda row: this account's only agenda is the Finance module's, which the list leaves out.
  const calendar = new CalendarPage(page)
  const iri = String(appointment['@id'] ?? `/api/events/${appointment.id}`)
  await calendar.goto(`${ROUTES.calendar}?eventId=${encodeURIComponent(iri)}`)
  await expect(calendar.detailTitle(title), 'the agenda shows the review under its month').toBeVisible()
  await expect(calendar.popover.getByText('Finance')).toBeVisible()
  await calendar.popover.getByRole('link', { name: 'Lancer' }).click()

  const activity = new FinanceReviewActivityPage(page)
  await activity.expectReady()
  const first = await activity.currentLabel()
  expect(labels).toContain(first)
  const second = labels.find((label) => label !== first) as string

  // … qualify the first and quit
  await activity.qualifyWith('Loisirs')
  await expect(activity.transaction.getByRole('heading', { name: second })).toBeVisible()
  await activity.quit()
  await expect(page).toHaveURL(/#\/calendar/)

  // … then relaunch: Then the review resumes on the second
  await activity.open()
  await expect(activity.transaction.getByRole('heading', { name: second })).toBeVisible()

  // The API holds it, not the tab: the first line is recorded as handled.
  const filed = await waitForIndexed<{ '@id'?: string; id?: string; label?: string; category?: string | null }>(
    api,
    '/api/transactions?itemsPerPage=100',
    (line) => line.label === first && Boolean(line.category),
    { what: `The line ${first}, once qualified` },
  )
  const stored = await api.get(`/api/finance/review/progress?month=${month}`, { headers: { Accept: 'application/json' } })
  const where = (await stored.json()) as { step: string; handledTransactionIds: string[] }
  expect(where.step).toBe('review')
  expect(where.handledTransactionIds).toContain(String(filed['@id'] ?? filed.id).split('/').pop())
})

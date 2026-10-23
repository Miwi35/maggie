import { test, expect } from '../fixtures/index.js'
import { FinanceTimelinePage } from '../pages/FinanceTimelinePage.js'

/**
 * The 20-year frise (MAG-554, extends MAG-102 and the Planification journeys).
 *
 * Read-only for the owner. The expected figures come from the same endpoint the
 * screen reads (`GET /api/finance/timeline`), which `FinanceTimelineControllerTest`
 * pins on a fixed clock: the date N depends on the planning the other journeys
 * leave behind (see `finance-independence.spec.ts`), so what this journey checks
 * is that the screen puts every event of the answer on its month — the
 * Indépendance one on the N date — and states the hypotheses, whatever N is.
 */

interface Line {
  scenarioId: string | null
  name: string
  nDate: string | null
  events: { id: string; month: string; label: string; category: string }[]
}

interface TimelineBody {
  assumptions: string
  current: Line
  compared: Line | null
  difference: { months: number | null; sentence: string } | null
}

/** « 2041-12 » as the screen spells it: « décembre 2041 ». */
const spelled = (month: string): string => {
  const [year, number] = month.split('-').map(Number)

  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' }).format(new Date(year ?? 0, (number ?? 1) - 1, 1))
}

test('the frise puts Indépendance on the N date of the current scenario', async ({ page, api }) => {
  const response = await api.get('/api/finance/timeline')
  expect(response.ok(), await response.text()).toBe(true)
  const expected = (await response.json()) as TimelineBody

  const frise = new FinanceTimelinePage(page)
  await frise.open()

  await expect(frise.currentRow.getByTestId('timeline-name-current')).toHaveText(expected.current.name)
  await expect(frise.panel.getByTestId('timeline-assumptions')).toHaveText(expected.assumptions)

  if (expected.current.nDate === null) {
    // N out of reach is said, not drawn.
    await expect(frise.currentRow.getByTestId('timeline-n-date-current')).toContainText("N non atteinte d'ici 20 ans")
    await expect(frise.event('independence_reached')).toHaveCount(0)

    return
  }

  const reached = frise.event('independence_reached')
  await expect(reached).toBeVisible()
  await expect(reached).toHaveAttribute('data-month', expected.current.nDate)
  await expect(reached).toContainText('Indépendance financière')
  await expect(reached).toContainText(spelled(expected.current.nDate))
  await expect(frise.currentRow.getByTestId('timeline-n-date-current')).toContainText(spelled(expected.current.nDate))

  for (const event of expected.current.events) {
    await expect(frise.event(event.id)).toHaveAttribute('data-month', event.month)
  }
})

test('a category filter hides its events, and a compared scenario draws a second row', async ({ page, api }) => {
  const response = await api.get('/api/finance/timeline?compare=auto-rapid')
  expect(response.ok(), await response.text()).toBe(true)
  const expected = (await response.json()) as TimelineBody
  expect(expected.compared?.name).toBe('Rapide')

  const frise = new FinanceTimelinePage(page)
  await frise.open()
  await expect(frise.comparedRow).toHaveCount(0)

  await frise.selector.selectOption('auto-rapid')

  await expect(frise.comparedRow).toBeVisible()
  await expect(frise.comparedRow.getByTestId('timeline-name-compared')).toHaveText('Rapide')
  // Facts only: the API's sentence, word for word.
  await expect(frise.panel.getByTestId('timeline-difference')).toHaveText(expected.difference?.sentence ?? '')
  if (expected.compared?.nDate) {
    await expect(frise.comparedEvent('independence_reached')).toHaveAttribute('data-month', expected.compared.nDate)
  }

  const category = expected.current.events.find((event) => event.category === 'indépendance')
  if (category) {
    await frise.filter('Indépendance').click()
    await expect(frise.event(category.id)).toHaveCount(0)
    await expect(frise.comparedEvent('independence_reached')).toHaveCount(0)

    await frise.filter('Indépendance').click()
    await expect(frise.event(category.id)).toBeVisible()
  }

  await frise.selector.selectOption('')
  await expect(frise.comparedRow).toHaveCount(0)
})

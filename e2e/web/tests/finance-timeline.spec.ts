import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { FinanceTimelinePage } from '../pages/FinanceTimelinePage.js'

/**
 * The 20-year frise (MAG-554, extends MAG-102 and the Planification journeys).
 *
 * The seed alone does not promise a date N within 20 years (it depends on the
 * planning the other journeys leave), so each journey first makes a scenario of
 * its own the current one, with a saving of 10 000 € a month: N is then always
 * within reach, and the journey asserts unconditionally. The scenario is let go
 * and deleted at the end. The expected figures come from the endpoint the
 * screen reads (`GET /api/finance/timeline`), which `FinanceTimelineControllerTest`
 * pins on a fixed clock: the journey checks that the screen puts every event of
 * the answer on its month — Indépendance on the N date — and states the hypotheses.
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

/** Makes a scenario that reaches N the current one; the returned function lets it go. */
async function followAScenarioThatReachesN(api: APIRequestContext): Promise<() => Promise<void>> {
  const created = await api.post('/api/finance/scenarios', { data: { name: 'Frise e2e', monthlySaving: 1_000_000 } })
  expect(created.status(), await created.text()).toBe(201)
  const id = ((await created.json()) as { scenario: { id: string } }).scenario.id
  const followed = await api.post(`/api/finance/scenarios/${id}/make-current`)
  expect(followed.ok(), await followed.text()).toBe(true)

  return async () => {
    await api.delete('/api/finance/current-scenario')
    await api.delete(`/api/finance/scenarios/${id}`)
  }
}

/** « 2041-12 » as the screen spells it: « décembre 2041 ». */
const spelled = (month: string): string => {
  const [year, number] = month.split('-').map(Number)

  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric' }).format(new Date(year ?? 0, (number ?? 1) - 1, 1))
}

test('the frise puts Indépendance on the N date of the current scenario', async ({ page, api }) => {
  const letGo = await followAScenarioThatReachesN(api)
  try {
    const response = await api.get('/api/finance/timeline')
    expect(response.ok(), await response.text()).toBe(true)
    const expected = (await response.json()) as TimelineBody
    const nDate = expected.current.nDate
    expect(nDate, 'a saving of 10 000 € a month must reach N within 20 years').not.toBeNull()
    expect(expected.current.name).toBe('Frise e2e')

    const frise = new FinanceTimelinePage(page)
    await frise.open()

    await expect(frise.currentRow.getByTestId('timeline-name-current')).toHaveText('Frise e2e')
    await expect(frise.panel.getByTestId('timeline-assumptions')).toHaveText(expected.assumptions)

    const reached = frise.event('independence_reached')
    await expect(reached).toBeVisible()
    await expect(reached).toHaveAttribute('data-month', nDate ?? '')
    await expect(reached).toContainText('Indépendance financière')
    await expect(reached).toContainText(spelled(nDate ?? ''))
    await expect(frise.currentRow.getByTestId('timeline-n-date-current')).toContainText(spelled(nDate ?? ''))

    for (const event of expected.current.events) {
      await expect(frise.event(event.id)).toHaveAttribute('data-month', event.month)
    }
  } finally {
    await letGo()
  }
})

test('a category filter hides its events, and a compared scenario draws a second row', async ({ page, api }) => {
  const letGo = await followAScenarioThatReachesN(api)
  try {
    // « Rapide » is `auto-rapid` until a journey stores it, then a ULID: the
    // select's options carry whichever id the list gives.
    const listed = await api.get('/api/finance/scenarios')
    expect(listed.ok(), await listed.text()).toBe(true)
    const rapid = ((await listed.json()) as { scenarios: { id: string; name: string; kind: string }[] }).scenarios
      .find((scenario) => scenario.kind === 'auto' && scenario.name === 'Rapide')
    expect(rapid, 'the automatic « Rapide » scenario is always offered').toBeDefined()
    const rapidId = rapid?.id ?? ''

    const response = await api.get(`/api/finance/timeline?compare=${encodeURIComponent(rapidId)}`)
    expect(response.ok(), await response.text()).toBe(true)
    const expected = (await response.json()) as TimelineBody
    expect(expected.compared?.name).toBe('Rapide')
    const independence = expected.current.events.find((event) => event.category === 'indépendance')
    expect(independence, 'the followed scenario reaches N: its Indépendance events are drawn').toBeDefined()

    const frise = new FinanceTimelinePage(page)
    await frise.open()
    await expect(frise.comparedRow).toHaveCount(0)

    await frise.selector.selectOption(rapidId)

    await expect(frise.comparedRow).toBeVisible()
    await expect(frise.comparedRow.getByTestId('timeline-name-compared')).toHaveText('Rapide')
    // Facts only: the API's sentence, word for word.
    await expect(frise.panel.getByTestId('timeline-difference')).toHaveText(expected.difference?.sentence ?? '')

    await frise.filter('Indépendance').click()
    await expect(frise.event(independence?.id ?? '')).toHaveCount(0)
    await expect(frise.comparedEvent('independence_reached')).toHaveCount(0)

    await frise.filter('Indépendance').click()
    await expect(frise.event(independence?.id ?? '')).toBeVisible()

    await frise.selector.selectOption('')
    await expect(frise.comparedRow).toHaveCount(0)
  } finally {
    await letGo()
  }
})

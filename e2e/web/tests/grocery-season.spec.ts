import { test, expect, e2eNow } from '../fixtures/index.js'
import type { APIRequestContext } from '@playwright/test'
import { assistantText, calledTools, toolResults } from '../helpers/agui.js'
import { getCollection, waitForIndexed } from '../helpers/api.js'
import { callMcpTool } from '../helpers/mcp.js'
import { ChatPanel } from '../pages/ChatPanel.js'
import { GroceryListPage } from '../pages/GroceryListPage.js'

/**
 * La saisonnalité des fruits et légumes (MAG-494, extends MAG-101 and MAG-99).
 *
 * A potiron and tomatoes go on the list by name, the way the owner types them:
 * the products are created on the fly and their season months come from the
 * calendar the API ships. The list then says « de saison » or « hors saison »
 * for the month the stack is pinned to — November in CI (`e2e/clock.sh ci`),
 * where the potiron is in season and the tomatoes are not, as in the October of
 * the ticket. The expectation is worked out from that month rather than written
 * down, so moving the CI instant does not make the journey lie.
 *
 * Then Maggie is asked what is in season: the fake model calls
 * `get_seasonal_produce`, which the Mind panel shows, and the data the tool
 * serves for that month are read through MCP — the fake does not read tool
 * results, so its sentence proves nothing about them.
 *
 * Its own account (`e2e-season`): the list and the chat history it writes to
 * are nobody else's, and the names below can stay constants.
 */

test.describe.configure({ mode: 'serial', retries: 0 })

const POTIRON = 'Potiron'
const TOMATOES = 'Tomates'

// What `api/modules/grocery/resources/seasonality.json` says of them.
const POTIRON_MONTHS = [1, 9, 10, 11, 12]
const TOMATO_MONTHS = [6, 7, 8, 9]

/** The month of the pinned instant, in Paris — the user's timezone, so the one the API reads. */
function parisMonth(): number {
  return Number(new Intl.DateTimeFormat('en', { timeZone: 'Europe/Paris', month: 'numeric' }).format(e2eNow()))
}

const badgeFor = (months: number[]) => (months.includes(parisMonth()) ? 'de saison' : 'hors saison')

interface Line {
  label: string
  inSeason?: boolean | null
  product?: { name: string; seasonMonths?: number[] | null } | null
}

interface StoredList {
  items: Line[]
}

async function items(api: APIRequestContext): Promise<Line[]> {
  const [list] = await getCollection<StoredList>(api, '/api/grocery_lists')

  return list?.items ?? []
}

test.beforeEach(async ({ seasonUser }) => {
  const { api } = seasonUser

  const labels = (await items(api)).map((line) => line.label)
  for (const label of [POTIRON, TOMATOES]) {
    if (labels.includes(label)) continue

    const added = await api.post('/api/grocery/add-item', { data: { label } })
    expect(added.status(), `the API refused « ${label} »: ${await added.text()}`).toBeLessThan(300)
  }

  await waitForIndexed<StoredList>(
    api,
    '/api/grocery_lists',
    (list) => [POTIRON, TOMATOES].every((label) => list.items.some((line) => label === line.label)),
    { what: 'The list holding the potiron and the tomatoes' },
  )
})

test('the potiron and the tomatoes get their season from the calendar, and the list shows it', async ({ seasonUser }) => {
  const { api, page } = seasonUser

  // Given a potiron and tomatoes typed by name: their season was filled in when they were created.
  const lines = await items(api)
  expect(lines.find((line) => POTIRON === line.label)?.product?.seasonMonths).toEqual(POTIRON_MONTHS)
  expect(lines.find((line) => TOMATOES === line.label)?.product?.seasonMonths).toEqual(TOMATO_MONTHS)

  // When the list is opened…
  const grocery = new GroceryListPage(page)
  await grocery.open()

  // …then each line carries the badge of the pinned month.
  await expect(grocery.line(POTIRON).getByTestId('season-badge')).toHaveText(badgeFor(POTIRON_MONTHS))
  await expect(grocery.line(TOMATOES).getByTestId('season-badge')).toHaveText(badgeFor(TOMATO_MONTHS))
})

test('« qu\'est-ce qui est de saison ? » calls get_seasonal_produce, which serves the potiron', async ({ seasonUser }) => {
  const { api, page } = seasonUser

  const grocery = new GroceryListPage(page)
  await grocery.open()

  const chat = new ChatPanel(page)
  const events = await chat.send("Qu'est-ce qui est de saison ?")

  expect(calledTools(events)).toContain('get_seasonal_produce')
  expect(toolResults(events)).toEqual(
    expect.arrayContaining([expect.objectContaining({ toolName: 'get_seasonal_produce', status: 'success' })]),
  )
  expect(assistantText(events)).toContain('potiron')

  await chat.openMind()
  await expect(chat.toolCall('get_seasonal_produce')).toHaveAttribute('data-status', 'success')
  await chat.openChat()

  // What the tool serves for the pinned month: the calendar's produce, and the user's own potiron when in season.
  const served = (await callMcpTool(api, 'get_seasonal_produce', {})) as {
    month: number
    produce: Array<{ name: string }>
    userProducts: Array<{ name: string }>
  }
  expect(served.month).toBe(parisMonth())
  const inSeason = POTIRON_MONTHS.includes(parisMonth())
  expect(served.produce.map((entry) => entry.name).includes(POTIRON)).toBe(inSeason)
  expect(served.userProducts.map((product) => product.name).includes(POTIRON)).toBe(inSeason)
  expect(served.userProducts.map((product) => product.name).includes(TOMATOES)).toBe(TOMATO_MONTHS.includes(parisMonth()))
})

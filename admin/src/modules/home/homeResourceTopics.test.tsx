import { render } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { AgendaCard } from './cards/AgendaCard'
import { TasksCard } from './cards/TasksCard'
import { GroceryHomeCard } from '../grocery/GroceryHomeCard'
import { CuisineHomeCard } from '../cookbook/CuisineHomeCard'
import { FinanceHomeCard } from '../finance/FinanceHomeCard'
import { HOME_RESOURCE_TOPICS } from './homeResourceTopics'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onmessage: ((event: MessageEvent) => void) | null = null
  constructor(public url: string) {
    FakeEventSource.instances.push(this)
  }
  close() {}
}

beforeEach(() => {
  FakeEventSource.instances = []
  localStorage.setItem('user', JSON.stringify({ id: 'user-1' }))
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
})

afterEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
})

test('the cards on business resources share one Mercure stream, so the page keeps connections for its requests', () => {
  render(
    <MemoryRouter>
      <ThemeProvider theme={veilleuseLightTheme}>
        <TasksCard day="today" />
        <AgendaCard day="today" />
        <GroceryHomeCard />
        <CuisineHomeCard day="today" />
        <FinanceHomeCard />
      </ThemeProvider>
    </MemoryRouter>,
  )

  expect(FakeEventSource.instances).toHaveLength(1)
})

test('the Courses and Cuisine cards listen to every resource behind them', () => {
  expect(HOME_RESOURCE_TOPICS).toEqual(
    expect.arrayContaining([
      '/api/grocery_lists/{id}',
      '/api/stores/{id}',
      '/api/products/{id}',
      '/api/ingredients/{id}',
      '/api/aisles/{id}',
      '/api/meals/{id}',
      '/api/recipes/{id}',
    ]),
  )
})

test('the Finance card listens to the resources the dashboard is made of', () => {
  expect(HOME_RESOURCE_TOPICS).toEqual(
    expect.arrayContaining([
      '/api/transactions/{id}',
      '/api/accounts/{id}',
      '/api/envelopes/{id}',
      '/api/recurring_operations/{id}',
      '/api/finance/projects/{id}',
      '/api/finance/scenarios/{id}',
    ]),
  )
})

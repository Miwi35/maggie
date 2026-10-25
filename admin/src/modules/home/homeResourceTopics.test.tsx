import { render } from '@testing-library/react'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { AgendaCard } from './cards/AgendaCard'
import { TasksCard } from './cards/TasksCard'
import { GroceryHomeCard } from '../grocery/GroceryHomeCard'
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
      </ThemeProvider>
    </MemoryRouter>,
  )

  expect(FakeEventSource.instances).toHaveLength(1)
})

test('the Courses card listens to every resource behind it', () => {
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

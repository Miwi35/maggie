import { render, waitFor } from '@testing-library/react'
import { AdminContext, Datagrid, ResourceContextProvider, TextField, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { LiveList } from './LiveList'

const subscribers = vi.hoisted(() => ({ onMessage: null as ((data?: string) => void) | null }))

vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], onMessage: (data?: string) => void) => {
    subscribers.onMessage = onMessage
  },
}))

const rows = ['A', 'B', 'C'].map((name) => ({ id: `/api/things/${name}`, name }))

const emit = (name: string) =>
  subscribers.onMessage?.(JSON.stringify({ '@id': `/api/things/${name}`, name: `${name}2` }))

describe('LiveList — events in quick succession', () => {
  beforeEach(() => {
    // No `act`: the race lives between a render's commit and its passive effects.
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = false
  })

  afterEach(() => {
    ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  })

  test('every event of a burst is applied, each leaving as the previous one is drawn', async () => {
    const dataProvider = testDataProvider({
      getList: (() => Promise.resolve({ data: rows, total: rows.length })) as unknown as DataProvider['getList'],
    })
    const { container } = render(
      <MemoryRouter>
        <AdminContext dataProvider={dataProvider}>
          <ResourceContextProvider value="things">
            <LiveList
              live={{ topics: ['/api/things/{id}'], required: ['name'] }}
              resource="things"
              actions={false}
              disableSyncWithLocation
            >
              <Datagrid bulkActionButtons={false}>
                <TextField source="name" />
              </Datagrid>
            </LiveList>
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )
    await waitFor(() => expect(container.textContent).toContain('C'))

    // A DOM mutation queues a microtask that runs right after the commit and
    // before that render's passive effects: where the next event used to land.
    const after = [
      ['A2', 'B'],
      ['B2', 'C'],
    ]
    const observer = new MutationObserver(() => {
      const [seen, next] = after[0] ?? []
      if (seen && container.textContent?.includes(seen)) {
        after.shift()
        emit(next)
      }
    })
    observer.observe(container, { childList: true, subtree: true, characterData: true })

    emit('A')

    await waitFor(() => {
      expect(container.textContent).toContain('A2')
      expect(container.textContent).toContain('B2')
      expect(container.textContent).toContain('C2')
    })
    observer.disconnect()
  })
})

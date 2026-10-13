import { act, render, screen, waitFor } from '@testing-library/react'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, test, expect, vi } from 'vitest'
import type { ReactElement } from 'react'

/**
 * Stand-in for `hooks/useMercure` in a screen test:
 * `vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)`.
 */
const handlers = new Map<string, (data?: string) => void>()

export const mercureModule = {
  useMercure: (topics: string[], onMessage: (data?: string) => void) => {
    for (const topic of topics) handlers.set(topic, onMessage)
  },
}

/** What the API publishes on `topic`: the payload leaves with the write, before the index holds it. */
export const emitMercure = (topic: string, payload: Record<string, unknown>) =>
  act(() => {
    const handler = handlers.get(topic)
    if (!handler) throw new Error(`Nothing is subscribed to ${topic}: ${[...handlers.keys()].join(', ') || 'no topic'}`)
    handler(JSON.stringify(payload))
  })

/**
 * Renders a list screen over a read that never learns of the change — the index
 * the event outruns — and counts the reads, so a test can say the event was
 * applied rather than read back.
 */
export function renderLiveList(
  screen: ReactElement,
  resource: string,
  listed: unknown[],
  route?: { path: string; entry: string },
) {
  const getList = vi.fn(() => Promise.resolve({ data: listed, total: listed.length }))
  const dataProvider = testDataProvider({
    getList: getList as unknown as DataProvider['getList'],
    getOne: (() => Promise.resolve({ data: { id: 'stub' } })) as unknown as DataProvider['getOne'],
    getMany: (() => Promise.resolve({ data: [] })) as unknown as DataProvider['getMany'],
    getManyReference: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getManyReference'],
  })
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))

  render(
    <MemoryRouter initialEntries={route ? [route.entry] : undefined}>
      <AdminContext dataProvider={dataProvider}>
        <ResourceContextProvider value={resource}>
          {route ? (
            <Routes>
              <Route path={route.path} element={screen} />
            </Routes>
          ) : (
            screen
          )}
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

  return { getList }
}

export interface LiveListSpec {
  name: string
  screen: () => ReactElement
  resource: string
  topic: string
  /** Where a screen reads its params from, when it is routed (`/accounts/:id/transactions`). */
  route?: { path: string; entry: string }
  /** The one row the read holds, as the Hydra provider gives it (`id` is the IRI). */
  listed: Record<string, unknown> & { id: string }
  /** The text that identifies `listed` on screen. */
  listedText: string
  /** A creation: the full payload, and what it shows. */
  created: { payload: Record<string, unknown>; text: string }
  /** An update: the differential payload, and what the row says after. */
  updated: { payload: Record<string, unknown>; text: string }
}

/**
 * The three events every indexed list has to show without waiting for the index
 * (MAG-347): a creation, an update and a deletion, each applied from the event,
 * with the collection read once, at load.
 */
export function liveListSuite(spec: LiveListSpec) {
  describe(`${spec.name} — live updates from Mercure`, () => {
    afterEach(() => vi.unstubAllGlobals())

    test('a created row appears while the read still lacks it', async () => {
      const { getList } = renderLiveList(spec.screen(), spec.resource, [spec.listed], spec.route)
      await screen.findByText(spec.listedText)

      await emitMercure(spec.topic, {
        '@id': spec.listed.id.replace(/[^/]+$/, '01NEWROW'),
        ...spec.created.payload,
      })

      expect(screen.getByText(spec.created.text)).toBeInTheDocument()
      expect(screen.getByText(spec.listedText)).toBeInTheDocument()
      expect(getList).toHaveBeenCalledTimes(1)
    })

    test('an updated row changes in place', async () => {
      const { getList } = renderLiveList(spec.screen(), spec.resource, [spec.listed], spec.route)
      await screen.findByText(spec.listedText)

      await emitMercure(spec.topic, { '@id': spec.listed.id, ...spec.updated.payload })

      expect(screen.getByText(spec.updated.text)).toBeInTheDocument()
      expect(screen.queryByText(spec.listedText)).not.toBeInTheDocument()
      expect(getList).toHaveBeenCalledTimes(1)
    })

    test('a deleted row disappears', async () => {
      const { getList } = renderLiveList(spec.screen(), spec.resource, [spec.listed], spec.route)
      await screen.findByText(spec.listedText)

      await emitMercure(spec.topic, { '@id': spec.listed.id, deleted: true })

      expect(screen.queryByText(spec.listedText)).not.toBeInTheDocument()
      expect(getList).toHaveBeenCalledTimes(1)
    })

    test('a row created on an empty list replaces the empty page', async () => {
      const { getList } = renderLiveList(spec.screen(), spec.resource, [], spec.route)
      await waitFor(() => expect(getList).toHaveBeenCalled())
      await act(async () => {
        await getList.mock.results[0].value
      })

      await emitMercure(spec.topic, {
        '@id': spec.listed.id.replace(/[^/]+$/, '01NEWROW'),
        ...spec.created.payload,
      })

      expect(await screen.findByText(spec.created.text)).toBeInTheDocument()
      expect(getList).toHaveBeenCalledTimes(1)
    })
  })
}

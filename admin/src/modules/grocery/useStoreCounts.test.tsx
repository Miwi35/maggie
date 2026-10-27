import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useStoreCounts } from './useStoreCounts'

const subscribed: Record<string, () => void> = {}
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[], onMessage: () => void) => {
    for (const topic of topics) subscribed[topic] = onMessage
  },
}))

let aisleMembers: { store: string }[]
let products: Record<string, number>

const json = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

describe('useStoreCounts', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt')
    aisleMembers = [{ store: '/api/stores/a' }, { store: '/api/stores/a' }, { store: '/api/stores/b' }]
    products = { a: 4, b: 0 }
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = new URL(String(input), 'http://localhost')
        if (url.pathname.endsWith('/aisles')) {
          const own = aisleMembers.filter((m) => m.store.endsWith(`/${url.searchParams.get('store')}`))
          return json({ totalItems: own.length, member: own.slice(0, 1) })
        }
        return json({ totalItems: products[url.searchParams.get('store') ?? ''] ?? 0, member: [] })
      }),
    )
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('counts the aisles and the products of each store, by bare id', async () => {
    const { result } = renderHook(() => useStoreCounts(['/api/stores/a', '/api/stores/b']))

    await waitFor(() =>
      expect(result.current).toEqual({ a: { aisles: 2, products: 4 }, b: { aisles: 1, products: 0 } }),
    )
  })

  test('an aisle added elsewhere is counted from its Mercure update', async () => {
    const { result } = renderHook(() => useStoreCounts(['a']))
    await waitFor(() => expect(result.current.a?.aisles).toBe(2))

    aisleMembers.push({ store: '/api/stores/a' })
    await act(async () => subscribed['/api/aisles/{id}']())

    await waitFor(() => expect(result.current.a?.aisles).toBe(3))
  })

  test('a product event is read twice: at once, then once the index has caught up', async () => {
    const { result } = renderHook(() => useStoreCounts(['a']))
    await waitFor(() => expect(result.current.a?.products).toBe(4))
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })

    // The index does not hold the new product yet when the event arrives.
    await act(async () => subscribed['/api/products/{id}']())
    expect(result.current.a?.products).toBe(4)

    products.a = 5
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })

    expect(result.current.a?.products).toBe(5)
  })

  test('a store whose counters cannot be read does not blank the others', async () => {
    const ok = (globalThis.fetch as ReturnType<typeof vi.fn>).getMockImplementation()!
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
        String(input).includes('store=b')
          ? Promise.resolve({ ok: false, status: 500, json: () => Promise.resolve({}) } as Response)
          : ok(input, init),
      ),
    )

    const { result } = renderHook(() => useStoreCounts(['a', 'b']))

    await waitFor(() => expect(result.current).toEqual({ a: { aisles: 2, products: 4 } }))
  })

  test('a slow read of fewer stores does not wipe the counters of a store added since', async () => {
    const answers: (() => void)[] = []
    const ok = (globalThis.fetch as ReturnType<typeof vi.fn>).getMockImplementation()!
    vi.stubGlobal(
      'fetch',
      vi.fn(
        (input: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((resolve) => answers.push(() => resolve(ok(input, init) as Promise<Response> as never))),
      ),
    )

    const { result, rerender } = renderHook(({ ids }) => useStoreCounts(ids), { initialProps: { ids: ['a'] } })
    rerender({ ids: ['a', 'b'] })
    const first = answers.splice(0, 2) // the read of ['a'], still pending
    await act(async () => {
      answers.splice(0).forEach((answer) => answer()) // the read of ['a', 'b'] answers first
    })
    await waitFor(() => expect(result.current.b).toEqual({ aisles: 1, products: 0 }))

    await act(async () => first.forEach((answer) => answer()))

    expect(result.current.b).toEqual({ aisles: 1, products: 0 })
  })
})

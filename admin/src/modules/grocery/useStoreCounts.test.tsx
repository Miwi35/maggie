import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useStoreCounts } from './useStoreCounts'

const subscribed: Record<string, (data?: string) => void> = {}
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[], onMessage: (data?: string) => void) => {
    for (const topic of topics) subscribed[topic] = onMessage
  },
}))

let aisleMembers: { store: string }[]
let products: Record<string, number>

const json = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

const BURST_MS = 300

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
    await act(async () => {
      await vi.advanceTimersByTimeAsync(BURST_MS)
    })
    expect(result.current.a?.products).toBe(4)

    products.a = 5
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000)
    })

    expect(result.current.a?.products).toBe(5)
  })

  const reads = (store: string, what: 'aisles' | 'products') =>
    (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.filter(
      ([input]) => String(input).includes(`/${what}?`) && String(input).includes(`store=${store}`),
    ).length

  test('a burst of Mercure messages naming one store is one read of that store only (MAG-676)', async () => {
    const { result } = renderHook(() => useStoreCounts(['a', 'b']))
    await waitFor(() => expect(result.current.b).toBeDefined())
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const before = { aisles: reads('a', 'aisles'), products: reads('a', 'products') }
    const other = { aisles: reads('b', 'aisles'), products: reads('b', 'products') }

    for (let i = 1; i <= 10; i++) {
      await act(async () => subscribed['/api/aisles/{id}'](JSON.stringify({ position: i, storeId: 'a' })))
    }
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })

    expect(reads('a', 'aisles') - before.aisles).toBe(1)
    expect(reads('a', 'products') - before.products).toBe(1)
    expect(reads('b', 'aisles')).toBe(other.aisles)
    expect(reads('b', 'products')).toBe(other.products)
  })

  test('a burst of messages that name no store is one read per store, then one more once the index caught up (MAG-676)', async () => {
    const { result } = renderHook(() => useStoreCounts(['a', 'b']))
    await waitFor(() => expect(result.current.b).toBeDefined())
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const before = (store: string) => reads(store, 'aisles') + reads(store, 'products')
    const [a, b] = [before('a'), before('b')]

    for (let i = 1; i <= 10; i++) {
      await act(async () => subscribed['/api/aisles/{id}'](JSON.stringify({ position: i })))
    }
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000)
    })
    expect([before('a') - a, before('b') - b]).toEqual([2, 2])

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000)
    })
    expect([before('a') - a, before('b') - b]).toEqual([4, 4])
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

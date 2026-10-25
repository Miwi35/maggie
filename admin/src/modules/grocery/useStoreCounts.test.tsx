import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { useStoreCounts } from './useStoreCounts'

const subscribed: Record<string, () => void> = {}
vi.mock('../../hooks/useMercure', () => ({
  useMercure: (topics: string[], onMessage: () => void) => {
    subscribed[topics[0]] = onMessage
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
        if (url.pathname.endsWith('/aisles')) return json({ member: aisleMembers })
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
})

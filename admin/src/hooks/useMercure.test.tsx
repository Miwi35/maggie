import { renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useMercure } from './useMercure'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onmessage: ((event: { data: string }) => void) | null = null
  closed = false

  constructor(
    public url: string,
    public init?: EventSourceInit,
  ) {
    FakeEventSource.instances.push(this)
  }

  close() {
    this.closed = true
  }

  emit(data: string) {
    this.onmessage?.({ data })
  }
}

const TOPICS = ['/api/transactions/{id}']

describe('useMercure', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('scopes the topics to the user and opens the stream with credentials', () => {
    renderHook(() => useMercure(TOPICS, () => {}))

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(decodeURIComponent(FakeEventSource.instances[0].url)).toContain('/users/U1/api/transactions/:id')
    expect(FakeEventSource.instances[0].init).toEqual({ withCredentials: true })
  })

  it('opens one stream for the same topics asked by several components, and tells each of them', () => {
    const first = vi.fn()
    const second = vi.fn()
    renderHook(() => useMercure(TOPICS, first))
    renderHook(() => useMercure(TOPICS, second))

    expect(FakeEventSource.instances).toHaveLength(1)

    FakeEventSource.instances[0].emit('payload')

    expect(first).toHaveBeenCalledWith('payload')
    expect(second).toHaveBeenCalledWith('payload')
  })

  it('keeps the stream open until the last component leaves', () => {
    const first = renderHook(() => useMercure(TOPICS, () => {}))
    const second = renderHook(() => useMercure(TOPICS, () => {}))
    const [stream] = FakeEventSource.instances

    first.unmount()
    expect(stream.closed).toBe(false)

    second.unmount()
    expect(stream.closed).toBe(true)
  })

  it('opens a stream of its own for other topics, and a new one once all have left', () => {
    const transactions = renderHook(() => useMercure(TOPICS, () => {}))
    renderHook(() => useMercure(['/api/accounts/{id}'], () => {}))

    expect(FakeEventSource.instances).toHaveLength(2)

    transactions.unmount()
    renderHook(() => useMercure(TOPICS, () => {}))

    expect(FakeEventSource.instances).toHaveLength(3)
  })

  it('subscribes to nothing without a signed-in user', () => {
    localStorage.clear()
    renderHook(() => useMercure(TOPICS, () => {}))

    expect(FakeEventSource.instances).toHaveLength(0)
  })
})

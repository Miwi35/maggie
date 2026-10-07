import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { subscribeAgentFeed } from './agentFeed'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onmessage: ((event: { data: string }) => void) | null = null
  close = vi.fn()
  constructor(
    public url: string,
    public init?: EventSourceInit,
  ) {
    FakeEventSource.instances.push(this)
  }
}

describe('subscribeAgentFeed', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('carries the contexts, proactions, approvals and notifications topics of the user, with credentials', () => {
    const stop = subscribeAgentFeed('u1', vi.fn())

    const [source] = FakeEventSource.instances
    const params = new URL(source.url).searchParams
    expect(params.getAll('match')).toEqual(['/contexts/u1', '/proactions/u1', '/approvals/u1'])
    expect(params.getAll('match_urlpattern')).toEqual(['/users/u1/api/notifications/:id'])
    expect(source.init).toEqual({ withCredentials: true })
    stop()
  })

  test('shares one connection between listeners and hands every message to each', () => {
    const first = vi.fn()
    const second = vi.fn()
    const stopFirst = subscribeAgentFeed('u1', first)
    const stopSecond = subscribeAgentFeed('u1', second)

    expect(FakeEventSource.instances).toHaveLength(1)
    FakeEventSource.instances[0].onmessage?.({ data: '{"id":"x"}' })
    expect(first).toHaveBeenCalledWith('{"id":"x"}')
    expect(second).toHaveBeenCalledWith('{"id":"x"}')

    stopFirst()
    expect(FakeEventSource.instances[0].close).not.toHaveBeenCalled()
    stopSecond()
    expect(FakeEventSource.instances[0].close).toHaveBeenCalledTimes(1)
  })

  test('opens a fresh connection once the last listener has left', () => {
    subscribeAgentFeed('u1', vi.fn())()
    subscribeAgentFeed('u1', vi.fn())()

    expect(FakeEventSource.instances).toHaveLength(2)
  })
})

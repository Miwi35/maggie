import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { emitMercure } from '../../../test/mercure'
import { fakeTimersApi } from './fakeTimersApi'
import type { FakeTimersApi } from './fakeTimersApi'
import { CHANGE_ERROR, START_ERROR, useCookingTimers } from './useCookingTimers'

vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

const TOPIC = '/api/cooking/timers/{id}'
const WATER = { label: 'Eau', recipe: '/api/recipes/REF', stepPosition: 1, durationSeconds: 600 }
const PASTA = { label: 'Pâtes', recipe: '/api/recipes/REF', stepPosition: 3, durationSeconds: 720 }

let api: FakeTimersApi

const render = async () => {
  const hook = renderHook(() => useCookingTimers())
  await waitFor(() => expect(api.requests.some((request) => request.method === 'GET')).toBe(true))
  await act(async () => {})

  return hook
}

const posts = () => api.requests.filter((request) => request.method === 'POST')
const patches = () => api.requests.filter((request) => request.method === 'PATCH').map((request) => request.body)

describe('useCookingTimers', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    api = fakeTimersApi()
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => api.handle(String(input), init) ?? Promise.resolve(new Response('', { status: 404 }))))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('a started timer counts down locally, every second, from what the API answered', async () => {
    const { result } = await render()

    await act(async () => {
      expect(await result.current.start(WATER)).toBe(true)
    })
    expect(result.current.timers).toMatchObject([{ id: 'T1', label: 'Eau', status: 'running', remaining: 600 }])

    await act(async () => {
      vi.advanceTimersByTime(3000)
    })
    expect(result.current.timers[0].remaining).toBeLessThanOrEqual(597)
    expect(result.current.timers[0].remaining).toBeGreaterThanOrEqual(596)
    expect(posts()).toHaveLength(1)
  })

  test('pause keeps the rest, ±1 min moves it, resume starts again', async () => {
    const { result } = await render()
    await act(async () => {
      await result.current.start(WATER)
    })

    await act(async () => {
      await result.current.change('T1', 'pause')
    })
    expect(result.current.timers[0]).toMatchObject({ status: 'paused', remaining: 600 })

    await act(async () => {
      await result.current.change('T1', 'add', 60)
    })
    expect(result.current.timers[0].remaining).toBe(660)
    await act(async () => {
      await result.current.change('T1', 'add', -60)
    })
    expect(result.current.timers[0].remaining).toBe(600)

    await act(async () => {
      await result.current.change('T1', 'resume')
    })
    expect(result.current.timers[0].status).toBe('running')
    expect(patches()).toEqual([{ action: 'pause' }, { action: 'add', seconds: 60 }, { action: 'add', seconds: -60 }, { action: 'resume' }])
  })

  test('Mercure says it rang: done; « OK » dismisses it', async () => {
    const { result } = await render()
    await act(async () => {
      await result.current.start(WATER)
    })

    await emitMercure(TOPIC, api.ring('T1'))
    expect(result.current.timers[0]).toMatchObject({ status: 'done', remaining: 0 })

    await act(async () => {
      await result.current.change('T1', 'dismiss')
    })
    expect(result.current.timers[0].status).toBe('dismissed')
  })

  test('the count reached 0 and Mercure is late: the API is asked every 2 s until it rang', async () => {
    const { result } = await render()
    await act(async () => {
      await result.current.start({ ...WATER, durationSeconds: 1 })
    })
    const reads = () => api.requests.filter((request) => request.method === 'GET').length
    const before = reads()

    await act(async () => {
      vi.advanceTimersByTime(1100)
    })
    await waitFor(() => expect(reads()).toBeGreaterThan(before))
    expect(result.current.timers[0].status).toBe('running')

    api.ring('T1')
    await act(async () => {
      vi.advanceTimersByTime(2000)
    })
    await waitFor(() => expect(result.current.timers[0].status).toBe('done'))

    const settled = reads()
    await act(async () => {
      vi.advanceTimersByTime(4000)
    })
    expect(reads()).toBe(settled)
  })

  test('a reload reads the timers back from GET: same timers, same rest, nothing posted', async () => {
    const first = await render()
    await act(async () => {
      await first.result.current.start(WATER)
      await first.result.current.start(PASTA)
      await first.result.current.change('T2', 'pause')
    })
    first.unmount()
    api.requests.length = 0

    const { result } = await render()

    await waitFor(() => expect(result.current.timers.map((timer) => [timer.id, timer.status])).toEqual([['T1', 'running'], ['T2', 'paused']]))
    expect(result.current.timers[1].remaining).toBe(720)
    expect(posts()).toHaveLength(0)
  })

  test('a timer cancelled elsewhere leaves', async () => {
    const { result } = await render()
    await act(async () => {
      await result.current.start(WATER)
    })

    await emitMercure(TOPIC, { '@id': '/api/cooking/timers/T1', ...api.timer('T1'), status: 'cancelled' })

    expect(result.current.timers).toEqual([])
  })

  test('a refused change takes the timer as it stands, without a message', async () => {
    const { result } = await render()
    await act(async () => {
      await result.current.start(WATER)
    })

    await act(async () => {
      expect(await result.current.change('T1', 'dismiss')).toBe(false)
    })

    expect(result.current.timers[0].status).toBe('running')
    expect(result.current.error).toBeNull()
  })

  test('an unreachable API says so: the start and the change fail with a message', async () => {
    const { result } = await render()
    await act(async () => {
      await result.current.start(WATER)
    })
    api.fail(true)

    await act(async () => {
      expect(await result.current.start(PASTA)).toBe(false)
    })
    expect(result.current.error).toBe(START_ERROR)
    expect(result.current.timers).toHaveLength(1)

    await act(async () => {
      await result.current.change('T1', 'pause')
    })
    expect(result.current.error).toBe(CHANGE_ERROR)
  })

  test('cancelAll stops the running, paused and rung timers', async () => {
    const { result } = await render()
    await act(async () => {
      await result.current.start(WATER)
      await result.current.start(PASTA)
    })
    api.ring('T2')

    await act(async () => {
      await result.current.cancelAll()
    })

    expect(patches()).toEqual([{ action: 'cancel' }, { action: 'cancel' }])
    expect(api.all().map((timer) => timer.status)).toEqual(['cancelled', 'cancelled'])
    expect(result.current.timers).toEqual([])
  })
})

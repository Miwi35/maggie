import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { fakeBilanStatusApi } from './fakeBilanStatusApi'
import type { FakeBilanStatusApi } from './fakeBilanStatusApi'
import { createBilanWriter } from './bilanStatusApi'
import { useBilanActivity } from './useBilanActivity'

let message: () => void = () => {}
vi.mock('../../../hooks/useMercure', () => ({ useMercure: (_topics: string[], onMessage: () => void) => void (message = onMessage) }))

const TODAY = '2026-11-23'
let api: FakeBilanStatusApi

const stub = (handler?: (url: string, init?: RequestInit) => Promise<Response> | null) =>
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => handler?.(String(input), init) ?? api.handle(String(input), init) ?? Promise.resolve(new Response('', { status: 404 })))

beforeEach(() => {
  api = fakeBilanStatusApi()
  stub()
})

afterEach(() => vi.unstubAllGlobals())

describe('useBilanActivity', () => {
  test('opening without a bilan of today writes journee', async () => {
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    expect(result.current.session).toMatchObject({ day: TODAY, step: 'journee' })
    await waitFor(() => expect(api.writes()).toEqual([{ origin: { kind: 'day', id: TODAY }, state: { step: 'journee' } }]))
  })

  test('a bilan of today resumes at its step', async () => {
    api = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'sante', answers: { mood: 7 } } })
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    expect(result.current.session?.step).toBe('sante')
    expect(api.writes()).toEqual([])
  })

  test('a past day is not resumed', async () => {
    api = fakeBilanStatusApi({ origin: { kind: 'day', id: '2026-11-20' }, state: { step: 'sante' } })
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    expect(result.current.session).toMatchObject({ day: TODAY, step: 'journee' })
    await waitFor(() => expect(api.current()?.origin.id).toBe(TODAY))
  })

  test('moving keeps the answers and the instructions', async () => {
    const instructions = [{ id: '1', text: 'Acheter du pain', kind: 'task', targetId: 't1' }]
    api = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'journee', answers: { mood: 7 }, instructions } })
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    act(() => result.current.moveTo('consignes'))
    await waitFor(() => expect(api.writes()).toHaveLength(1))
    expect(api.writes()[0]).toEqual({ origin: { kind: 'day', id: TODAY }, state: { step: 'consignes', answers: { mood: 7 }, instructions } })
  })

  test('recording merges into the answers', async () => {
    api = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'sante', answers: { mood: 7 } } })
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    act(() => result.current.record({ fatigue: 4 }))
    await waitFor(() => expect(api.writes()).toHaveLength(1))
    expect(api.writes()[0]?.state).toEqual({ step: 'sante', answers: { mood: 7, fatigue: 4 } })
  })

  test('writes go one at a time and only the last waits', async () => {
    const release: (() => void)[] = []
    let inFlight = 0
    let peak = 0
    const bodies: unknown[] = []
    const writer = createBilanWriter(
      async (_origin, state) => {
        inFlight++
        peak = Math.max(peak, inFlight)
        bodies.push(state)
        await new Promise<void>((resolve) => release.push(resolve))
        inFlight--
      },
      async () => undefined,
    )
    const { result } = renderHook(() => useBilanActivity(TODAY, writer))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    await waitFor(() => expect(release).toHaveLength(1))
    act(() => result.current.moveTo('finance'))
    act(() => result.current.moveTo('sante'))
    act(() => result.current.moveTo('consignes'))
    expect(bodies).toHaveLength(1)
    release[0]()
    await waitFor(() => expect(release).toHaveLength(2))
    expect(bodies[1]).toEqual({ step: 'consignes' })
    release[1]()
    await writer.settled()
    expect(peak).toBe(1)
    expect(bodies).toHaveLength(2)
  })

  test('another device moving it is adopted', async () => {
    api = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'journee' } })
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    api.set({ origin: { kind: 'day', id: TODAY }, state: { step: 'consignes', instructions: [{ id: '1', text: 'Pain', kind: 'task' }] } })
    act(() => message())
    await waitFor(() => expect(result.current.session?.step).toBe('consignes'))
    expect(result.current.session?.state.instructions).toHaveLength(1)
  })

  test('a 404 over Mercure ends it', async () => {
    api = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'journee' } })
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    api.set(null)
    act(() => message())
    await waitFor(() => expect(result.current.load).toBe('closed'))
  })

  test('Bonne nuit deletes', async () => {
    api = fakeBilanStatusApi({ origin: { kind: 'day', id: TODAY }, state: { step: 'demain' } })
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('ready'))
    act(() => result.current.end())
    await waitFor(() => expect(api.deletes()).toHaveLength(1))
    expect(api.current()).toBeNull()
    expect(result.current.load).toBe('closed')
  })

  test('an unreachable API is an error that retry reads again', async () => {
    stub(() => Promise.resolve(new Response('', { status: 503 })))
    const { result } = renderHook(() => useBilanActivity(TODAY))
    await waitFor(() => expect(result.current.load).toBe('error'))
    stub()
    act(() => result.current.retry())
    await waitFor(() => expect(result.current.load).toBe('ready'))
  })
})

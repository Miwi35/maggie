import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { LATER_DELAY_MS, useMaggieInterruption } from './useMaggieInterruption'

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
  emit(payload: unknown) {
    act(() => this.onmessage?.({ data: JSON.stringify(payload) }))
  }
}

const proaction = (overrides: Record<string, unknown> = {}) => ({
  id: 'p1',
  status: 'completed',
  response: 'Ton colis est arrivé.',
  ...overrides,
})

const stream = () => FakeEventSource.instances[0]

function setup(chatOpen = false) {
  const onOpenChat = vi.fn()
  const hook = renderHook((props: { chatOpen: boolean }) => useMaggieInterruption({ ...props, onOpenChat }), {
    initialProps: { chatOpen },
  })
  return { ...hook, onOpenChat }
}

describe('useMaggieInterruption', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    FakeEventSource.instances = []
    vi.stubGlobal('EventSource', FakeEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('subscribes to the user proactions topic, with credentials', () => {
    setup()

    expect(decodeURIComponent(stream().url)).toContain('/proactions/u1')
    expect(stream().init).toEqual({ withCredentials: true })
  })

  test('does not subscribe without a signed-in user', () => {
    localStorage.clear()

    setup()

    expect(FakeEventSource.instances).toHaveLength(0)
  })

  test('closes the stream on unmount', () => {
    const { unmount } = setup()

    unmount()

    expect(stream().close).toHaveBeenCalled()
  })

  test('a completed proaction with an answer interrupts', () => {
    const { result } = setup()

    stream().emit(proaction())

    expect(result.current.current).toEqual({ id: 'p1', message: 'Ton colis est arrivé.' })
  })

  test.each([
    ['pending', { status: 'pending' }],
    ['running', { status: 'running' }],
    ['failed', { status: 'failed' }],
    ['empty', { response: '' }],
    ['blank', { response: '   ' }],
    ['missing', { response: null }],
  ])('a %s proaction stays quiet', (_label, overrides) => {
    const { result } = setup()

    stream().emit(proaction(overrides))

    expect(result.current.current).toBeNull()
  })

  test('ignores a malformed message', () => {
    const { result } = setup()

    act(() => stream().onmessage?.({ data: 'not json' }))

    expect(result.current.current).toBeNull()
  })

  test('the same proaction twice interrupts once', () => {
    const { result } = setup()

    stream().emit(proaction())
    act(() => result.current.dismiss(false))
    stream().emit(proaction())

    expect(result.current.current).toBeNull()
  })

  test('several arrivals queue up and show one at a time', () => {
    const { result } = setup()

    stream().emit(proaction({ id: 'a', response: 'Première' }))
    stream().emit(proaction({ id: 'b', response: 'Seconde' }))
    expect(result.current.current?.message).toBe('Première')

    act(() => result.current.dismiss(false))

    expect(result.current.current?.message).toBe('Seconde')
  })

  test('« Plus tard » brings it back after the delay', () => {
    const { result } = setup()
    stream().emit(proaction())

    act(() => result.current.dismiss(true))
    expect(result.current.current).toBeNull()

    act(() => vi.advanceTimersByTime(LATER_DELAY_MS - 1))
    expect(result.current.current).toBeNull()
    act(() => vi.advanceTimersByTime(1))
    expect(result.current.current?.message).toBe('Ton colis est arrivé.')
  })

  test('opening the chat from it closes it for good', () => {
    const { result, onOpenChat } = setup()
    stream().emit(proaction())

    act(() => result.current.openChat())

    expect(onOpenChat).toHaveBeenCalledTimes(1)
    expect(result.current.current).toBeNull()
    act(() => vi.advanceTimersByTime(LATER_DELAY_MS * 2))
    expect(result.current.current).toBeNull()
  })

  test('the chat opening elsewhere closes it and cancels the return', () => {
    const { result, rerender } = setup()
    stream().emit(proaction({ id: 'a' }))
    stream().emit(proaction({ id: 'b' }))
    act(() => result.current.dismiss(true))

    rerender({ chatOpen: true })

    expect(result.current.current).toBeNull()
    act(() => vi.advanceTimersByTime(LATER_DELAY_MS * 2))
    expect(result.current.current).toBeNull()
  })
})

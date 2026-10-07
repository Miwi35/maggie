import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { isSoundEnabled, playChime, setSoundEnabled } from './chime'

const created = vi.fn()

class FakeAudioContext {
  currentTime = 0
  destination = {}
  constructor() {
    created()
  }
  resume = () => Promise.resolve()
  close = () => Promise.resolve()
  createGain = () => ({
    gain: { setValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() },
    connect: (next: unknown) => next,
  })
  createOscillator = () => ({
    type: 'sine',
    frequency: { value: 0 },
    connect: (next: unknown) => next,
    start: vi.fn(),
    stop: vi.fn(),
  })
}

describe('chime', () => {
  beforeEach(() => {
    localStorage.clear()
    created.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    Reflect.deleteProperty(window, 'AudioContext')
  })

  test('sound is on until the user turns it off', () => {
    expect(isSoundEnabled()).toBe(true)
    setSoundEnabled(false)
    expect(localStorage.getItem('maggie.sound')).toBe('off')
    expect(isSoundEnabled()).toBe(false)
    setSoundEnabled(true)
    expect(isSoundEnabled()).toBe(true)
  })

  test('is silent where there is no AudioContext', () => {
    expect(() => playChime()).not.toThrow()
  })

  test('plays through Web Audio when enabled', () => {
    window.AudioContext = FakeAudioContext as unknown as typeof AudioContext

    playChime()

    expect(created).toHaveBeenCalledTimes(1)
  })

  test('stays silent when the sound is off', () => {
    window.AudioContext = FakeAudioContext as unknown as typeof AudioContext
    setSoundEnabled(false)

    playChime()

    expect(created).not.toHaveBeenCalled()
  })
})

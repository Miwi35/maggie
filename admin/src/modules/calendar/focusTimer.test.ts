import { describe, test, expect } from 'vitest'
import { FOCUS_MS, IDLE, formatClock, pauseTimer, resumeTimer, startTimer, viewTimer } from './focusTimer'

describe('focusTimer — ten minutes read from the clock (MAG-499)', () => {
  test('idle, it shows the whole ten minutes', () => {
    expect(viewTimer(IDLE, 5000)).toEqual({ status: 'idle', remainingMs: FOCUS_MS })
    expect(formatClock(FOCUS_MS)).toBe('10:00')
  })

  test('running, the time left follows the clock whatever the ticks', () => {
    const state = startTimer(1000)

    expect(viewTimer(state, 1000)).toEqual({ status: 'running', remainingMs: FOCUS_MS })
    expect(viewTimer(state, 1000 + 90_000)).toEqual({ status: 'running', remainingMs: FOCUS_MS - 90_000 })
  })

  test('paused, the time left no longer moves; resumed, it runs again from there', () => {
    const paused = pauseTimer(startTimer(0), 120_000)

    expect(paused).toEqual({ status: 'paused', remainingMs: FOCUS_MS - 120_000 })
    expect(viewTimer(paused, 999_999)).toEqual({ status: 'paused', remainingMs: FOCUS_MS - 120_000 })
    const resumed = resumeTimer(paused, 500_000)
    expect(viewTimer(resumed, 560_000)).toEqual({ status: 'running', remainingMs: FOCUS_MS - 180_000 })
  })

  test('it is finished at the deadline and never goes below zero', () => {
    const state = startTimer(0)

    expect(viewTimer(state, FOCUS_MS).status).toBe('finished')
    expect(viewTimer(state, FOCUS_MS * 3)).toEqual({ status: 'finished', remainingMs: 0 })
  })

  test('pause and resume leave the other states alone', () => {
    expect(pauseTimer(IDLE, 1)).toBe(IDLE)
    const running = startTimer(0)
    expect(resumeTimer(running, 1)).toBe(running)
  })

  test('the clock rounds up to the second', () => {
    expect(formatClock(599_001)).toBe('10:00')
    expect(formatClock(59_000)).toBe('00:59')
    expect(formatClock(1)).toBe('00:01')
    expect(formatClock(0)).toBe('00:00')
    expect(formatClock(-5)).toBe('00:00')
  })
})

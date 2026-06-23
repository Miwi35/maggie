import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, act } from '@testing-library/react'
import { LoadingPage } from './LoadingPage'

describe('LoadingPage', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  test('does not render the splash for brief loading states (before the delay)', () => {
    const { container } = render(<LoadingPage />)

    // Unmounted before the delay elapses (the flicker case): nothing is shown.
    act(() => {
      vi.advanceTimersByTime(300)
    })
    expect(container.querySelector('img')).toBeNull()
  })

  test('renders the splash once the loading state outlasts the delay', () => {
    const { container } = render(<LoadingPage />)

    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(container.querySelector('img[alt="Maggie"]')).not.toBeNull()
  })
})

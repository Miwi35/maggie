import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { urgencyColor } from '../../design/tokens'
import { UrgencyBadge } from './UrgencyBadge'

/** A task's urgency, computed by the API (MAG-406): an icon and a word, never the colour alone. */

/** `#4CAF50` as `rgb(76, 175, 80)` — what jsdom computes off the emotion rule. */
const asRgb = (hex: string): string => {
  const [r, g, b] = [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16))

  return `rgb(${r}, ${g}, ${b})`
}

describe('UrgencyBadge', () => {
  test.each([
    ['critical', 'critique'],
    ['high', 'haute'],
    ['normal', 'normale'],
    ['low', 'basse'],
  ])('draws %s as the word « %s » with an icon in its colour', (urgency, word) => {
    render(<UrgencyBadge urgency={urgency} />)

    const badge = screen.getByTestId('urgency-badge')
    expect(badge).toHaveTextContent(word)
    expect(badge).toHaveAttribute('title', `Urgence ${word}`)
    const icon = badge.querySelector('svg')
    expect(icon).not.toBeNull()
    expect(getComputedStyle(icon!).color).toBe(asRgb(urgencyColor(urgency)))
  })

  test.each([[undefined], [null], ['urgent']])('draws nothing for an urgency it does not know (%s)', (urgency) => {
    const { container } = render(<UrgencyBadge urgency={urgency} />)

    expect(container).toBeEmptyDOMElement()
  })
})

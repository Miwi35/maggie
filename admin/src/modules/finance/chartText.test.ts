import { describe, test, expect } from 'vitest'
import { placeLabels, textWidthPx, truncate } from './chartText'

describe('truncate', () => {
  test('keeps a short label whole', () => {
    expect(truncate('Loyer', 14)).toBe('Loyer')
  })

  test('cuts a long label with an ellipsis within the limit', () => {
    const cut = truncate('BOUYGUES TELECOM BOUYGUES TELECO', 14)

    expect(cut).toHaveLength(14)
    expect(cut.endsWith('…')).toBe(true)
  })
})

describe('placeLabels', () => {
  test('puts labels that touch on different rows', () => {
    const placed = placeLabels(
      [
        { centerPx: 100, widthPx: 80 },
        { centerPx: 130, widthPx: 80 },
        { centerPx: 400, widthPx: 80 },
      ],
      800,
      6,
      3,
    )

    expect(placed.map((label) => label.row)).toEqual([0, 1, 0])
  })

  test('keeps a label inside the plot', () => {
    const [left, right] = placeLabels(
      [
        { centerPx: 2, widthPx: 80 },
        { centerPx: 799, widthPx: 80 },
      ],
      800,
      6,
      3,
    )

    expect(left.xPx).toBe(40)
    expect(right.xPx).toBe(760)
  })

  test('never exceeds the rows it is given', () => {
    const slots = Array.from({ length: 6 }, () => ({ centerPx: 100, widthPx: 80 }))

    expect(Math.max(...placeLabels(slots, 800, 6, 3).map((label) => label.row))).toBe(2)
  })
})

describe('textWidthPx', () => {
  test('grows with the text', () => {
    expect(textWidthPx('abcdef')).toBeGreaterThan(textWidthPx('abc'))
  })
})

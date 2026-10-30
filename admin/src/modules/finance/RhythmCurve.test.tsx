import { afterEach, describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RhythmCurve } from './RhythmCurve'
import { rhythm } from './scoresFixture'
import { textWidthPx } from './chartText'

const VIEWBOX_WIDTH = 640

const renderedAt = (width: number) =>
  vi.spyOn(SVGElement.prototype, 'getBoundingClientRect').mockReturnValue({ width } as DOMRect)

const FOUR_DEBITS = [
  { date: '2026-10-05', label: 'Assurance 508,10', amountCents: 50810, recurringOperationId: 'a' },
  { date: '2026-10-05', label: 'BOUYGUES TELECOM BOUYGUES TELECO', amountCents: 2000, recurringOperationId: 'b' },
  { date: '2026-10-06', label: 'Taxe foncière', amountCents: 97700, recurringOperationId: 'c' },
  { date: '2026-10-06', label: 'Claude', amountCents: 18000, recurringOperationId: 'd' },
]

afterEach(() => vi.restoreAllMocks())

describe('RhythmCurve', () => {
  test('marks each direct debit on its day', () => {
    render(<RhythmCurve rhythm={rhythm()} />)

    const marker = screen.getByTestId('debit-marker')
    expect(marker.dataset.label).toBe('Loyer')
    expect(marker.dataset.date).toBe('2026-10-05')
    expect(marker).toHaveTextContent('Loyer 5')
    expect(screen.getByText(/Loyer — le 5 —/)).toBeInTheDocument()
  })

  test('the spent line stops at today, the days to come have no point', () => {
    render(<RhythmCurve rhythm={rhythm()} />)

    const commands = screen.getByTestId('spent-line').getAttribute('d')?.match(/[ML]/g)
    expect(commands).toHaveLength(3)
  })

  test('a debit outside the drawn days is not drawn', () => {
    render(
      <RhythmCurve
        rhythm={rhythm({
          debits: [{ date: '2026-11-05', label: 'Autre', amountCents: 100, recurringOperationId: 'x' }],
        })}
      />,
    )

    expect(screen.queryByTestId('debit-marker')).not.toBeInTheDocument()
  })

  test('says in words what it draws', () => {
    render(<RhythmCurve rhythm={rhythm()} />)

    expect(screen.getByRole('img')).toHaveAccessibleName(/Dépensé cumulé/)
  })

  test.each([1060, 1440])('draws its labels at the mockup size, %d px wide', (width) => {
    renderedAt(width)
    render(<RhythmCurve rhythm={rhythm({ debits: FOUR_DEBITS })} />)

    for (const text of screen.getAllByTestId('debit-marker').map((marker) => marker.querySelector('text')!)) {
      const fontPx = Number(text.getAttribute('font-size')) * (width / VIEWBOX_WIDTH)
      expect(fontPx).toBeCloseTo(11, 1)
    }
  })

  test.each([1060, 1440])('four markers on two days do not overlap, %d px wide', (width) => {
    renderedAt(width)
    render(<RhythmCurve rhythm={rhythm({ debits: FOUR_DEBITS })} />)

    const boxes = screen.getAllByTestId('debit-marker').map((marker) => {
      const text = marker.querySelector('text')!
      const scale = width / VIEWBOX_WIDTH
      const half = textWidthPx(text.textContent ?? '') / 2

      return { y: Number(text.getAttribute('y')), left: Number(text.getAttribute('x')) * scale - half, right: Number(text.getAttribute('x')) * scale + half }
    })

    for (const [index, a] of boxes.entries()) {
      for (const b of boxes.slice(index + 1)) {
        if (a.y === b.y) {
          expect(a.right <= b.left || b.right <= a.left).toBe(true)
        }
      }
    }
  })

  test('a long label is cut, the list below spells it out', () => {
    render(<RhythmCurve rhythm={rhythm({ debits: FOUR_DEBITS })} />)

    const marker = screen.getAllByTestId('debit-marker')[1]
    expect(marker.querySelector('text')!.textContent!.length).toBeLessThan(24)
    expect(marker.dataset.label).toBe('BOUYGUES TELECOM BOUYGUES TELECO')
    expect(screen.getByText(/BOUYGUES TELECOM BOUYGUES TELECO — le 5/)).toBeInTheDocument()
  })
})

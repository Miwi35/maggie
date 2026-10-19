import { describe, test, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Constellation, LATEST_STAR_COLOR, STAR_COLOR, starsLabel } from './Constellation'
import type { Stars } from './stars'

const stars = (count: number, extra: Partial<Stars> = {}): Stars => ({
  month: '2026-10',
  count,
  total: 31,
  best: null,
  latestAt: count ? '2026-10-15T09:00:00+00:00' : null,
  ...extra,
})

const drawn = () => screen.queryAllByTestId('constellation-star')

describe('Constellation (MAG-509)', () => {
  test('titles the month and counts its stars, one star drawn for each', () => {
    render(<Constellation stars={stars(5)} animate />)

    expect(screen.getByRole('heading', { name: "Ta constellation d'octobre · 5 étoiles" })).toBeInTheDocument()
    expect(drawn()).toHaveLength(5)
    expect(screen.queryByTestId('constellation-more')).toBeNull()
  })

  test('says « 1 étoile » in the singular and de + month before a consonant', () => {
    render(<Constellation stars={stars(1, { month: '2026-11' })} animate />)

    expect(screen.getByRole('heading', { name: 'Ta constellation de novembre · 1 étoile' })).toBeInTheDocument()
    expect(starsLabel(0)).toBe('0 étoile')
  })

  test('a month without a star draws nothing: no empty place to fill', () => {
    render(<Constellation stars={stars(0)} animate />)

    expect(screen.getByRole('heading')).toHaveTextContent('0 étoile')
    expect(drawn()).toHaveLength(0)
  })

  test('the stars are lavender, the latest one orchid and alone', () => {
    render(<Constellation stars={stars(3)} animate />)

    const all = drawn()
    expect(all.map((s) => s.getAttribute('data-latest'))).toEqual(['false', 'false', 'true'])
    expect(getComputedStyle(all[0]).color).toBe(hexToRgb(STAR_COLOR))
    expect(getComputedStyle(all[2]).color).toBe(hexToRgb(LATEST_STAR_COLOR))
  })

  test('the latest star glints, unless the animations are off', () => {
    const { rerender } = render(<Constellation stars={stars(3)} animate />)
    expect(getComputedStyle(drawn()[2]).animation).toContain('1400ms')
    expect(getComputedStyle(drawn()[0]).animation).toBe('')

    rerender(<Constellation stars={stars(3)} animate={false} />)
    expect(getComputedStyle(drawn()[2]).animation).not.toContain('1400ms')
  })

  test('past a hundred: the last hundred drawn and « +n » for the rest', () => {
    render(<Constellation stars={stars(112)} animate />)

    expect(screen.getByRole('heading')).toHaveTextContent('112 étoiles')
    expect(drawn()).toHaveLength(100)
    expect(screen.getByTestId('constellation-more')).toHaveTextContent('+12')
  })

  test('exactly a hundred has no « +n »', () => {
    render(<Constellation stars={stars(100)} animate />)

    expect(drawn()).toHaveLength(100)
    expect(screen.queryByTestId('constellation-more')).toBeNull()
  })

  test('without arrows nor total by default', () => {
    render(<Constellation stars={stars(2)} animate />)

    expect(screen.queryByRole('button', { name: 'Mois précédent' })).toBeNull()
    expect(screen.queryByTestId('constellation-total')).toBeNull()
  })

  test('the total since always sits under the grid, and the arrows move the month', async () => {
    const onPrevious = vi.fn()
    const onNext = vi.fn()
    render(<Constellation stars={stars(7, { month: '2026-09', total: 42 })} animate showTotal onPrevious={onPrevious} onNext={onNext} />)

    expect(within(screen.getByTestId('constellation-total')).getByText('42 étoiles depuis toujours')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Mois précédent' }))
    await userEvent.click(screen.getByRole('button', { name: 'Mois suivant' }))

    expect(onPrevious).toHaveBeenCalledTimes(1)
    expect(onNext).toHaveBeenCalledTimes(1)
  })

  test('on the current month the next arrow is out of reach', () => {
    render(<Constellation stars={stars(4)} animate showTotal onPrevious={vi.fn()} onNext={null} />)

    expect(screen.getByRole('button', { name: 'Mois suivant' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Mois précédent' })).toBeEnabled()
  })
})

function hexToRgb(hex: string): string {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16))

  return `rgb(${r}, ${g}, ${b})`
}

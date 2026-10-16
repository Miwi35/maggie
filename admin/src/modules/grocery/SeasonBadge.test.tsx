import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SeasonBadge } from './SeasonBadge'

describe('SeasonBadge', () => {
  test('in season reads « de saison »', () => {
    render(<SeasonBadge inSeason />)

    expect(screen.getByTestId('season-badge')).toHaveTextContent('de saison')
  })

  test('out of season reads « hors saison »', () => {
    render(<SeasonBadge inSeason={false} />)

    expect(screen.getByTestId('season-badge')).toHaveTextContent('hors saison')
  })

  test('no season, no badge', () => {
    const { container } = render(<SeasonBadge inSeason={null} />)

    expect(container).toBeEmptyDOMElement()
  })
})

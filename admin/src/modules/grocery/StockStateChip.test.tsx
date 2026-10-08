import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StockStateChip } from './StockStateChip'

describe('StockStateChip', () => {
  test.each([
    ['in_stock', 'En stock', 'MuiChip-colorSuccess'],
    ['low', 'Stock faible', 'MuiChip-colorWarning'],
    ['out', 'Rupture', 'MuiChip-colorError'],
  ])('shows %s as « %s » in its own colour', (state, label, colour) => {
    render(<StockStateChip state={state} />)

    expect(screen.getByText(label).closest('.MuiChip-root')).toHaveClass(colour)
  })

  test('shows a record with no state as in stock', () => {
    render(<StockStateChip state={undefined} />)

    expect(screen.getByText('En stock')).toBeInTheDocument()
  })
})

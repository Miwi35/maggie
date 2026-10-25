import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { NutritionBlock } from './RecipeBlocks'

const NUTRITION = { kcal: 800, protein: 40, carbs: 90, fat: 30, skipped: 0 }

describe('NutritionBlock', () => {
  test('gives the share of the day’s need when the reference is known', () => {
    render(<NutritionBlock nutrition={NUTRITION} dailyKcal={2000} />)

    expect(screen.getByRole('heading', { name: 'Valeurs par personne' })).toBeInTheDocument()
    expect(screen.getByTestId('nutrition-energy')).toHaveTextContent('≈ 800 kcal · 40 % du besoin du jour')
    expect(screen.getByText('Protéines 40 g')).toBeInTheDocument()
    expect(screen.getByText('Lipides 30 g')).toBeInTheDocument()
    expect(screen.getByText('Glucides 90 g')).toBeInTheDocument()
  })

  test.each([[null], [undefined], [0]])('leaves the share out when the reference is %s', (dailyKcal) => {
    render(<NutritionBlock nutrition={NUTRITION} dailyKcal={dailyKcal} />)

    expect(screen.getByTestId('nutrition-energy')).toHaveTextContent(/^≈ 800 kcal$/)
    expect(screen.queryByText(/du besoin du jour/)).not.toBeInTheDocument()
  })

  test('shows nothing when no figure can be computed', () => {
    render(<NutritionBlock nutrition={null} dailyKcal={2000} />)

    expect(screen.queryByText('Valeurs par personne')).not.toBeInTheDocument()
  })
})

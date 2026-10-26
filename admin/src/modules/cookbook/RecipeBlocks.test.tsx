import { describe, expect, test } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NutritionBlock } from './RecipeBlocks'
import type { Micronutrient } from './recipeSheet'

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

const MICRONUTRIENTS: Micronutrient[] = [
  { key: 'vitaminC', label: 'Vitamine C', group: 'vitamins', amount: 75, unit: 'mg', referencePercent: 94 },
  { key: 'iron', label: 'Fer', group: 'minerals', amount: 3.45, unit: 'mg', referencePercent: 25 },
  { key: 'omega3Ala', label: 'Oméga-3 ALA', group: 'fattyAcids', amount: 2.35, unit: 'g', referencePercent: 106 },
  { key: 'omega6', label: 'Oméga-6', group: 'fattyAcids', amount: 0.048, unit: 'g', referencePercent: null },
]

describe('Micronutriments (MAG-648)', () => {
  test('is folded under the macros and counts the micronutrients known', () => {
    render(<NutritionBlock nutrition={NUTRITION} micronutrients={MICRONUTRIENTS} />)

    const toggle = screen.getByRole('button', { name: 'Micronutriments · 4' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('Vitamine C')).not.toBeInTheDocument()
  })

  test('opens on the three groups, each line as a share of its reference', async () => {
    const user = userEvent.setup()
    render(<NutritionBlock nutrition={NUTRITION} micronutrients={MICRONUTRIENTS} />)

    await user.click(screen.getByRole('button', { name: 'Micronutriments · 4' }))

    expect(screen.getByRole('button', { name: 'Micronutriments · 4' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual(['Vitamines', 'Minéraux', 'Acides gras'])
    expect(within(screen.getByTestId('micronutrient-vitaminC')).getByText('94 %')).toBeInTheDocument()
    expect(screen.getByTestId('micronutrient-vitaminC')).toHaveTextContent('Vitamine C75 mg94 %')
    expect(screen.getByTestId('micronutrient-iron')).toHaveTextContent('Fer3,5 mg25 %')
    expect(within(screen.getByRole('region', { name: 'Acides gras' })).getByText('Oméga-3 ALA')).toBeInTheDocument()
    expect(screen.getByTestId('micronutrient-omega6')).toHaveTextContent(/^Oméga-60,05 g$/)
  })

  test('leaves a group out when nothing of it is known', async () => {
    const user = userEvent.setup()
    render(<NutritionBlock nutrition={NUTRITION} micronutrients={MICRONUTRIENTS.slice(0, 1)} />)

    await user.click(screen.getByRole('button', { name: 'Micronutriments · 1' }))

    expect(screen.getByRole('region', { name: 'Vitamines' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Minéraux' })).not.toBeInTheDocument()
  })

  test.each([[[]], [undefined]])('shows no line when no micronutrient is known (%s)', (micronutrients) => {
    render(<NutritionBlock nutrition={NUTRITION} micronutrients={micronutrients} />)

    expect(screen.getByTestId('nutrition-energy')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Micronutriments/ })).not.toBeInTheDocument()
  })

  test('shows the micronutrients even when the macros cannot be computed', () => {
    render(<NutritionBlock nutrition={null} micronutrients={MICRONUTRIENTS} />)

    expect(screen.queryByTestId('nutrition-energy')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Micronutriments · 4' })).toBeInTheDocument()
  })
})

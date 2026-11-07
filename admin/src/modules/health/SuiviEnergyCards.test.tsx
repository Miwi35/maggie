import { describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseLightTheme } from '../../theme'
import { SuiviEnergyCard } from './SuiviEnergyCard'
import { SuiviStepsCard } from './SuiviStepsCard'
import type { DailyEnergyState } from './dailyEnergyApi'
import type { DailyEnergy } from './dailyEnergySeries'

const row = (date: string, over: Partial<DailyEnergy> = {}): DailyEnergy => ({
  date,
  steps: null,
  activeKcal: null,
  bmrKcal: 1700,
  workoutKcal: 0,
  stepsKcal: null,
  expendedKcal: 1700,
  ...over,
})

const READY: DailyEnergyState = {
  status: 'ready',
  rows: [
    row('2026-10-19', { steps: 4000, stepsKcal: 100, expendedKcal: 1800 }),
    row('2026-10-20', { steps: 8000, stepsKcal: 250, workoutKcal: 300, expendedKcal: 2250 }),
    row('2026-10-21', { steps: 6000, stepsKcal: 180, bmrKcal: null, expendedKcal: null }),
  ],
}

const wrap = (node: React.ReactNode) => render(<ThemeProvider theme={veilleuseLightTheme}>{node}</ThemeProvider>)

describe('SuiviStepsCard', () => {
  test('shows the average per day, a bar per day, no verdict', () => {
    wrap(<SuiviStepsCard state={READY} today="2026-10-21" selected={null} onSelect={vi.fn()} />)

    const card = screen.getByRole('region', { name: 'Pas' })
    expect(within(card).getByTestId('steps-label')).toHaveTextContent('Moyenne par jour')
    expect(within(card).getByTestId('steps-value')).toHaveTextContent('6 000')
    expect(within(card).getAllByTestId('steps-bar')).toHaveLength(3)
  })

  test('the chosen day shows its figure; clicking it again goes back to the average', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const { rerender } = wrap(<SuiviStepsCard state={READY} today="2026-10-21" selected={null} onSelect={onSelect} />)

    await user.click(screen.getByRole('button', { name: /^mar\. 20 oct\. : 8\s000 pas$/ }))
    expect(onSelect).toHaveBeenCalledWith(1)

    rerender(
      <ThemeProvider theme={veilleuseLightTheme}>
        <SuiviStepsCard state={READY} today="2026-10-21" selected={1} onSelect={onSelect} />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('steps-value')).toHaveTextContent('8 000')
    expect(screen.getByTestId('steps-label')).toHaveTextContent('mar. 20 oct.')
    await user.click(screen.getByRole('button', { name: /^mar\. 20 oct\. : 8\s000 pas$/ }))
    expect(onSelect).toHaveBeenLastCalledWith(null)
  })

  test('says so when no day has steps', () => {
    wrap(<SuiviStepsCard state={{ status: 'ready', rows: [row('2026-10-21')] }} today="2026-10-21" selected={null} onSelect={vi.fn()} />)

    expect(screen.getByText('Aucune donnée de pas')).toBeInTheDocument()
    expect(screen.queryByTestId('steps-bar')).not.toBeInTheDocument()
  })

  test('says in French when the steps cannot be read', () => {
    wrap(<SuiviStepsCard state={{ status: 'error' }} today="2026-10-21" selected={null} onSelect={vi.fn()} />)

    expect(screen.getByText('Erreur : les pas n’ont pas pu être chargés.')).toBeInTheDocument()
  })
})

describe('SuiviEnergyCard', () => {
  test('shows today by default: the API total with base · pas · séances; a missing base is « — » and the total too', () => {
    wrap(<SuiviEnergyCard state={READY} today="2026-10-21" selected={null} onSelect={vi.fn()} />)

    const card = screen.getByRole('region', { name: 'Énergie' })
    expect(within(card).getByTestId('energy-label')).toHaveTextContent('Aujourd’hui')
    expect(within(card).getByTestId('energy-total')).toHaveTextContent('— kcal')
    expect(within(card).getByTestId('energy-detail')).toHaveTextContent('base — kcal · pas 180 kcal · séances 0 kcal')
    expect(within(card).getAllByTestId('energy-bar')).toHaveLength(3)
  })

  test('the chosen day shows its three parts and the total as the API gave it', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    wrap(<SuiviEnergyCard state={READY} today="2026-10-21" selected={1} onSelect={onSelect} />)

    expect(screen.getByTestId('energy-total')).toHaveTextContent('2 250 kcal')
    expect(screen.getByTestId('energy-detail')).toHaveTextContent('base 1 700 kcal · pas 250 kcal · séances 300 kcal')
    await user.click(screen.getByRole('button', { name: 'Aujourd’hui : — kcal' }))
    expect(onSelect).toHaveBeenCalledWith(2)
  })

  test('says in French when the energy cannot be read', () => {
    wrap(<SuiviEnergyCard state={{ status: 'error' }} today="2026-10-21" selected={null} onSelect={vi.fn()} />)

    expect(screen.getByText('Erreur : l’énergie n’a pas pu être chargée.')).toBeInTheDocument()
  })
})

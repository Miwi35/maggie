import { describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseLightTheme } from '../../theme'
import { SuiviEnergyCard } from './SuiviEnergyCard'
import { SuiviEnergyDetailCard } from './SuiviEnergyDetailCard'
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

const axis = { from: '19 oct.', to: '21 oct.' }
const wrap = (node: React.ReactNode) => render(<ThemeProvider theme={veilleuseLightTheme}>{node}</ThemeProvider>)

describe('SuiviStepsCard', () => {
  test('reads « Aujourd’hui · 6 000 » with no day chosen, a bar per day, no verdict', () => {
    wrap(<SuiviStepsCard state={READY} today="2026-10-21" selected={null} onSelect={vi.fn()} axis={axis} />)

    const card = screen.getByRole('region', { name: 'Pas' })
    expect(within(card).getByTestId('steps-label')).toHaveTextContent('Aujourd’hui')
    expect(within(card).getByTestId('steps-value')).toHaveTextContent('6 000')
    expect(card).toHaveTextContent('19 oct.')
    expect(card).toHaveTextContent('21 oct.')
    expect(within(card).getAllByTestId('steps-bar')).toHaveLength(3)
  })

  test('the chosen day shows its figure; clicking it again goes back to today', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const { rerender } = wrap(<SuiviStepsCard state={READY} today="2026-10-21" selected={null} onSelect={onSelect} axis={axis} />)

    await user.click(screen.getByRole('button', { name: /^mar\. 20 oct\. : 8\s000 pas$/ }))
    expect(onSelect).toHaveBeenCalledWith(1)

    rerender(
      <ThemeProvider theme={veilleuseLightTheme}>
        <SuiviStepsCard state={READY} today="2026-10-21" selected={1} onSelect={onSelect} axis={axis} />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('steps-value')).toHaveTextContent('8 000')
    expect(screen.getByTestId('steps-label')).toHaveTextContent('mar. 20 oct.')
    await user.click(screen.getByRole('button', { name: /^mar\. 20 oct\. : 8\s000 pas$/ }))
    expect(onSelect).toHaveBeenLastCalledWith(null)
  })

  test('the chosen steps bar is solid, the others faded', () => {
    wrap(<SuiviStepsCard state={READY} today="2026-10-21" selected={1} onSelect={vi.fn()} axis={axis} />)

    const fills = screen.getAllByTestId('steps-bar').map((bar) => getComputedStyle(bar.firstElementChild as Element).backgroundColor)
    expect(fills[1]).not.toBe(fills[0])
    expect(fills[0]).toBe(fills[2])
    expect(fills[0]).toMatch(/0\.42\)$/)
  })

  test('says so when no day has steps', () => {
    wrap(<SuiviStepsCard state={{ status: 'ready', rows: [row('2026-10-21')] }} today="2026-10-21" selected={null} onSelect={vi.fn()} axis={axis} />)

    expect(screen.getByText('Aucune donnée de pas')).toBeInTheDocument()
    expect(screen.queryByTestId('steps-bar')).not.toBeInTheDocument()
  })

  test('says in French when the steps cannot be read', () => {
    wrap(<SuiviStepsCard state={{ status: 'error' }} today="2026-10-21" selected={null} onSelect={vi.fn()} axis={axis} />)

    expect(screen.getByText('Erreur : les pas n’ont pas pu être chargés.')).toBeInTheDocument()
  })
})

describe('SuiviEnergyCard', () => {
  test('the legend is on top: Base, Pas, Séances; no total, no sentence', () => {
    wrap(<SuiviEnergyCard state={READY} today="2026-10-21" selected={null} onSelect={vi.fn()} axis={axis} />)

    const card = screen.getByRole('region', { name: 'Énergie' })
    expect(within(card).getByTestId('energy-label')).toHaveTextContent('Aujourd’hui')
    expect(card.textContent).toMatch(/BasePasSéances/)
    expect(within(card).queryByTestId('energy-total')).not.toBeInTheDocument()
    expect(card).not.toHaveTextContent('base —')
    expect(within(card).getAllByTestId('energy-bar')).toHaveLength(3)
    expect(card).toHaveTextContent('21 oct.')
  })

  test('a bar chooses its day', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    wrap(<SuiviEnergyCard state={READY} today="2026-10-21" selected={1} onSelect={onSelect} axis={axis} />)

    await user.click(screen.getByRole('button', { name: 'Aujourd’hui : — kcal' }))
    expect(onSelect).toHaveBeenCalledWith(2)
  })

  test('says in French when the energy cannot be read', () => {
    wrap(<SuiviEnergyCard state={{ status: 'error' }} today="2026-10-21" selected={null} onSelect={vi.fn()} axis={axis} />)

    expect(screen.getByText('Erreur : l’énergie n’a pas pu être chargée.')).toBeInTheDocument()
  })
})

describe('SuiviEnergyDetailCard', () => {
  const rowsOf = (card: HTMLElement) => within(card).getAllByRole('row').map((tr) => tr.textContent)

  test('lists Base, Pas, Séances, Dépensées in kcal, « — kcal » without a profile', () => {
    const { rerender } = wrap(<SuiviEnergyDetailCard state={READY} today="2026-10-21" selected={1} />)

    const card = screen.getByRole('region', { name: 'Détail de l’énergie' })
    expect(rowsOf(card).map((text) => text?.replace(/\s/g, ' '))).toEqual(['Base1 700 kcal', 'Pas250 kcal', 'Séances300 kcal', 'Dépensées2 250 kcal'].map((t) => t.replace(/\s/g, ' ')))

    rerender(
      <ThemeProvider theme={veilleuseLightTheme}>
        <SuiviEnergyDetailCard state={READY} today="2026-10-21" selected={null} />
      </ThemeProvider>,
    )
    expect(within(card).getByTestId('energy-total')).toHaveTextContent('— kcal')
    expect(rowsOf(card)[0]).toContain('— kcal')
  })
})

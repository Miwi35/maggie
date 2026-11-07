import { describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import type { Theme } from '@mui/material/styles'
import { SuiviMoodCard } from './SuiviMoodCard'
import type { HealthDay } from './healthDaysApi'
import type { Loaded } from './nutritionApi'

const day = (date: string, mood: number | null, fatigue: number | null): HealthDay => ({
  id: `01${date}`,
  date,
  weightKg: null,
  bodyFatPercent: null,
  weighedAt: null,
  steps: null,
  eatenKcal: null,
  expendedKcal: null,
  balanceKcal: null,
  sportMinutes: null,
  mood,
  fatigue,
  note: null,
  notedBy: 'user',
  notedAt: null,
})

// Four days, the 19th not in the journal: its column is empty and breaks the lines.
const READY: Loaded<HealthDay[]> = {
  status: 'ready',
  data: [day('2026-10-18', 6, 5), day('2026-10-20', 8, 2), day('2026-10-21', 7, 4)],
}

const wrap = (node: React.ReactNode, theme: Theme = veilleuseLightTheme) => render(<ThemeProvider theme={theme}>{node}</ThemeProvider>)
const card = () => screen.getByRole('region', { name: 'Moral · fatigue' })

describe('SuiviMoodCard', () => {
  test('shows today’s scores and one column per day of the period, without a verdict', () => {
    wrap(<SuiviMoodCard state={READY} from="2026-10-18" to="2026-10-21" selected={null} onSelect={vi.fn()} />)

    expect(within(card()).getByTestId('mood-label')).toHaveTextContent('Aujourd’hui')
    expect(within(card()).getByTestId('mood-value')).toHaveTextContent('7')
    expect(within(card()).getByTestId('fatigue-value')).toHaveTextContent('4')
    expect(within(card()).getAllByTestId('mood-column').map((column) => column.getAttribute('aria-label'))).toEqual([
      'dim. 18 oct. : moral 6, fatigue 5',
      'lun. 19 oct. : moral —, fatigue —',
      'mar. 20 oct. : moral 8, fatigue 2',
      'Aujourd’hui : moral 7, fatigue 4',
    ])
    expect(within(card()).getByRole('button', { name: 'Aujourd’hui : moral 7, fatigue 4' })).toHaveAttribute('aria-pressed', 'true')
    expect(card()).toHaveTextContent(/moral.*fatigue$/)
  })

  test('draws moral in a solid amber line and fatigue in a dotted blue one, on a 0–10 scale', () => {
    for (const theme of [veilleuseLightTheme, veilleuseDarkTheme]) {
      const { unmount } = wrap(<SuiviMoodCard state={READY} from="2026-10-18" to="2026-10-21" selected={null} onSelect={vi.fn()} />, theme)

      const mood = within(card()).getByTestId('mood-line')
      const fatigue = within(card()).getByTestId('fatigue-line')
      // x: the middle of each of the four columns; y: 100 − score × 10. The missing 19th starts a new line.
      expect(mood).toHaveAttribute('d', 'M125.0 40.0 M625.0 20.0 L875.0 30.0')
      expect(fatigue).toHaveAttribute('d', 'M125.0 50.0 M625.0 80.0 L875.0 60.0')
      expect(within(card()).getByTestId('mood-dots').getAttribute('d')).toContain('M875.0 30.0 l0 0')
      expect(mood).not.toHaveAttribute('stroke-dasharray')
      expect(fatigue).toHaveAttribute('stroke-dasharray', '4 4')
      expect(mood).toHaveStyle({ stroke: theme.palette.warning.main })
      expect(fatigue).toHaveStyle({ stroke: theme.palette.info.main })
      unmount()
    }
  })

  test('a column chooses its day; clicking it again goes back to today', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    const { rerender } = wrap(<SuiviMoodCard state={READY} from="2026-10-18" to="2026-10-21" selected={null} onSelect={onSelect} />)

    await user.click(screen.getByRole('button', { name: 'mar. 20 oct. : moral 8, fatigue 2' }))
    expect(onSelect).toHaveBeenCalledWith(2)

    rerender(
      <ThemeProvider theme={veilleuseLightTheme}>
        <SuiviMoodCard state={READY} from="2026-10-18" to="2026-10-21" selected={2} onSelect={onSelect} />
      </ThemeProvider>,
    )
    expect(within(card()).getByTestId('mood-label')).toHaveTextContent('mar. 20 oct.')
    expect(within(card()).getByTestId('mood-value')).toHaveTextContent('8')
    expect(within(card()).getByTestId('fatigue-value')).toHaveTextContent('2')

    await user.click(screen.getByRole('button', { name: 'mar. 20 oct. : moral 8, fatigue 2' }))
    expect(onSelect).toHaveBeenLastCalledWith(null)
  })

  test('a day without scores reads « — »; loading and errors are said', () => {
    const { rerender } = wrap(<SuiviMoodCard state={{ status: 'ready', data: [] }} from="2026-10-18" to="2026-10-21" selected={null} onSelect={vi.fn()} />)
    expect(within(card()).getByTestId('mood-value')).toHaveTextContent('—')
    expect(within(card()).getByTestId('mood-line')).toHaveAttribute('d', '')

    rerender(
      <ThemeProvider theme={veilleuseLightTheme}>
        <SuiviMoodCard state={{ status: 'error' }} from="2026-10-18" to="2026-10-21" selected={null} onSelect={vi.fn()} />
      </ThemeProvider>,
    )
    expect(within(card()).getByText('Erreur : le moral et la fatigue n’ont pas pu être chargés.')).toBeInTheDocument()
  })
})


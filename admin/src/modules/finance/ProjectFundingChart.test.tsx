import { describe, expect, test } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { chartColor } from '../../design/tokens'
import { renderWithTheme } from '../../test/renderWithTheme'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { ProjectFundingChart } from './ProjectFundingChart'
import { ProjectGantt } from './ProjectGantt'
import type { PlanningMonth, ProjectPlanning } from './projectsApi'

const monthName = (offset: number) => {
  const total = 2026 * 12 + 9 + offset

  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`
}

/** 36 months from 2026-10 with a 500 € capacity; `overrides` replaces a month by its offset. */
const months = (overrides: Record<number, Partial<PlanningMonth>> = {}): PlanningMonth[] =>
  Array.from({ length: 36 }, (_, index) => ({
    month: monthName(index),
    capacityCents: 50000,
    salaryCents: 0,
    savingsCents: 0,
    capitalCents: 0,
    isOverCapacity: false,
    ...overrides[index],
  }))

const planning = (list: PlanningMonth[], overrides: Partial<ProjectPlanning> = {}): ProjectPlanning => ({
  startMonth: '2026-10',
  horizonMonths: 36,
  currency: 'EUR',
  isIncomeKnown: true,
  cushion: { state: 'complete', completeMonth: null },
  months: list,
  projects: [],
  ...overrides,
})

// « Mariage 6 000 € à date fixe M2, capacité 500 €/mois »: 2 000 € of salary in M0, M1 and M2.
const WEDDING = months({
  0: { salaryCents: 200000, isOverCapacity: true },
  1: { salaryCents: 200000, isOverCapacity: true },
  2: { salaryCents: 200000, isOverCapacity: true },
})

const monthOf = (month: string) =>
  screen.getAllByTestId('funding-bar').find((column) => column.getAttribute('data-month') === month) as HTMLElement

describe('ProjectFundingChart', () => {
  test('draws one bar per month over 36 months, the current one marked on the axis', () => {
    renderWithTheme(<ProjectFundingChart planning={planning(WEDDING)} />)

    expect(screen.getAllByTestId('funding-bar')).toHaveLength(36)
    const axis = screen.getByTestId('funding-axis')
    expect(axis.querySelectorAll('[data-month]')).toHaveLength(36)
    expect(within(axis).getByTestId('funding-today')).toHaveAttribute('data-month', '2026-10')
  })

  test('stacks the salary of the wedding over the capacity mark and flags its three months', () => {
    renderWithTheme(<ProjectFundingChart planning={planning(WEDDING)} />)

    const salary = within(monthOf('2026-10')).getByTestId('funding-segment')
    expect(salary).toHaveAttribute('data-source', 'salary')
    expect(salary).toHaveAttribute('data-cents', '200000')
    expect(screen.getAllByTestId('funding-over').map((mark) => mark.getAttribute('data-month'))).toEqual(['2026-10', '2026-11', '2026-12'])
    expect(within(monthOf('2027-01')).queryByTestId('funding-over')).toBeNull()
    expect(within(monthOf('2027-01')).queryByTestId('funding-segment')).toBeNull()
  })

  test('draws the capacity as a staircase that steps where the capacity changes', () => {
    renderWithTheme(<ProjectFundingChart planning={planning(months({ 2: { capacityCents: 25000 } }))} />)

    // Plot of 140 high, scale 500 €: 500 € sits at 0, 250 € at 70 — the third month only.
    const path = screen.getByTestId('funding-capacity').getAttribute('d') as string
    expect(path.startsWith('M0,0 L1,0')).toBe(true)
    expect(path).toContain('L2,70 L3,70')
    expect(path).toContain('L3,0 L4,0')
  })

  test('shows 800 € of savings as a savings segment in the month of the purchase', () => {
    renderWithTheme(<ProjectFundingChart planning={planning(months({ 4: { savingsCents: 80000 } }))} />)

    const segment = within(monthOf('2027-02')).getByTestId('funding-segment')
    expect(segment).toHaveAttribute('data-source', 'savings')
    expect(segment).toHaveAttribute('data-cents', '80000')
    expect(within(monthOf('2027-02')).queryByTestId('funding-over')).toBeNull()
  })

  test('stacks salary, savings and released capital in that order', () => {
    renderWithTheme(
      <ProjectFundingChart planning={planning(months({ 1: { salaryCents: 10000, savingsCents: 20000, capitalCents: 30000 } }))} />,
    )

    const sources = within(monthOf('2026-11'))
      .getAllByTestId('funding-segment')
      .map((segment) => segment.getAttribute('data-source'))
    expect(sources).toEqual(['salary', 'savings', 'capital'])
  })

  test('hovering a flagged month gives the amount by source, the capacity and the excess', async () => {
    const user = userEvent.setup()
    renderWithTheme(<ProjectFundingChart planning={planning(WEDDING)} />)

    await user.hover(monthOf('2026-10'))
    const tip = await screen.findByRole('tooltip')
    expect(tip).toHaveTextContent('oct. 2026')
    expect(tip).toHaveTextContent(/Salaire 2\s000,00\s€/)
    expect(tip).toHaveTextContent(/Économies 0,00\s€/)
    expect(tip).toHaveTextContent(/Capacité 500,00\s€/)
    expect(tip).toHaveTextContent(/Dépassement 1\s500,00\s€/)
  })

  test('a month under its capacity says nothing about an excess', async () => {
    const user = userEvent.setup()
    renderWithTheme(<ProjectFundingChart planning={planning(months({ 4: { savingsCents: 80000 } }))} />)

    await user.hover(monthOf('2027-02'))
    const tip = await screen.findByRole('tooltip')
    expect(tip).toHaveTextContent(/Économies 800,00\s€/)
    expect(tip).not.toHaveTextContent('Dépassement')
  })

  test('a month is focusable and named with the detail of its tooltip', async () => {
    const user = userEvent.setup()
    renderWithTheme(<ProjectFundingChart planning={planning(WEDDING)} />)

    await user.tab()
    expect(monthOf('2026-10')).toHaveFocus()
    expect(monthOf('2026-10').getAttribute('aria-label')).toContain('Dépassement')
  })

  test.each([
    ['light', veilleuseLightTheme, 'light'],
    ['dark', veilleuseDarkTheme, 'dark'],
  ] as const)('draws the three sources with category colours and no alert colour (%s)', (_name, theme, mode) => {
    renderWithTheme(
      <ProjectFundingChart planning={planning(months({ 0: { salaryCents: 200000, savingsCents: 10000, capitalCents: 5000, isOverCapacity: true } }))} />,
      theme,
    )

    const colours = new Map(
      screen.getAllByTestId('funding-segment').map((segment) => [segment.getAttribute('data-source'), segment]),
    )
    expect(colours.get('salary')).toHaveStyle({ backgroundColor: chartColor(1, mode) })
    expect(colours.get('savings')).toHaveStyle({ backgroundColor: chartColor(2, mode) })
    expect(colours.get('capital')).toHaveStyle({ backgroundColor: chartColor(3, mode) })
    const alerts = [theme.palette.error.main, theme.palette.warning.main, theme.palette.error.light, theme.palette.error.dark]
    for (const segment of colours.values()) {
      for (const alert of alerts) expect(segment).not.toHaveStyle({ backgroundColor: alert })
    }
    for (const alert of alerts) {
      expect(screen.getByTestId('funding-over')).not.toHaveStyle({ backgroundColor: alert })
      expect(screen.getByTestId('funding-capacity').getAttribute('stroke')).not.toBe(alert)
    }
  })

  test('names the three sources and the capacity in a legend', () => {
    renderWithTheme(<ProjectFundingChart planning={planning(WEDDING)} />)

    const legend = screen.getByTestId('funding-legend')
    for (const name of ['Salaire', 'Économies', 'Capital libéré', 'Capacité sur salaire']) {
      expect(within(legend).getByText(name)).toBeInTheDocument()
    }
  })

  test('draws nothing without months', () => {
    const { container } = renderWithTheme(<ProjectFundingChart planning={planning([])} />)

    expect(container).toBeEmptyDOMElement()
  })

  test('sits under the Gantt, in its scroll and on the same 36 month columns', () => {
    const project = {
      projectId: 'WEDDING',
      name: 'Mariage',
      position: 0,
      wellbeing: 'high' as const,
      amountCents: 600000,
      dateMode: 'fixed' as const,
      targetOn: '2026-12-15',
      savingFromMonth: '2026-10',
      purchaseMonth: '2026-12',
      gatheredCents: 0,
      loan: null,
      isBeyondHorizon: false,
      isDeadlineMissed: false,
      delayMonths: 0,
      unmetDependencies: [],
    }
    renderWithTheme(
      <ProjectGantt planning={planning(WEDDING, { projects: [project] })}>
        <ProjectFundingChart planning={planning(WEDDING)} />
      </ProjectGantt>,
    )

    const gantt = screen.getByTestId('project-gantt')
    expect(within(gantt).getByTestId('funding-chart')).toBeInTheDocument()
    expect(within(gantt).getByTestId('gantt-axis').querySelectorAll('[data-month]')).toHaveLength(36)
    expect(within(gantt).getByTestId('funding-axis').querySelectorAll('[data-month]')).toHaveLength(36)
  })

  test('is not drawn when the Gantt is not (income unknown)', () => {
    renderWithTheme(
      <ProjectGantt planning={planning(WEDDING, { isIncomeKnown: false })}>
        <ProjectFundingChart planning={planning(WEDDING)} />
      </ProjectGantt>,
    )

    expect(screen.queryByTestId('funding-chart')).toBeNull()
  })
})

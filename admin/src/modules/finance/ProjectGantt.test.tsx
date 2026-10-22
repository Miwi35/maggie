import { describe, expect, test } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { veilleuseLightTheme } from '../../theme'
import { ProjectGantt } from './ProjectGantt'
import type { PlannedProject, ProjectPlanning } from './projectsApi'

const planned = (name: string, fields: Partial<PlannedProject>): PlannedProject => ({
  projectId: name.toUpperCase(),
  name,
  position: 0,
  wellbeing: 'medium',
  amountCents: 100000,
  dateMode: 'none',
  targetOn: null,
  savingFromMonth: '2026-10',
  purchaseMonth: '2026-12',
  gatheredCents: 0,
  loan: null,
  isBeyondHorizon: false,
  isDeadlineMissed: false,
  delayMonths: 0,
  unmetDependencies: [],
  ...fields,
})

// « Vélo 1 200 € puis Japon 3 000 €, 500 €/mois » (MAG-526): the bike M0 → M2, Japan M2 → M8.
const BIKE = planned('Vélo', { position: 0, amountCents: 120000, savingFromMonth: '2026-10', purchaseMonth: '2026-12', gatheredCents: 20000 })
const JAPAN = planned('Japon', { position: 1, amountCents: 300000, savingFromMonth: '2026-12', purchaseMonth: '2027-06' })

const planning = (projects: PlannedProject[], overrides: Partial<ProjectPlanning> = {}): ProjectPlanning => ({
  startMonth: '2026-10',
  horizonMonths: 36,
  currency: 'EUR',
  isIncomeKnown: true,
  cushion: { state: 'complete', completeMonth: null },
  months: [],
  projects,
  ...overrides,
})

const rowOf = (name: string) =>
  screen.getAllByTestId('gantt-row').find((row) => row.getAttribute('data-project') === name) as HTMLElement

describe('ProjectGantt', () => {
  test('draws the bike from month 0 to 2 and Japan from 2 to 8, in that order', () => {
    renderWithTheme(<ProjectGantt planning={planning([BIKE, JAPAN])} />)

    expect(screen.getAllByTestId('gantt-row').map((row) => row.getAttribute('data-project'))).toEqual(['Vélo', 'Japon'])
    const bike = within(rowOf('Vélo')).getByTestId('gantt-saving')
    expect([bike.getAttribute('data-from'), bike.getAttribute('data-to')]).toEqual(['0', '2'])
    const japan = within(rowOf('Japon')).getByTestId('gantt-saving')
    expect([japan.getAttribute('data-from'), japan.getAttribute('data-to')]).toEqual(['2', '8'])
    expect(within(rowOf('Vélo')).getByTestId('gantt-purchase')).toHaveAttribute('data-at', '2')
    expect(within(rowOf('Japon')).getByTestId('gantt-purchase')).toHaveAttribute('data-at', '8')
  })

  test('numbers the axis over 36 months and marks the current month', () => {
    renderWithTheme(<ProjectGantt planning={planning([BIKE])} />)

    const axis = screen.getByTestId('gantt-axis')
    expect(axis.querySelectorAll('[data-month]')).toHaveLength(36)
    const today = within(axis).getByTestId('gantt-today')
    expect(today).toHaveAttribute('data-month', '2026-10')
    expect(today).toHaveAttribute('aria-current', 'date')
    expect(axis.querySelector('[data-month="2027-01"]')).toHaveTextContent('2027')
    expect(axis.querySelector('[data-month="2029-09"]')).not.toBeNull()
  })

  test('draws a loan from the month after the purchase to its last payment', () => {
    const loaned = planned('Voiture', { purchaseMonth: '2027-01', loan: { startMonth: '2027-02', endMonth: '2027-12', monthlyPaymentCents: 25000 } })
    renderWithTheme(<ProjectGantt planning={planning([loaned])} />)

    const loan = within(rowOf('Voiture')).getByTestId('gantt-loan')
    expect([loan.getAttribute('data-from'), loan.getAttribute('data-to')]).toEqual(['4', '14'])
    expect(within(loan).queryByTestId('gantt-beyond')).toBeNull()
  })

  test('cuts a loan at the right edge and draws an arrow when it outlives the 36 months', () => {
    const loaned = planned('Maison', { purchaseMonth: '2027-01', loan: { startMonth: '2027-02', endMonth: null, monthlyPaymentCents: 90000 } })
    renderWithTheme(<ProjectGantt planning={planning([loaned])} />)

    const loan = within(rowOf('Maison')).getByTestId('gantt-loan')
    expect(loan).toHaveAttribute('data-to', '35')
    expect(within(loan).getByTestId('gantt-beyond')).toBeInTheDocument()
  })

  test('a project past 36 months shows an arrow at the edge and no purchase month', () => {
    const far = planned('Maison', { savingFromMonth: '2026-10', purchaseMonth: null, isBeyondHorizon: true, amountCents: 50000000 })
    renderWithTheme(<ProjectGantt planning={planning([far])} />)

    const row = within(rowOf('Maison'))
    expect(row.queryByTestId('gantt-purchase')).toBeNull()
    expect(row.getByTestId('gantt-saving')).toHaveAttribute('data-to', '35')
    expect(row.getByTestId('gantt-beyond')).toBeInTheDocument()
  })

  test('a fixed date has its padlock, a deadline its vertical mark, an unmet dependency its signal', () => {
    const fixed = planned('Déménagement', { dateMode: 'fixed', targetOn: '2027-03-01', purchaseMonth: '2027-03' })
    const deadline = planned('Vélo', { position: 1, dateMode: 'deadline', targetOn: '2027-01-31', purchaseMonth: '2027-02', unmetDependencies: ['MOVE'] })
    renderWithTheme(<ProjectGantt planning={planning([fixed, deadline])} />)

    expect(within(rowOf('Déménagement')).getByTestId('gantt-fixed')).toBeInTheDocument()
    expect(within(rowOf('Déménagement')).queryByTestId('gantt-deadline')).toBeNull()
    expect(within(rowOf('Vélo')).queryByTestId('gantt-fixed')).toBeNull()
    expect(within(rowOf('Vélo')).getByTestId('gantt-deadline')).toHaveAttribute('data-at', '3')
    expect(within(rowOf('Vélo')).getByTestId('gantt-unmet')).toBeInTheDocument()
    expect(within(rowOf('Déménagement')).queryByTestId('gantt-unmet')).toBeNull()
  })

  test('shows a « Matelas » line up to the month the cushion is complete, and none once it is', () => {
    const { unmount } = renderWithTheme(
      <ProjectGantt planning={planning([BIKE], { cushion: { state: 'building', completeMonth: '2027-02' } })} />,
    )
    const row = screen.getByTestId('gantt-cushion-row')
    expect(row).toHaveTextContent('Matelas')
    expect(within(row).getByTestId('gantt-cushion')).toHaveAttribute('data-to', '4')
    expect(screen.getAllByTestId('gantt-row')[0]?.compareDocumentPosition(row)).toBe(Node.DOCUMENT_POSITION_PRECEDING)
    unmount()

    renderWithTheme(<ProjectGantt planning={planning([BIKE])} />)
    expect(screen.queryByTestId('gantt-cushion-row')).toBeNull()
  })

  test('runs the cushion to the edge when it is not complete within the horizon', () => {
    renderWithTheme(<ProjectGantt planning={planning([BIKE], { cushion: { state: 'recharging', completeMonth: null } })} />)

    const cushion = screen.getByTestId('gantt-cushion')
    expect(cushion).toHaveAttribute('data-to', '35')
    expect(within(cushion).getByTestId('gantt-beyond')).toBeInTheDocument()
  })

  test('hovering a bar gives the month, the amount gathered and the amount of the project', async () => {
    const user = userEvent.setup()
    renderWithTheme(<ProjectGantt planning={planning([BIKE, JAPAN])} />)

    await user.hover(within(rowOf('Vélo')).getByTestId('gantt-purchase'))
    const tip = await screen.findByRole('tooltip')
    expect(tip).toHaveTextContent('Vélo · achat en déc. 2026')
    expect(tip).toHaveTextContent(/Réuni\s200,00\s€ sur 1\s200,00\s€/)

    await user.unhover(within(rowOf('Vélo')).getByTestId('gantt-purchase'))
    await user.hover(within(rowOf('Japon')).getByTestId('gantt-saving'))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Japon · épargne de déc. 2026 à juin 2027')
  })

  test('a bar is focusable and named with the same detail as its tooltip', async () => {
    const user = userEvent.setup()
    renderWithTheme(<ProjectGantt planning={planning([BIKE])} />)

    await user.tab()
    const bar = within(rowOf('Vélo')).getByTestId('gantt-saving')
    expect(bar).toHaveFocus()
    expect(bar.getAttribute('aria-label')).toContain('Vélo · épargne de oct. 2026 à déc. 2026')
  })

  test('shows nothing while the income is unknown', () => {
    const { container } = renderWithTheme(<ProjectGantt planning={planning([BIKE], { isIncomeKnown: false })} />)

    expect(container).toBeEmptyDOMElement()
  })

  test('shows nothing without a project', () => {
    const { container } = renderWithTheme(<ProjectGantt planning={planning([])} />)

    expect(container).toBeEmptyDOMElement()
  })

  test('the compact variant keeps the first three projects, with no axis and no cushion line', () => {
    const four = ['A', 'B', 'C', 'D'].map((name, position) => planned(name, { position }))
    renderWithTheme(
      <ProjectGantt variant="compact" planning={planning(four, { cushion: { state: 'building', completeMonth: '2027-02' } })} />,
    )

    expect(screen.getAllByTestId('gantt-row').map((row) => row.getAttribute('data-project'))).toEqual(['A', 'B', 'C'])
    expect(screen.queryByTestId('gantt-axis')).toBeNull()
    expect(screen.queryByTestId('gantt-cushion-row')).toBeNull()
  })

  test.each([
    ['dark', undefined],
    ['light', veilleuseLightTheme],
  ] as const)('a project that comes after its deadline is a fact, with no red and no reproach (%s)', (_mode, theme) => {
    const late = planned('Vélo', {
      dateMode: 'deadline',
      targetOn: '2026-12-31',
      purchaseMonth: '2027-04',
      isDeadlineMissed: true,
      delayMonths: 4,
    })
    const { container } = renderWithTheme(<ProjectGantt planning={planning([late])} />, theme)

    // The bar runs past the deadline mark.
    expect(within(rowOf('Vélo')).getByTestId('gantt-deadline')).toHaveAttribute('data-at', '2')
    expect(within(rowOf('Vélo')).getByTestId('gantt-saving')).toHaveAttribute('data-to', '6')
    expect(container.textContent).not.toMatch(/retard|manqu|dépass|échou|raté|alerte|erreur/i)

    for (const node of [container, ...container.querySelectorAll<HTMLElement>('*')]) {
      const style = getComputedStyle(node)
      for (const value of [style.color, style.backgroundColor, style.borderTopColor, style.borderRightColor, style.fill, style.stroke]) {
        const channels = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(value)
        if (!channels) continue
        const [red, green, blue] = channels.slice(1).map(Number) as [number, number, number]
        expect(red > 170 && green < 90 && blue < 90, `${node.tagName} is red: ${value}`).toBe(false)
      }
    }
  })
})

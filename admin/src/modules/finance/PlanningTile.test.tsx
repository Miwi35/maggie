import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { dashboardFixture } from './dashboardFixture'
import { PlanningTile } from './PlanningTile'

const planningHook = vi.hoisted(() => ({ planning: null as unknown }))
vi.mock('./useProjectPlanning', () => ({
  useProjectPlanning: () => ({ status: planningHook.planning ? 'ready' : 'failed', planning: planningHook.planning, reload: () => {} }),
}))

const project = (name: string, position: number) => ({
  projectId: name,
  name,
  position,
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
})

const renderTile = (tile = dashboardFixture().tiles.planning) =>
  render(
    <MemoryRouter>
      <PlanningTile tile={tile} />
    </MemoryRouter>,
  )

describe('PlanningTile', () => {
  test('shows the saving capacity and its three terms', () => {
    renderTile()

    const capacity = screen.getByTestId('saving-capacity')
    expect(capacity).toHaveTextContent(/1\s973,34\s€/)
    expect(capacity).toHaveTextContent(/2\s350,00\s€ de revenu/)
    expect(capacity).toHaveTextContent(/210,00\s€ de mensualités/)
    expect(capacity).toHaveTextContent(/166,66\s€ de train de vie/)
  })

  test('draws no capacity block, and no 0 €, when the net income is unknown', () => {
    const tile = dashboardFixture().tiles.planning
    tile.savingCapacity = { ...tile.savingCapacity, isIncomeKnown: false }
    renderTile(tile)

    expect(screen.queryByTestId('saving-capacity')).not.toBeInTheDocument()
    expect(screen.queryByText(/Renseignez/)).not.toBeInTheDocument()
  })

  test('shows the independence and the rentes breakdown', () => {
    renderTile()

    expect(screen.getByText('60 %')).toBeInTheDocument()
    const rente = screen.getByTestId('rente')
    expect(rente).toHaveAttribute('data-category', 'Loyers perçus')
  })

  test('shows the date N in one line', () => {
    renderTile()

    expect(screen.getByTestId('independence-n-line')).toHaveTextContent('Date N projetée · septembre 2045')
  })

  test('links to the treasury outlook', () => {
    renderTile()

    expect(screen.getByRole('link', { name: 'Planification' })).toHaveAttribute('href', '/finance/treasury')
  })

  test('draws the first three projects as a compact Gantt', () => {
    planningHook.planning = {
      startMonth: '2026-10',
      horizonMonths: 36,
      currency: 'EUR',
      isIncomeKnown: true,
      cushion: { state: 'complete', completeMonth: null },
      projects: ['A', 'B', 'C', 'D'].map((name, index) => project(name, index)),
    }
    renderTile()

    expect(screen.getByTestId('project-gantt')).toHaveAttribute('data-variant', 'compact')
    expect(screen.getAllByTestId('gantt-row')).toHaveLength(3)
    planningHook.planning = null
  })

  test('has no Gantt without a planning', () => {
    planningHook.planning = null
    renderTile()

    expect(screen.queryByTestId('project-gantt')).not.toBeInTheDocument()
    expect(screen.getByTestId('saving-capacity')).toBeInTheDocument()
  })
})

import { describe, test, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { dashboardFixture } from './dashboardFixture'
import { IndependenceProjectionBlock } from './IndependenceProjection'
import type { IndependenceCounter, IndependenceProjection } from './useFinanceDashboard'

const counter = (
  projection: Partial<IndependenceProjection> = {},
  overrides: Partial<IndependenceCounter> = {},
): IndependenceCounter => {
  const base = dashboardFixture().independence

  return { ...base, ...overrides, projection: { ...base.projection, ...projection } }
}

const NONE = { '25': null, '50': null, '75': null, '100': null }

describe('IndependenceProjectionBlock', () => {
  test('names the month N and the time left', () => {
    render(<IndependenceProjectionBlock counter={counter()} />)

    const block = screen.getByTestId('independence-n-date')
    expect(block).toHaveTextContent('Date N projetée · septembre 2045')
    expect(block).toHaveTextContent('Dans 18 ans et 11 mois')
  })

  test('draws the curve with a text summary and a 0 to 100 % axis', () => {
    render(<IndependenceProjectionBlock counter={counter()} />)

    const curve = screen.getByTestId('independence-curve')
    const chart = within(curve).getByRole('img')
    expect(chart).toHaveAccessibleName(/de octobre 2026 \(60 %\) à septembre 2045 \(100 %\)/)
    expect(curve.querySelector('polyline')).not.toBeNull()
    expect(curve).toHaveTextContent('0 %')
    expect(curve).toHaveTextContent('100 %')
  })

  test('dates each milestone, and says "atteint" for those already behind', () => {
    render(<IndependenceProjectionBlock counter={counter()} />)

    const list = screen.getByTestId('independence-milestones')
    const items = within(list).getAllByRole('listitem').map((li) => li.textContent)
    expect(items).toEqual(['25 % · atteint', '50 % · atteint', '75 % · mars 2033', '100 % · septembre 2045'])
  })

  test('states plainly that N is not reached within 20 years', () => {
    render(
      <IndependenceProjectionBlock
        counter={counter({ nDate: null, milestones: { ...NONE, '25': '2030-01' } })}
      />,
    )

    expect(screen.getByTestId('independence-n-date')).toHaveTextContent("N non atteinte d'ici 20 ans")
    expect(screen.getByTestId('independence-milestones')).toHaveTextContent("100 % · non atteint d'ici 20 ans")
    expect(screen.getByTestId('independence-milestones')).toHaveTextContent('25 % · janvier 2030')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('says the rentes already cover the train de vie when N is the current month', () => {
    render(
      <IndependenceProjectionBlock
        counter={counter({
          nDate: '2026-10',
          curve: [{ month: '2026-10', coverage: 1 }],
          milestones: { '25': '2026-10', '50': '2026-10', '75': '2026-10', '100': '2026-10' },
        })}
      />,
    )

    expect(screen.getByTestId('independence-n-date')).toHaveTextContent('Les rentes couvrent déjà le train de vie')
    expect(screen.queryByText(/Dans /)).not.toBeInTheDocument()
    expect(screen.getByTestId('independence-milestones')).toHaveTextContent('100 % · atteint')
  })

  test('shows nothing when no train de vie is measured', () => {
    const { container } = render(
      <IndependenceProjectionBlock counter={counter({ nDate: null, curve: [] }, { isMeasurable: false })} />,
    )

    expect(container).toBeEmptyDOMElement()
  })

  test('states the assumptions in one footnote, without advice', () => {
    render(<IndependenceProjectionBlock counter={counter()} />)

    const block = screen.getByTestId('independence-projection')
    expect(block).toHaveTextContent(/4 % par an/)
    expect(block).not.toHaveTextContent(/recommand|devriez/)
  })
})

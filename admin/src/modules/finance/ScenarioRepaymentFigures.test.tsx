import { beforeEach, describe, expect, test, vi } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { ScenarioRepaymentFigures } from './ScenarioRepaymentFigures'
import { nImpactLabel } from './scenariosApi'
import type { Scenario } from './scenariosApi'

const fetchRepaymentFigures = vi.hoisted(() => vi.fn())

vi.mock('./scenariosApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./scenariosApi')>()),
  fetchRepaymentFigures,
}))

const scenario = (fields: Partial<Scenario> = {}): Scenario => ({
  id: '01MINE',
  name: 'Mon plan',
  kind: 'user',
  status: 'saved',
  current: false,
  projectIds: ['MOVE'],
  repayments: [{ id: '01REPAY', loanId: 'LOAN', month: '2027-04', amountCents: null, effect: null }],
  placementIds: [],
  monthlySaving: null,
  nDate: '2041-12',
  metrics: {} as Scenario['metrics'],
  ...fields,
})

describe('ScenarioRepaymentFigures', () => {
  beforeEach(() => {
    fetchRepaymentFigures.mockReset().mockResolvedValue({})
  })

  test('reads the figures again when the projects, the placements or the saving change, not only the repayments', async () => {
    const view = render(<ScenarioRepaymentFigures scenario={scenario()} />)
    await waitFor(() => expect(fetchRepaymentFigures).toHaveBeenCalledTimes(1))

    view.rerender(<ScenarioRepaymentFigures scenario={scenario()} />)
    expect(fetchRepaymentFigures).toHaveBeenCalledTimes(1)

    view.rerender(<ScenarioRepaymentFigures scenario={scenario({ projectIds: ['SOFA', 'MOVE'] })} />)
    await waitFor(() => expect(fetchRepaymentFigures).toHaveBeenCalledTimes(2))

    view.rerender(<ScenarioRepaymentFigures scenario={scenario({ projectIds: ['SOFA', 'MOVE'], monthlySaving: 20000 })} />)
    await waitFor(() => expect(fetchRepaymentFigures).toHaveBeenCalledTimes(3))

    view.rerender(
      <ScenarioRepaymentFigures scenario={scenario({ projectIds: ['SOFA', 'MOVE'], monthlySaving: 20000, placementIds: ['P1'] })} />,
    )
    await waitFor(() => expect(fetchRepaymentFigures).toHaveBeenCalledTimes(4))
  })
})

describe('nImpactLabel', () => {
  test.each([
    [-3, 'N − 3 mois'],
    [2, 'N + 2 mois'],
    [0, 'N inchangée'],
    [null, 'N non atteinte avant 20 ans'],
  ])('says %s as « %s »', (months, label) => {
    expect(nImpactLabel(months)).toBe(label)
  })
})

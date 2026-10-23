import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  centsToEuros,
  clearCurrent,
  currentLabel,
  deleteScenario,
  eurosToCents,
  fetchComparison,
  gapAgainst,
  makeCurrent,
  monthLabel,
  nLabel,
  toComparison,
  toScenario,
} from './scenariosApi'

const raw = {
  id: '01SCENARIO',
  name: 'Mon plan',
  kind: 'user',
  status: 'saved',
  current: true,
  projects: [
    { projectId: 'B', position: 1 },
    { projectId: '/api/finance/projects/A', position: 0 },
  ],
  repayments: [{ id: 'r1', loanId: 'L1', month: '2027-04', amountCents: null }],
  placements: [{ placementId: 'P1' }],
  monthlySaving: 30000,
  nDate: '2041-03',
  metrics: { projectsDoneBy: '2029-01', debtsEndDate: null, interestPaid: 1234, wellbeing: 7 },
}

describe('scenariosApi', () => {
  afterEach(() => vi.unstubAllGlobals())

  test('reads a scenario with its projects in priority order and bare ids', () => {
    const scenario = toScenario(raw)

    expect(scenario.projectIds).toEqual(['A', 'B'])
    expect(scenario.placementIds).toEqual(['P1'])
    expect(scenario.repayments).toEqual([{ id: 'r1', loanId: 'L1', month: '2027-04', amountCents: null }])
    expect(scenario).toMatchObject({ kind: 'user', status: 'saved', current: true, monthlySaving: 30000, nDate: '2041-03' })
    expect(scenario.metrics).toEqual({ projectsDoneBy: '2029-01', debtsEndDate: null, interestPaid: 1234, wellbeing: 7 })
  })

  test('tolerates a bare answer: no projects, no metrics, an unknown kind', () => {
    const scenario = toScenario({ id: 'x', name: 'Vide', kind: 'weird' })

    expect(scenario).toMatchObject({ kind: 'user', status: 'draft', current: false, projectIds: [], monthlySaving: null, nDate: null })
    expect(scenario.metrics).toEqual({ projectsDoneBy: null, debtsEndDate: null, interestPaid: 0, wellbeing: 0 })
  })

  test('reads a comparison with its curves and the API sentences untouched', () => {
    const comparison = toComparison({
      referenceId: 'a',
      scenarios: [{ ...raw, id: 'a', curve: [{ month: '2026-10', coverage: 0.5 }], milestones: { '50': '2026-10' } }],
      differences: [{ scenarioId: 'b', againstId: 'a', months: -9, sentence: 'N 9 mois plus tôt que « Mon plan »' }],
    })

    expect(comparison.scenarios[0]?.curve).toEqual([{ month: '2026-10', coverage: 0.5 }])
    expect(comparison.differences[0]).toMatchObject({ months: -9, sentence: 'N 9 mois plus tôt que « Mon plan »' })
  })

  test('words months and N in French, and says when N is out of reach', () => {
    expect(monthLabel('2041-03')).toBe('mars 2041')
    expect(monthLabel(null)).toBe('—')
    expect(nLabel({ nDate: '2041-03' })).toBe('mars 2041')
    expect(nLabel({ nDate: null })).toBe('non atteinte avant 20 ans')
    expect(currentLabel({ name: 'Rapide', nDate: '2041-03' })).toBe('Scénario courant · Rapide · N : mars 2041')
  })

  test('reads the gap of N against the current scenario as a fact, never as a verdict', () => {
    expect(gapAgainst({ nDate: '2040-06' }, { nDate: '2041-03' })).toBe('N 9 mois plus tôt que le courant')
    expect(gapAgainst({ nDate: '2042-03' }, { nDate: '2041-03' })).toBe('N 1 an plus tard que le courant')
    expect(gapAgainst({ nDate: '2041-03' }, { nDate: '2041-03' })).toBe('N à la même date que le courant')
    expect(gapAgainst({ nDate: null }, { nDate: '2041-03' })).toBeNull()
  })

  test('turns euros typed into cents, and refuses what is not a positive amount', () => {
    expect(eurosToCents('150')).toBe(15000)
    expect(eurosToCents(' 1 200,5 ')).toBe(120050)
    expect(eurosToCents('')).toBeNull()
    expect(eurosToCents('abc')).toBeNaN()
    expect(eurosToCents('-3')).toBeNaN()
    expect(centsToEuros(15050)).toBe('150.5')
    expect(centsToEuros(null)).toBe('')
  })

  test('asks the comparison with the ids in the order given', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ referenceId: 'b', scenarios: [], differences: [] })))
    vi.stubGlobal('fetch', fetchMock)

    await fetchComparison(['b', 'auto-rapid', 'a'])

    expect(String((fetchMock.mock.calls[0] as unknown[])[0])).toBe('/api/finance/scenario-comparison?ids=b,auto-rapid,a')
  })

  test.each([
    ['make current', () => makeCurrent('nope'), 404, 'Scenario not found: nope'],
    ['delete', () => deleteScenario('auto-rapid'), 400, 'An automatic scenario cannot be deleted.'],
    ['clear', () => clearCurrent(), 400, 'Nothing to clear.'],
  ])('keeps the API sentence when %s is refused', async (_, call, status, error) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error }), { status })))

    await expect(call()).rejects.toThrow(error)
  })

  test('falls back to a sentence when the refusal carries none', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })))

    await expect(makeCurrent('x')).rejects.toThrow("Le scénario n'a pas été rendu courant.")
  })
})

import { describe, expect, test } from 'vitest'
import { MAX_LANES, placeEvents, yearTicks } from './timelineLayout'
import type { TimelineEvent } from './timelineApi'

const at = (id: string, month: string): TimelineEvent => ({
  id,
  month,
  type: 'project_purchase',
  category: 'projet',
  label: id,
  sublabel: null,
  amount_eur: null,
  monthly_impact_eur: null,
  n_impact_months: null,
  detail: {},
})

describe('placeEvents', () => {
  test('puts an event at its month of the 240', () => {
    const [first, last] = placeEvents([at('b', '2046-09'), at('a', '2026-10')], '2026-10', 240)

    expect(first).toMatchObject({ index: 0, lane: 0 })
    expect(first.percent).toBeCloseTo(0.5 / 2.4)
    expect(last).toMatchObject({ index: 239, lane: 0 })
    expect(last.percent).toBeCloseTo(99.79, 1)
  })

  test('stacks events of the same period and reuses a lane once clear', () => {
    const placed = placeEvents([at('a', '2027-01'), at('b', '2027-02'), at('c', '2027-03'), at('d', '2028-06')], '2026-10', 240)

    expect(placed.map((p) => p.lane)).toEqual([0, 1, 2, 0])
  })

  test('never uses more lanes than the frise draws', () => {
    const crowd = Array.from({ length: 9 }, (_, i) => at(`e${i}`, '2027-01'))

    expect(Math.max(...placeEvents(crowd, '2026-10', 240).map((p) => p.lane))).toBe(MAX_LANES - 1)
  })

  test('keeps an event outside the horizon on the edge instead of off the axis', () => {
    const [placed] = placeEvents([at('late', '2050-01')], '2026-10', 240)

    expect(placed.index).toBe(239)
  })
})

describe('yearTicks', () => {
  test('starts at the first month and ticks each January inside the horizon', () => {
    const ticks = yearTicks('2026-10', 240)

    expect(ticks[0]).toEqual({ year: '2026', percent: 0 })
    expect(ticks[1]).toEqual({ year: '2027', percent: (3 / 240) * 100 })
    expect(ticks[ticks.length - 1].year).toBe('2046')
    expect(ticks).toHaveLength(21)
  })
})

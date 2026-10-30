import { describe, expect, test } from 'vitest'
import {
  chartPaths,
  columnLabel,
  columns,
  dailyValues,
  formatKg,
  formatPercent,
  periodStart,
  readStart,
  trendDelta,
  trendOf,
  weighingsOf,
} from './suiviSeries'
import type { Measured } from './suiviSeries'

// Wednesday 21 October 2026, 15:00 local.
const NOW = new Date(2026, 9, 21, 15, 0)

const at = (month: number, day: number, hour = 7, minute = 0, year = 2026) => new Date(year, month - 1, day, hour, minute).toISOString()
const weigh = (measuredAt: string, weightKg: number | null, extra: Partial<Measured> = {}): Measured => ({ measuredAt, weightKg, ...extra })

describe('the value of a day', () => {
  test('is the first weighing of the local day that is not excluded, with the body fat of that same weighing', () => {
    const daily = dailyValues([
      weigh(at(10, 21, 21), 73.0, { bodyFatPercent: 22.0 }),
      weigh(at(10, 21, 6), 90.0, { excluded: true, bodyFatPercent: 30.0 }),
      weigh(at(10, 21, 7, 12), 72.4, { bodyFatPercent: 21.3 }),
    ])

    expect(daily.get('2026-10-21')).toEqual({ weightKg: 72.4, bodyFatPercent: 21.3 })
  })

  test('skips a measurement without a weight, and a day stops at local midnight', () => {
    const daily = dailyValues([
      weigh(at(10, 20, 6), null, { bodyFatPercent: 22.0 }),
      weigh(at(10, 20, 23, 59), 72.8),
      weigh(at(10, 21, 0, 0), 72.6),
    ])

    expect(daily.get('2026-10-20')).toEqual({ weightKg: 72.8, bodyFatPercent: null })
    expect(daily.get('2026-10-21')?.weightKg).toBe(72.6)
  })
})

describe('the trend', () => {
  test('averages the weighed days among the day and the six before it', () => {
    const daily = dailyValues([weigh(at(10, 21), 72.0), weigh(at(10, 18), 73.0), weigh(at(10, 15), 74.0), weigh(at(10, 14), 99.0)])

    expect(trendOf(daily, new Date(2026, 9, 21))).toBeCloseTo(73.0)
  })

  test('never counts an excluded weighing', () => {
    const daily = dailyValues([weigh(at(10, 21), 72.0), weigh(at(10, 20), 95.0, { excluded: true })])

    expect(trendOf(daily, new Date(2026, 9, 21))).toBe(72.0)
    expect(trendOf(daily, new Date(2026, 9, 20))).toBeNull()
  })

  test('is none when no day of the window is weighed', () => {
    expect(trendOf(dailyValues([weigh(at(10, 10), 72.0)]), new Date(2026, 9, 21))).toBeNull()
  })
})

describe('the columns of a period', () => {
  test('7 j: seven days, today last as « Aujourd’hui »', () => {
    const cols = columns([], 7, NOW)

    expect(cols).toHaveLength(7)
    expect(cols[0].label).toBe('jeu. 15 oct.')
    expect(cols[5].label).toBe('mar. 20 oct.')
    expect(cols[6].label).toBe('Aujourd’hui')
  })

  test('30 j: thirty days, the axis from « 22 sept. » to « 21 oct. »', () => {
    const cols = columns([], 30, NOW)

    expect(cols).toHaveLength(30)
    expect(cols[0].short).toBe('22 sept.')
    expect(cols[29].short).toBe('21 oct.')
  })

  test('90 j: thirteen weeks over the 91 last days, the mean of their weighed days', () => {
    const cols = columns([weigh(at(10, 16), 72.0, { bodyFatPercent: 21.0 }), weigh(at(10, 20), 73.0)], 90, NOW)

    expect(cols).toHaveLength(13)
    expect(cols.every((column) => column.days.length === 7)).toBe(true)
    expect(cols[0].label).toBe('Sem. du 23 juil.')
    expect(cols[12].label).toBe('Sem. du 15 oct.')
    expect(cols[12].weightKg).toBeCloseTo(72.5)
    expect(cols[12].bodyFatPercent).toBe(21.0)
    expect(cols[11].weightKg).toBeNull()
  })

  test('1 an: one column per calendar month, « Novembre 2025 » to « Octobre 2026 »', () => {
    const cols = columns([], 365, NOW)

    expect(cols).toHaveLength(12)
    expect(cols[0].label).toBe('Novembre 2025')
    expect(cols[0].short).toBe('nov. 25')
    expect(cols[11].label).toBe('Octobre 2026')
    expect(cols[11].short).toBe('oct. 26')
    expect(cols[11].days).toHaveLength(21)
  })

  test('starts the read six days before the period, for the trend of its first day', () => {
    expect(periodStart(30, NOW).getTime()).toBe(new Date(2026, 8, 22).getTime())
    expect(readStart(30, NOW).getTime()).toBe(new Date(2026, 8, 16).getTime())
    expect(periodStart(365, NOW).getTime()).toBe(new Date(2025, 10, 1).getTime())
  })
})

describe('the labels', () => {
  test('French numbers: one decimal and a comma, « — » when nothing', () => {
    expect(formatKg(72.4)).toBe('72,4 kg')
    expect(formatKg(72)).toBe('72,0 kg')
    expect(formatKg(null)).toBe('—')
    expect(formatPercent(21.3)).toBe('21,3 %')
  })

  test('the accessible name of a column', () => {
    const cols = columns([weigh(at(10, 21), 72.4), weigh(at(10, 20), 71.8)], 7, NOW)

    expect(columnLabel(cols[6])).toBe('Aujourd’hui : 72,4 kg, tendance 72,1 kg')
    expect(columnLabel(cols[0])).toBe('jeu. 15 oct. : pas de pesée, tendance —')
  })

  test('the trend delta over the period, « — » when the first column has no trend', () => {
    expect(trendDelta(columns([weigh(at(10, 15), 73.6), weigh(at(10, 21), 72.4)], 7, NOW))).toBe('−0,6 kg')
    expect(trendDelta(columns([weigh(at(10, 15), 72.0), weigh(at(10, 21), 73.0)], 7, NOW))).toBe('+0,5 kg')
    expect(trendDelta(columns([weigh(at(10, 21), 72.4)], 7, NOW))).toBe('—')
  })
})

describe('the chart', () => {
  test('dots for the weighed columns only when asked, a line through the trend', () => {
    const cols = columns([weigh(at(10, 19), 72.0), weigh(at(10, 21), 73.0)], 7, NOW)

    const withDots = chartPaths(cols, true)
    expect(withDots.dots.match(/M/g)).toHaveLength(2)
    expect(withDots.line.startsWith('M')).toBe(true)
    expect(withDots.line.match(/L/g)).toHaveLength(2)
    expect(chartPaths(cols, false).dots).toBe('')
    expect(chartPaths(columns([], 7, NOW), true)).toEqual({ line: '', dots: '' })
  })
})

describe('the weighings of the period', () => {
  test('newest first, the excluded ones kept, nothing before the period', () => {
    const listed = [
      weigh(at(10, 18), 72.8),
      weigh(at(10, 21), 72.4, { excluded: true }),
      weigh(at(10, 14), 73.1),
      weigh(at(9, 20), 74.0),
    ]

    expect(weighingsOf(listed, 7, NOW).map((weighing) => weighing.weightKg)).toEqual([72.4, 72.8])
    expect(weighingsOf(listed, 30, NOW)).toHaveLength(3)
  })
})

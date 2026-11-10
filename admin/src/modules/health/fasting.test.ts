import { describe, expect, test } from 'vitest'
import { clockLabel, fastBars, fastHours, fastingStatus, fastScale, nightFastLabel, windowLabel } from './fasting'
import type { FastingDay } from './fasting'
import type { HealthDay } from './healthDaysApi'

const DAY: FastingDay = {
  date: '2026-10-21',
  protocol: '16:8',
  windowStart: '12:00',
  windowEnd: '20:00',
  openedAt: '2026-10-21T12:10:00+02:00',
  lastIntakeAt: '2026-10-21T12:10:00+02:00',
  state: 'open',
  nightFastMinutes: 965,
}

describe('fasting', () => {
  test('a protocol fasts its first figure', () => {
    expect(fastHours('14:10')).toBe(14)
    expect(fastHours('16:8')).toBe(16)
    expect(fastHours('20:4')).toBe(20)
  })

  test('times and durations read as the mockup', () => {
    expect(clockLabel('12:10')).toBe('12 h 10')
    expect(clockLabel('08:00')).toBe('8 h 00')
    expect(nightFastLabel(965)).toBe('16 h 05')
    expect(nightFastLabel(null)).toBe('—')
    expect(windowLabel(DAY)).toBe('12 h 10 → 20 h 00')
    expect(windowLabel({ ...DAY, openedAt: null })).toBe('12 h 00 → 20 h 00')
  })

  test.each([
    ['17:40', 'open', 'fenêtre ouverte · 2 h 20 restantes'],
    ['19:30', 'open', 'fenêtre ouverte · 30 min restantes'],
    ['10:50', 'fasting', 'jeûne en cours · 1 h 10 avant l’ouverture'],
    ['13:00', 'fasting', 'fenêtre ouverte · 7 h 00 restantes'],
    ['21:00', 'closed', 'fenêtre fermée'],
  ] as const)('at %s, %s says « %s »', (now, state, status) => {
    expect(fastingStatus({ ...DAY, state }, now)).toBe(status)
  })

  test('a window past midnight counts down across it', () => {
    const late: FastingDay = { ...DAY, protocol: '20:4', windowStart: '22:00', windowEnd: '02:00' }
    expect(fastingStatus(late, '23:00')).toBe('fenêtre ouverte · 3 h 00 restantes')
    expect(fastingStatus(late, '21:00')).toBe('jeûne en cours · 1 h 00 avant l’ouverture')
  })

  test('another day has no countdown, no protocol no status', () => {
    expect(fastingStatus(DAY, null)).toBe('fenêtre ouverte')
    expect(fastingStatus({ ...DAY, state: 'fasting' }, null)).toBe('jeûne en cours')
    expect(fastingStatus({ ...DAY, protocol: null, state: null }, '12:00')).toBe('')
  })

  test('14 bars ending on the day, a day the journal does not hold has none', () => {
    const bars = fastBars(
      [
        { date: '2026-10-21', nightFastMinutes: 965 } as HealthDay,
        { date: '2026-10-08', nightFastMinutes: 1300 } as HealthDay,
      ],
      '2026-10-21',
    )
    expect(bars).toHaveLength(14)
    expect(bars[0]).toEqual({ date: '2026-10-08', minutes: 1300 })
    expect(bars[1]).toEqual({ date: '2026-10-09', minutes: null })
    expect(bars[13]).toEqual({ date: '2026-10-21', minutes: 965 })
    expect(fastScale(bars, '16:8')).toBe(22)
    expect(fastScale(bars.slice(1), '16:8')).toBe(20)
  })
})

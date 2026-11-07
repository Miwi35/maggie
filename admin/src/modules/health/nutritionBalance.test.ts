import { describe, expect, test } from 'vitest'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { balanceZone, formatSignedKcal, shiftDay, weekRange, zoneColor } from './nutritionBalance'

describe('balanceZone', () => {
  test.each([
    [100, 'balanced'],
    [150, 'balanced'],
    [-150, 'balanced'],
    [0, 'balanced'],
    [151, 'surplus'],
    [-151, 'deficit'],
    [null, null],
  ])('%s kcal is %s', (balance, zone) => {
    expect(balanceZone(balance)).toBe(zone)
  })
})

describe('zoneColor', () => {
  test.each([veilleuseLightTheme, veilleuseDarkTheme])('no zone takes an alert colour', (theme) => {
    const alerts = [theme.palette.error.main, theme.palette.warning.main, theme.palette.error.light, theme.palette.warning.light]
    for (const zone of ['balanced', 'surplus', 'deficit', null] as const) {
      expect(alerts).not.toContain(zoneColor(theme, zone))
    }
    expect(zoneColor(theme, 'balanced')).toBe(theme.palette.text.secondary)
  })
})

describe('formatSignedKcal', () => {
  test('signs the figure with a plus or a real minus', () => {
    expect(formatSignedKcal(320)).toBe('+320 kcal')
    expect(formatSignedKcal(-480)).toBe('−480 kcal')
    expect(formatSignedKcal(0)).toBe('0 kcal')
    expect(formatSignedKcal(1234)).toMatch(/^\+1\s234 kcal$/)
  })
})

describe('days', () => {
  test('the 7 days end on the day shown, across a month', () => {
    expect(weekRange('2026-11-03')).toEqual({ from: '2026-10-28', to: '2026-11-03' })
    expect(shiftDay('2026-10-31', 1)).toBe('2026-11-01')
  })
})

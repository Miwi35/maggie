import { describe, test, expect } from 'vitest'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { moduleColor } from './moduleColor'

describe('moduleColor', () => {
  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('the Agenda card wears module.agenda, not the travail green (%s)', (_mode, theme) => {
    expect(moduleColor(theme, 'agenda')).toBe(theme.palette.module.agenda)
    expect(moduleColor(theme, 'agenda')).not.toBe(theme.palette.module.travail)
  })
})

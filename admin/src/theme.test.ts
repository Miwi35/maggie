import { describe, test, expect } from 'vitest'
import { NARROW_QUERY } from './breakpoints'
import { lightTheme, darkTheme } from './theme'

/**
 * Two things about the theme that no screen test would catch (MAG-38).
 *
 * Both are about the merge. `createTheme(radiantLightTheme, responsiveOptions)`
 * deep-merges, and MUI's deepmerge replaces a key outright whenever the
 * incoming value is not a plain object — so a `styleOverrides` written as a
 * function takes a slot radiant already styles and drops its rule without a
 * word. That is a layout change at *every* width, from a file whose whole
 * point is to leave the desk alone.
 */
describe('the admin theme', () => {
  test.each([
    ['light', lightTheme],
    ['dark', darkTheme],
  ])('answers the same `md` the app hard-codes (%s)', (_name, theme) => {
    // `useNarrowScreen` asks `matchMedia` for NARROW_QUERY while the modules
    // ask the theme for `down('md')`. Nothing makes them agree but this.
    expect(theme.breakpoints.down('md')).toBe(`@media ${NARROW_QUERY}`)
  })

  test.each([
    ['light', lightTheme],
    ['dark', darkTheme],
  ])('keeps radiant’s own app-frame margin beside the narrow rules (%s)', (_name, theme) => {
    const root = theme.components?.RaLayout?.styleOverrides?.root as Record<
      string,
      Record<string, unknown>
    >

    // Radiant sets this; losing it falls back to react-admin's larger default
    // and every page gains dead space under the app bar, desk included.
    expect(root['& .RaLayout-appFrame']).toBeDefined()
    expect(root[`@media ${NARROW_QUERY}`]).toMatchObject({
      minWidth: 0,
      '& .RaLayout-content': { minWidth: 0 },
    })
  })
})

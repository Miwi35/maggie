import { describe, test, expect } from 'vitest'
import { NARROW_QUERY } from './breakpoints'
import { TOKENS } from './design/tokens'
import { lightTheme, darkTheme } from './theme'

import type { Theme } from '@mui/material/styles'

const MODES: [name: 'light' | 'dark', theme: Theme][] = [
  ['light', lightTheme],
  ['dark', darkTheme],
]

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

/**
 * The identity, in both modes (MAG-39).
 *
 * Radiant already carried the violet and Gabarito, so most of what is asserted
 * here is unchanged pixels under a name. Asserting it anyway is the point: the
 * values now come from `design/tokens.json`, the mobile theme reads the same
 * file, and a react-admin upgrade that moves radiant's palette has to fail here
 * rather than on a screen the owner notices weeks later.
 */
describe('the identity the admin is drawn in', () => {
  test.each(MODES)('takes its brand colours from the tokens (%s)', (mode, theme) => {
    expect(theme.palette.primary.main).toBe(TOKENS.brand.primary)
    expect(theme.palette.primary.contrastText).toBe(TOKENS.brand.onPrimary)
    expect(theme.palette.secondary.main).toBe(
      mode === 'light' ? TOKENS.brand.secondaryLight : TOKENS.brand.secondaryDark,
    )
  })

  test.each(MODES)('takes its surfaces and its text from the mode’s tokens (%s)', (mode, theme) => {
    const surface = TOKENS.surface[mode]

    expect(theme.palette.mode).toBe(mode)
    expect(theme.palette.background.default).toBe(surface.background)
    expect(theme.palette.background.paper).toBe(surface.paper)
    expect(theme.palette.text.primary).toBe(surface.text)
    expect(theme.palette.text.secondary).toBe(surface.textMuted)
  })

  test.each(MODES)('keeps MUI’s alert roles on the feedback family (%s)', (_mode, theme) => {
    // Radiant's acid palette. It is a token so that an upgrade cannot change it
    // silently; the data colours are `signal`, and are nobody's `palette.error`.
    expect(theme.palette.error.main).toBe(TOKENS.feedback.error)
    expect(theme.palette.warning.main).toBe(TOKENS.feedback.warning)
    expect(theme.palette.info.main).toBe(TOKENS.feedback.info)
    expect(theme.palette.success.main).toBe(TOKENS.feedback.success)
  })

  test.each(MODES)('writes in Gabarito, at the shared sizes (%s)', (_mode, theme) => {
    const { family, size } = TOKENS.typography

    expect(theme.typography.fontFamily).toBe(family)
    // The eight variants the admin uses, in `rem` against a 16px root — MUI's
    // own unit. `body2` alone is 85 of the usages.
    expect(theme.typography.h5.fontSize).toBe(`${size.xxl / 16}rem`)
    expect(theme.typography.h6.fontSize).toBe(`${size.xl / 16}rem`)
    expect(theme.typography.subtitle1.fontSize).toBe(`${size.lg / 16}rem`)
    expect(theme.typography.subtitle2.fontSize).toBe(`${size.md / 16}rem`)
    expect(theme.typography.body1.fontSize).toBe(`${size.lg / 16}rem`)
    expect(theme.typography.body2.fontSize).toBe(`${size.md / 16}rem`)
    expect(theme.typography.caption.fontSize).toBe(`${size.sm / 16}rem`)
    expect(theme.typography.overline.fontSize).toBe(`${size.sm / 16}rem`)
  })

  test.each(MODES)('never asks for a weight the page does not load (%s)', (_mode, theme) => {
    // `index.html` loads `wght@400;500;600;700`; radiant asked `h4` for 800 and
    // `h5` for 900, and the browser synthesised both — the nine `variant="h5"`
    // headings in the admin were drawn in faux-bold. The token file's weights
    // are checked against the link element in `design/tokens.contract.test.ts`.
    const loadable: number[] = Object.values(TOKENS.typography.weight)
    const variants = [
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'subtitle1', 'subtitle2', 'body1', 'body2', 'caption', 'overline', 'button',
    ] as const

    for (const variant of variants) {
      const asked = Number(theme.typography[variant].fontWeight)
      expect(loadable, `${variant} asks for weight ${asked}`).toContain(asked)
    }

    expect(theme.typography.h5.fontWeight).toBe(TOKENS.typography.weight.bold)
  })

  test.each(MODES)('rounds corners by the shared radius (%s)', (_mode, theme) => {
    expect(theme.shape.borderRadius).toBe(TOKENS.radius.sm)
  })
})

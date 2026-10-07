import { describe, test, expect } from 'vitest'
import { alpha } from '@mui/material/styles'
import { NARROW_QUERY } from './breakpoints'
import { TOKENS } from './design/tokens'
import { veilleuseDarkTheme, veilleuseLightTheme } from './theme'

import type { Theme } from '@mui/material/styles'

type Mode = 'light' | 'dark'

const MODES: [name: Mode, theme: Theme][] = [
  ['light', veilleuseLightTheme],
  ['dark', veilleuseDarkTheme],
]

const channel = (value: number) => {
  const c = value / 255
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

const luminance = (hex: string) => {
  const n = parseInt(hex.slice(1), 16)
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255)
}

/** WCAG 2.x contrast ratio of two opaque `#RRGGBB` colours. */
const contrast = (a: string, b: string) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** Flattens `rgba(r, g, b, a)` laid over an opaque hex into an opaque hex. */
const over = (rgba: string, base: string) => {
  const [r, g, b, a] = rgba.match(/[\d.]+/g)!.map(Number)
  const n = parseInt(base.slice(1), 16)
  const mix = (fg: number, bg: number) => Math.round(fg * a + bg * (1 - a))
  const hex = (v: number) => v.toString(16).padStart(2, '0')
  return `#${hex(mix(r, (n >> 16) & 255))}${hex(mix(g, (n >> 8) & 255))}${hex(mix(b, n & 255))}`
}

/**
 * Two things about the merge that no screen test would catch (MAG-38).
 *
 * `createTheme(radiant, options)` deep-merges, and MUI's deepmerge replaces a
 * key outright whenever the incoming value is not a plain object — so a
 * `styleOverrides` written as a function takes a slot radiant already styles
 * and drops its rule without a word.
 */
describe('the admin theme', () => {
  test.each(MODES)('answers the same `md` the app hard-codes (%s)', (_name, theme) => {
    expect(theme.breakpoints.down('md')).toBe(`@media ${NARROW_QUERY}`)
  })

  test.each(MODES)('keeps radiant’s own app-frame margin beside the narrow rules (%s)', (_name, theme) => {
    const root = theme.components?.RaLayout?.styleOverrides?.root as Record<string, Record<string, unknown>>

    expect(root['& .RaLayout-appFrame']).toBeDefined()
    expect(root[`@media ${NARROW_QUERY}`]).toMatchObject({
      minWidth: 0,
      '& .RaLayout-content': { minWidth: 0 },
    })
  })

  test.each(MODES)('styles radiant’s slots with objects, never a function (%s)', (_name, theme) => {
    const slots = [
      'MuiPaper',
      'MuiAppBar',
      'MuiTableCell',
      'RaDatagrid',
      'RaMenuItemLink',
      'RaToolbar',
      'RaLayout',
    ] as const
    for (const slot of slots) {
      const overrides = theme.components?.[slot]?.styleOverrides as Record<string, unknown> | undefined
      expect(overrides, slot).toBeDefined()
      for (const [key, rule] of Object.entries(overrides!)) {
        expect(typeof rule, `${slot}.${key}`).not.toBe('function')
      }
    }
  })
})

/** The identity in both modes (MAG-311): the theme reads `design/tokens.json`, it keeps no copy. */
describe('the Veilleuse identity', () => {
  test.each(MODES)('has the right mode (%s)', (name, theme) => {
    expect(theme.palette.mode).toBe(name)
  })

  test.each(MODES)('draws the night, the cards and the ink from the tokens (%s)', (name, theme) => {
    const surface = TOKENS.surface[name]
    expect(theme.palette.background.default).toBe(surface.background)
    expect(theme.palette.background.paper).toBe(surface.paper)
    expect(theme.palette.text.primary).toBe(surface.text)
    expect(theme.palette.text.secondary).toBe(surface.textMuted)
    expect(theme.palette.veilleuse.raised).toBe(surface.raised)
    expect(theme.palette.veilleuse.caption).toBe(surface.caption)
  })

  test.each(MODES)('has one accent, and the second role is the first (%s)', (name, theme) => {
    const accent = name === 'dark' ? TOKENS.brand.primary : TOKENS.brand.primaryLight
    expect(theme.palette.primary.main).toBe(accent)
    expect(theme.palette.secondary.main).toBe(accent)
  })

  test.each(MODES)('exposes one hue per module (%s)', (name, theme) => {
    for (const [module, hue] of Object.entries(TOKENS.module)) {
      expect(theme.palette.module[module as keyof typeof TOKENS.module]).toBe(hue[name])
    }
  })

  test.each(MODES)('takes feedback colours of the mode (%s)', (name, theme) => {
    expect(theme.palette.error.main).toBe(TOKENS.feedback[name].error)
    expect(theme.palette.success.main).toBe(TOKENS.feedback[name].success)
  })

  test.each(MODES)('is set in Geist, with tabular figures, and never above semibold (%s)', (_name, theme) => {
    expect(theme.typography.fontFamily).toBe(TOKENS.typography.family)
    const styles = theme.components?.MuiCssBaseline?.styleOverrides as Record<string, Record<string, string>>
    expect(styles.body.fontVariantNumeric).toBe('tabular-nums')
    const weights = Object.values(TOKENS.typography.weight)
    for (const variant of ['h5', 'h6', 'subtitle2', 'body2', 'button'] as const) {
      expect(weights, variant).toContain(theme.typography[variant].fontWeight)
    }
  })

  test.each(MODES)('has pills and rounded fields (%s)', (_name, theme) => {
    expect(theme.shape.borderRadius).toBe(TOKENS.radius.sm)
    const button = theme.components?.MuiButton?.styleOverrides?.root as Record<string, unknown>
    expect(button.borderRadius).toBe(TOKENS.radius.pill)
    expect(theme.components?.MuiChip?.styleOverrides?.root).toMatchObject({ borderRadius: TOKENS.radius.pill })
  })

  test.each(MODES)('keeps the 44px touch targets on a phone (%s)', (_name, theme) => {
    const media = `@media ${NARROW_QUERY}`
    const button = theme.components?.MuiButton?.styleOverrides?.root as Record<string, Record<string, number>>
    const icon = theme.components?.MuiIconButton?.styleOverrides?.root as Record<string, Record<string, number>>
    expect(button[media].minHeight).toBe(44)
    expect(icon[media]).toMatchObject({ minWidth: 44, minHeight: 44 })
  })
})

/** The acceptance bar: 4.5:1 for text, in both modes, on every surface text lands on. */
describe('contrast', () => {
  const AA = 4.5

  describe.each(MODES)('%s', (name, theme) => {
    const surface = TOKENS.surface[name]
    const accent = theme.palette.primary.main
    const grounds = { background: surface.background, paper: surface.paper, raised: surface.raised }

    test.each(Object.entries(grounds))('text, muted text and caption read on %s', (_ground, bg) => {
      expect(contrast(surface.text, bg)).toBeGreaterThanOrEqual(AA)
      expect(contrast(surface.textMuted, bg)).toBeGreaterThanOrEqual(AA)
      expect(contrast(surface.caption, bg)).toBeGreaterThanOrEqual(AA)
    })

    test.each(Object.entries(grounds))('the accent reads on %s', (_ground, bg) => {
      expect(contrast(accent, bg)).toBeGreaterThanOrEqual(AA)
    })

    test.each(Object.entries(grounds))('every module hue reads on %s', (_ground, bg) => {
      for (const [module, hue] of Object.entries(theme.palette.module)) {
        expect(contrast(hue, bg), module).toBeGreaterThanOrEqual(AA)
      }
    })

    test.each(Object.entries(grounds))('every feedback colour reads on %s', (_ground, bg) => {
      for (const colour of ['error', 'warning', 'info', 'success'] as const) {
        expect(contrast(theme.palette[colour].main, bg), colour).toBeGreaterThanOrEqual(AA)
      }
    })

    test('the label on the accent reads', () => {
      expect(contrast(theme.palette.primary.contrastText, accent)).toBeGreaterThanOrEqual(AA)
      expect(contrast(theme.palette.primary.contrastText, theme.palette.primary.light)).toBeGreaterThanOrEqual(AA)
    })

    test('the principal button’s label reads, at rest and hovered', () => {
      expect(contrast(surface.background, surface.text)).toBeGreaterThanOrEqual(AA)
    })

    test('the active menu entry — the accent at 16 % over the page — reads', () => {
      const tint = over(alpha(accent, 0.16), surface.background)
      const active = (
        theme.components?.RaMenuItemLink?.styleOverrides?.root as Record<string, Record<string, unknown>>
      )['&.RaMenuItemLink-active']
      expect(contrast(active.color as string, tint)).toBeGreaterThanOrEqual(AA)
      expect(contrast(accent, tint)).toBeGreaterThanOrEqual(3)
    })

    test('Maggie’s bubble and the chat reply read', () => {
      expect(contrast(surface.text, theme.palette.maggie.bubble)).toBeGreaterThanOrEqual(AA)
      expect(contrast(surface.text, theme.palette.maggie.reply)).toBeGreaterThanOrEqual(AA)
      expect(contrast(surface.text, theme.palette.maggie.panel)).toBeGreaterThanOrEqual(AA)
    })
  })

  test('the interruption’s header — Maggie’s violet on its bubble — reads in the dark', () => {
    expect(contrast(TOKENS.maggie.avatarFrom, TOKENS.maggie.bubble.dark)).toBeGreaterThanOrEqual(AA)
  })
})

import { alpha, createTheme } from '@mui/material/styles'
import { radiantDarkTheme, radiantLightTheme } from 'react-admin'
import { NARROW_QUERY } from './breakpoints'
import { TOKENS } from './design/tokens'
import type { ThemeMode } from './design/tokens'
import type { ThemeOptions } from '@mui/material/styles'

/*
 * « Veilleuse » (MAG-311) — the identity of the activity screens, worn by a
 * work tool. The admin takes the colours, the typeface, the shapes and the one
 * accent; it keeps its own density, its own layout and its own responsive
 * rules. Nothing here makes a row taller, a page narrower or a text bigger than
 * radiant had them: `agent-os/standards/frontend/veilleuse.md`.
 *
 * Every value is read from `design/tokens.json` (through `design/tokens.ts`),
 * which the mobile theme reads too.
 *
 * `createTheme(radiant…, these)` deep-merges, and MUI's deepmerge *replaces*
 * whenever the incoming value is not a plain object. So an override may only
 * be written as a function on a slot radiant leaves alone: on a slot it
 * already styles — `MuiPaper` (`root`, `elevation1`), `MuiAppBar`
 * (`colorSecondary`), `MuiButton` (`sizeSmall`), `MuiTableCell` (`root`),
 * `MuiTableRow` (`root`), `RaDatagrid`, `RaFilterForm`, `RaLayout`,
 * `RaMenuItemLink`, `RaToolbar`, `RaBulkActionsToolbar` — a function takes the
 * key whole and silently drops radiant's rule. Those slots are plain objects
 * here, and `theme.test.ts` keeps it so.
 */

declare module '@mui/material/styles' {
  interface Palette {
    /** One hue per module, of the mode's own lightness. */
    module: Record<ModuleName, string>
    /** What Maggie's own surfaces — the interruption, the chat — are drawn with. */
    maggie: { avatarFrom: string; avatarTo: string; bubble: string; panel: string; reply: string }
    /** The surfaces and inks the MUI palette has no role for. */
    veilleuse: { raised: string; track: string; caption: string; ink: string; onInk: string }
  }
  interface PaletteOptions {
    module?: Record<ModuleName, string>
    maggie?: { avatarFrom: string; avatarTo: string; bubble: string; panel: string; reply: string }
    veilleuse?: { raised: string; track: string; caption: string; ink: string; onInk: string }
  }
}

export type ModuleName = keyof typeof TOKENS.module

/**
 * The smallest a control may be when a finger is the pointer — WCAG 2.5.5's
 * 44px, not Material's 48dp: the app bar carries five controls next to a
 * search field at 393px, and 48 would not fit. Keyed on the width rather than
 * on `pointer: coarse`, because the width is what a journey can set.
 */
const TOUCH_TARGET = 44

const NARROW = `@media ${NARROW_QUERY}`

const { family, mono, weight, size } = TOKENS.typography
const { radius, motion } = TOKENS

/** MUI sizes type in `rem` against a 16px root, which is what its own defaults are. */
const rem = (px: number) => `${px / 16}rem`

const dividerOf = (mode: ThemeMode) =>
  mode === 'dark' ? `rgba(255, 255, 255, ${TOKENS.divider.dark})` : `rgba(0, 0, 0, ${TOKENS.divider.light})`

const accentOf = (mode: ThemeMode) => (mode === 'dark' ? TOKENS.brand.primary : TOKENS.brand.primaryLight)
const accentHoverOf = (mode: ThemeMode) =>
  mode === 'dark' ? TOKENS.brand.primaryHover : TOKENS.brand.primaryHoverLight
const onAccentOf = (mode: ThemeMode) => (mode === 'dark' ? TOKENS.brand.onPrimary : TOKENS.brand.onPrimaryLight)

const moduleHues = (mode: ThemeMode) =>
  Object.fromEntries(Object.entries(TOKENS.module).map(([name, hue]) => [name, hue[mode]])) as Record<
    ModuleName,
    string
  >

const paletteFor = (mode: ThemeMode): ThemeOptions['palette'] => {
  const surface = TOKENS.surface[mode]
  const feedback = TOKENS.feedback[mode]
  const accent = accentOf(mode)

  return {
    mode,
    // One accent, no secondary colour: the second role is the first.
    primary: { main: accent, light: accentHoverOf(mode), contrastText: onAccentOf(mode) },
    secondary: { main: accent, light: accentHoverOf(mode), contrastText: onAccentOf(mode) },
    background: { default: surface.background, paper: surface.paper },
    text: { primary: surface.text, secondary: surface.textMuted, disabled: surface.caption },
    divider: dividerOf(mode),
    action: {
      // The raised surface is what a hovered row or a field is drawn on.
      hover: surface.raised,
      selected: alpha(accent, 0.16),
      focus: alpha(accent, 0.24),
    },
    error: { main: feedback.error },
    warning: { main: feedback.warning },
    info: { main: feedback.info },
    success: { main: feedback.success },
    module: moduleHues(mode),
    maggie: {
      avatarFrom: TOKENS.maggie.avatarFrom,
      avatarTo: TOKENS.maggie.avatarTo,
      bubble: TOKENS.maggie.bubble[mode],
      panel: TOKENS.maggie.panel[mode],
      reply: TOKENS.maggie.reply[mode],
    },
    veilleuse: {
      raised: surface.raised,
      track: surface.track,
      caption: surface.caption,
      // The principal button: a light pill on the night, a dark one on ivory.
      ink: surface.text,
      onInk: surface.background,
    },
  }
}

/*
 * The six sizes onto the eight variants the admin uses — `body2` 85 times,
 * `caption` 42, `subtitle2` 20, `h6` 13, `h5` 9, then `subtitle1`, `overline`
 * and `body1`. Line heights are left to MUI: Material 3 ships its own, within
 * a few percent, and overriding either would re-flow every screen for nothing.
 * Geist is loaded at 300–600 and nothing asks for more (`h4` and `h5` were
 * synthesised bold under Gabarito, MAG-39).
 */
const typographyFor = (mode: ThemeMode): ThemeOptions['typography'] => ({
  fontFamily: family,
  fontWeightLight: weight.light,
  fontWeightRegular: weight.regular,
  fontWeightMedium: weight.medium,
  fontWeightBold: weight.semibold,
  h1: { fontWeight: weight.semibold },
  h2: { fontWeight: weight.semibold },
  h3: { fontWeight: weight.semibold },
  h4: { fontWeight: weight.semibold },
  h5: { fontSize: rem(size.xxl), fontWeight: weight.semibold },
  h6: { fontSize: rem(size.xl), fontWeight: weight.semibold },
  subtitle1: { fontSize: rem(size.lg), fontWeight: weight.regular },
  subtitle2: { fontSize: rem(size.md), fontWeight: weight.medium },
  body1: { fontSize: rem(size.lg), fontWeight: weight.regular },
  body2: { fontSize: rem(size.md), fontWeight: weight.regular },
  caption: { fontSize: rem(size.sm), fontWeight: weight.regular },
  // A section's legend: small, spaced capitals in the caption colour.
  overline: {
    fontSize: rem(size.sm),
    fontWeight: weight.medium,
    letterSpacing: '0.08em',
    textTransform: 'uppercase',
    color: TOKENS.surface[mode].caption,
  },
  button: { fontWeight: weight.medium, textTransform: 'none' },
})

/** `0 0 0 2px` of the page, then `0 0 0 4px` of the accent. */
const focusRing = (mode: ThemeMode) =>
  `0 0 0 2px ${TOKENS.surface[mode].background}, 0 0 0 4px ${accentOf(mode)}`

const componentsFor = (mode: ThemeMode): ThemeOptions['components'] => {
  const surface = TOKENS.surface[mode]
  const accent = accentOf(mode)
  const divider = dividerOf(mode)
  const ink = alpha(surface.text, 0.08)
  const inkHover = alpha(surface.text, 0.14)
  const popoverShadow =
    mode === 'dark' ? '0 12px 32px rgba(0, 0, 0, 0.45)' : '0 12px 32px rgba(27, 24, 38, 0.16)'

  return {
    MuiCssBaseline: {
      styleOverrides: {
        // Tabular figures everywhere: amounts, dates and times line up in a
        // column without any screen asking for it.
        body: { fontVariantNumeric: 'tabular-nums', fontFeatureSettings: '"tnum"' },
        'code, kbd, pre, samp': { fontFamily: mono },
      },
    },

    // --- The surfaces: cards detach by colour, not by border or shadow ---
    // Radiant styles `root` and `elevation1` of `MuiPaper`: objects only.
    MuiPaper: {
      styleOverrides: {
        root: { backgroundClip: 'padding-box', backgroundImage: 'none' },
        elevation1: { boxShadow: 'none' },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: { borderRadius: radius.lg, boxShadow: 'none', border: 'none' },
      },
    },
    MuiPopover: {
      styleOverrides: {
        paper: {
          backgroundColor: surface.raised,
          borderRadius: radius.md,
          boxShadow: popoverShadow,
        },
      },
    },
    MuiAutocomplete: {
      styleOverrides: {
        paper: { backgroundColor: surface.raised, borderRadius: radius.md, boxShadow: popoverShadow },
      },
    },
    MuiBackdrop: {
      styleOverrides: {
        root: { backgroundColor: alpha(TOKENS.surface.dark.background, 0.6) },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: { boxShadow: 'none', borderBottom: `1px solid ${divider}` },
        colorSecondary: { backgroundColor: surface.background, color: surface.text },
        colorPrimary: { backgroundColor: surface.background, color: surface.text },
      },
    },
    MuiDivider: { styleOverrides: { root: { borderColor: divider } } },

    // A dialog keeps MUI's 32px margin on a desktop and gives it up on a phone:
    // at 393px those margins are a sixth of the screen, and every form in the
    // app — a meal, an event, a grocery item — is a dialog (MAG-38).
    MuiDialog: {
      styleOverrides: {
        paper: ({ theme }) => ({
          borderRadius: radius.xl,
          boxShadow: popoverShadow,
          [theme.breakpoints.down('sm')]: {
            margin: theme.spacing(1),
            width: `calc(100% - ${theme.spacing(2)})`,
            maxWidth: `calc(100% - ${theme.spacing(2)})`,
            maxHeight: `calc(100% - ${theme.spacing(2)})`,
          },
        }),
      },
    },

    // --- Controls: pills for buttons, chips and tabs; 12 for fields ---
    // Radiant makes `outlined` the default variant and styles `sizeSmall`, so
    // `sizeSmall` is left to it. `root` is nobody's, and takes both the shape
    // and the touch target in one function.
    MuiButtonBase: {
      styleOverrides: {
        root: { '&.Mui-focusVisible': { boxShadow: focusRing(mode) } },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: radius.pill,
          boxShadow: 'none',
          fontWeight: weight.medium,
          textTransform: 'none',
          '&:hover': { boxShadow: 'none' },
          [NARROW]: { minHeight: TOUCH_TARGET },
        },
        // The principal button: a light pill on the night.
        containedPrimary: {
          backgroundColor: surface.text,
          color: surface.background,
          '&:hover': { backgroundColor: accentHoverOf(mode), color: onAccentOf(mode) },
        },
        // The secondary one: a translucent pill, no border.
        outlined: { border: 'none', backgroundColor: ink, '&:hover': { border: 'none', backgroundColor: inkHover } },
        outlinedPrimary: {
          color: surface.text,
          border: 'none',
          backgroundColor: ink,
          '&:hover': { border: 'none', backgroundColor: inkHover },
        },
        textPrimary: { '&:hover': { backgroundColor: alpha(accent, 0.16) } },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: { [NARROW]: { minWidth: TOUCH_TARGET, minHeight: TOUCH_TARGET } },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { borderRadius: radius.pill, fontWeight: weight.medium },
        filled: { '&.MuiChip-colorDefault': { backgroundColor: surface.raised } },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: { minHeight: 36 },
        flexContainer: { gap: TOKENS.space.xs },
        indicator: { display: 'none' },
      },
    },
    MuiTab: {
      styleOverrides: {
        root: {
          minHeight: 36,
          borderRadius: radius.pill,
          textTransform: 'none',
          fontWeight: weight.medium,
          '&.Mui-selected': { backgroundColor: alpha(accent, 0.16), color: accent },
          [NARROW]: { minHeight: TOUCH_TARGET },
        },
      },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: radius.md,
          backgroundColor: surface.raised,
          '& .MuiOutlinedInput-notchedOutline': { borderColor: divider },
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: surface.caption },
          '&.Mui-focused .MuiOutlinedInput-notchedOutline': { borderColor: accent, borderWidth: 2 },
        },
      },
    },
    MuiMenuItem: {
      styleOverrides: { root: { [NARROW]: { minHeight: TOUCH_TARGET } } },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: { borderRadius: radius.md, [NARROW]: { minHeight: TOUCH_TARGET } },
      },
    },
    MuiCheckbox: {
      styleOverrides: { root: { [NARROW]: { padding: 10 } } },
    },

    // --- react-admin: radiant's slots, restated as objects ---
    // Density is radiant's: its cell padding stays, and is merged with the
    // separator colour rather than replaced.
    MuiTableCell: {
      styleOverrides: { root: { borderBottom: `1px solid ${divider}` } },
    },
    RaDatagrid: {
      styleOverrides: {
        root: {
          '& .RaDatagrid-headerCell': { color: surface.caption, fontWeight: weight.medium },
        },
      },
    },
    // The active entry is the accent at 16 % on a pill; radiant's violet
    // gradient, its white left border and its shadow are gone.
    RaMenuItemLink: {
      styleOverrides: {
        root: {
          borderLeft: 'none',
          borderRadius: radius.pill,
          '&:hover': { borderRadius: radius.pill, backgroundColor: surface.raised },
          '&.RaMenuItemLink-active': {
            borderLeft: 'none',
            borderRadius: radius.pill,
            backgroundImage: 'none',
            backgroundColor: alpha(accent, 0.16),
            boxShadow: 'none',
            // On ivory the accent at 16 % is 4.3:1 against itself; the label
            // takes the container ink, the icon keeps the accent.
            color: mode === 'dark' ? accent : TOKENS.brand.onContainerLight,
            '& .MuiListItemIcon-root > .MuiSvgIcon-root': { fill: accent },
          },
        },
      },
    },
    RaToolbar: {
      styleOverrides: { root: { backgroundColor: surface.raised } },
    },
    // react-admin sizes the frame to its content so a wide table does not get
    // cut off, and lets the content column grow with it. On a phone that is
    // how one 900px-wide datagrid widens the layout viewport — Chrome's
    // shrink-to-fit — and the whole app, app bar included, is drawn at
    // two-thirds size. Both lines are needed: the first stops the frame
    // growing, the second stops the flex item inside it refusing to shrink
    // below the table's width, which is what it was growing for. The page
    // then scrolls sideways within itself — `page-content` in `Layout`.
    RaLayout: {
      styleOverrides: {
        root: {
          [NARROW]: {
            minWidth: 0,
            '& .RaLayout-content': { minWidth: 0 },
          },
        },
      },
    },
  }
}

const veilleuseOptions = (mode: ThemeMode): ThemeOptions => ({
  palette: paletteFor(mode),
  typography: typographyFor(mode),
  shape: { borderRadius: radius.sm },
  transitions: {
    duration: { shorter: motion.fastMs, standard: motion.baseMs },
    // A spring of stiffness 300 and damping 30 is critically damped within a
    // percent; this curve is its CSS shape.
    easing: { easeOut: 'cubic-bezier(0.2, 0.9, 0.3, 1)' },
  },
  components: componentsFor(mode),
})

export const veilleuseLightTheme = createTheme(radiantLightTheme, veilleuseOptions('light'))
export const veilleuseDarkTheme = createTheme(radiantDarkTheme, veilleuseOptions('dark'))

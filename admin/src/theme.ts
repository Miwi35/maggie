import { createTheme } from '@mui/material/styles'
import { radiantDarkTheme, radiantLightTheme } from 'react-admin'
import { NARROW_QUERY } from './breakpoints'
import { TOKENS } from './design/tokens'
import type { ThemeMode } from './design/tokens'
import type { Theme, ThemeOptions } from '@mui/material/styles'

/*
 * The admin, drawn for the window it is in (MAG-38) — what changes below `md`,
 * for every component at once. The breakpoint itself, and why it is 900px,
 * lives in `src/breakpoints.ts`; `theme.test.ts` checks the two agree.
 *
 * `createTheme(radiant…, these)` deep-merges, and MUI's deepmerge *replaces*
 * whenever the incoming value is not a plain object. So an override may only
 * be written as a function on a slot radiant leaves alone: on a slot it
 * already styles, a function takes the key whole and silently drops its rule.
 * `RaLayout.styleOverrides.root` is the one such slot here, and it stays an
 * object for that reason.
 */

/**
 * The smallest a control may be when a finger is the pointer — WCAG 2.5.5's
 * 44px, not Material's 48dp: the app bar carries five controls next to a
 * search field at 393px, and 48 would not fit. Keyed on the width rather than
 * on `pointer: coarse`, because the width is what a journey can set.
 */
const TOUCH_TARGET = 44

const narrow = (theme: Theme) => theme.breakpoints.down('md')

/*
 * The identity (MAG-39). Radiant already carried most of it — the violet and
 * Gabarito are its own — so these options mostly *name* what was already on
 * screen; `design/tokens.json` is where the names live, and the mobile theme
 * reads the same file. Two values do change, both of them bugs:
 * `index.html` loads `wght@400;500;600;700` while radiant asks `h4` for 800
 * and `h5` for 900, so the browser synthesises those two.
 */
const paletteFor = (mode: ThemeMode): ThemeOptions['palette'] => {
  const surface = TOKENS.surface[mode]

  return {
    mode,
    primary: { main: TOKENS.brand.primary, contrastText: TOKENS.brand.onPrimary },
    secondary: {
      main: mode === 'light' ? TOKENS.brand.secondaryLight : TOKENS.brand.secondaryDark,
    },
    background: { default: surface.background, paper: surface.paper },
    text: { primary: surface.text, secondary: surface.textMuted },
    error: { main: TOKENS.feedback.error },
    warning: { main: TOKENS.feedback.warning },
    info: { main: TOKENS.feedback.info },
    success: { main: TOKENS.feedback.success },
  }
}

const { family, weight, size } = TOKENS.typography

/** MUI sizes type in `rem` against a 16px root, which is what its own defaults are. */
const rem = (px: number) => `${px / 16}rem`

/*
 * The six sizes onto the eight variants the admin uses — `body2` 85 times,
 * `caption` 42, `subtitle2` 20, `h6` 13, `h5` 9, then `subtitle1`, `overline`
 * and `body1`. Line heights are left to MUI: Material 3 ships its own, within
 * a few percent, and overriding either would re-flow every screen for nothing.
 */
const TYPOGRAPHY: ThemeOptions['typography'] = {
  fontFamily: family,
  h4: { fontWeight: weight.bold },
  h5: { fontSize: rem(size.xxl), fontWeight: weight.bold },
  h6: { fontSize: rem(size.xl) },
  subtitle1: { fontSize: rem(size.lg) },
  subtitle2: { fontSize: rem(size.md), fontWeight: weight.medium },
  body1: { fontSize: rem(size.lg) },
  body2: { fontSize: rem(size.md) },
  caption: { fontSize: rem(size.sm) },
  overline: { fontSize: rem(size.sm) },
}

const identityOptions = (mode: ThemeMode): ThemeOptions => ({
  palette: paletteFor(mode),
  typography: TYPOGRAPHY,
  shape: { borderRadius: TOKENS.radius.sm },
})

const responsiveOptions: ThemeOptions = {
  components: {
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
          [`@media ${NARROW_QUERY}`]: {
            minWidth: 0,
            '& .RaLayout-content': { minWidth: 0 },
          },
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: ({ theme }) => ({
          [narrow(theme)]: { minWidth: TOUCH_TARGET, minHeight: TOUCH_TARGET },
        }),
      },
    },
    MuiButton: {
      styleOverrides: {
        root: ({ theme }) => ({
          [narrow(theme)]: { minHeight: TOUCH_TARGET },
        }),
      },
    },
    MuiMenuItem: {
      styleOverrides: {
        root: ({ theme }) => ({
          [narrow(theme)]: { minHeight: TOUCH_TARGET },
        }),
      },
    },
    MuiListItemButton: {
      styleOverrides: {
        root: ({ theme }) => ({
          [narrow(theme)]: { minHeight: TOUCH_TARGET },
        }),
      },
    },
    MuiTab: {
      styleOverrides: {
        root: ({ theme }) => ({
          [narrow(theme)]: { minHeight: TOUCH_TARGET },
        }),
      },
    },
    MuiCheckbox: {
      styleOverrides: {
        root: ({ theme }) => ({
          [narrow(theme)]: { padding: 10 },
        }),
      },
    },
    // A dialog keeps MUI's 32px margin on a desktop and gives it up on a phone:
    // at 393px those margins are a sixth of the screen, and every form in the
    // app — a meal, an event, a grocery item — is a dialog.
    MuiDialog: {
      styleOverrides: {
        paper: ({ theme }) => ({
          [theme.breakpoints.down('sm')]: {
            margin: theme.spacing(1),
            width: `calc(100% - ${theme.spacing(2)})`,
            maxWidth: `calc(100% - ${theme.spacing(2)})`,
            maxHeight: `calc(100% - ${theme.spacing(2)})`,
          },
        }),
      },
    },
  },
}

export const lightTheme = createTheme(radiantLightTheme, identityOptions('light'), responsiveOptions)
export const darkTheme = createTheme(radiantDarkTheme, identityOptions('dark'), responsiveOptions)

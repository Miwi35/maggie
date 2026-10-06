import { createTheme } from '@mui/material/styles'
import { radiantDarkTheme, radiantLightTheme } from 'react-admin'
import { BREAKPOINTS } from './breakpoints'
import type { Theme, ThemeOptions } from '@mui/material/styles'

/*
 * The admin, drawn for the window it is in (MAG-38) — what changes below `md`,
 * for every component at once. The breakpoint itself, and why it is 900px,
 * lives in `src/breakpoints.ts`.
 */

/**
 * The smallest a control may be when a finger is the pointer — WCAG 2.5.5's
 * 44px, not Material's 48dp: the app bar carries five controls next to a search
 * field at 393px, and 48 would not fit.
 *
 * Keyed on the width rather than on `pointer: coarse` because the width is what
 * a journey can set: Playwright emulates touch, but whether Chromium then
 * answers `pointer: coarse` to a media query is not something a test should
 * have to bet on.
 */
const TOUCH_TARGET = 44

const narrow = (theme: Theme) => theme.breakpoints.down('md')

const responsiveOptions: ThemeOptions = {
  breakpoints: { values: { ...BREAKPOINTS } },
  components: {
    // react-admin sizes the frame to its content so a wide table does not get
    // cut off, and lets the content column grow with it. On a phone that is
    // how one 900px-wide datagrid widens the layout viewport — Chrome's
    // shrink-to-fit — and the whole app, app bar included, is drawn at
    // two-thirds size.
    //
    // Both are needed. `minWidth: 0` on the frame stops it growing; the same
    // on `RaLayout-content` stops the flex item inside it refusing to shrink
    // below the table's own width, which is what the frame was growing for.
    // The page then scrolls sideways within itself — see `page-content` in
    // `Layout`.
    RaLayout: {
      styleOverrides: {
        root: ({ theme }) => ({
          [narrow(theme)]: {
            minWidth: 0,
            '& .RaLayout-content': { minWidth: 0 },
          },
        }),
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

export const lightTheme = createTheme(radiantLightTheme, responsiveOptions)
export const darkTheme = createTheme(radiantDarkTheme, responsiveOptions)

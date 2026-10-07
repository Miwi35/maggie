import { render } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme } from '../theme'
import type { ReactElement } from 'react'
import type { Theme } from '@mui/material/styles'

/** The Veilleuse palette (`maggie`, `veilleuse`) only exists under its provider. */
export const renderWithTheme = (ui: ReactElement, theme: Theme = veilleuseDarkTheme) =>
  render(<ThemeProvider theme={theme}>{ui}</ThemeProvider>)

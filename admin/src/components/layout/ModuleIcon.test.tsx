import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import HomeIcon from '@mui/icons-material/Home'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { ModuleIcon } from './ModuleIcon'

describe('ModuleIcon', () => {
  test.each([
    ['dark', veilleuseDarkTheme],
    ['light', veilleuseLightTheme],
  ])('draws the icon in its module hue (%s)', (_name, theme) => {
    render(
      <ThemeProvider theme={theme}>
        <ModuleIcon module="cuisine">
          <HomeIcon data-testid="icon" />
        </ModuleIcon>
      </ThemeProvider>,
    )

    const box = screen.getByTestId('icon').parentElement!
    expect(box).toHaveAttribute('data-module', 'cuisine')
    expect(getComputedStyle(box).color).not.toBe('')
  })
})

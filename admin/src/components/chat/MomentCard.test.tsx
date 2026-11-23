import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { MomentCard } from './MomentCard'

const renderCard = (theme = veilleuseLightTheme) =>
  render(
    <MemoryRouter>
      <ThemeProvider theme={theme}>
        <MomentCard icon="play" title="Digest du matin" moment="mar. 27 oct. · 1:12" to="/digest?message=m-1" openLabel="Ouvrir" ariaLabel="Ouvrir le digest du matin" />
      </ThemeProvider>
    </MemoryRouter>,
  )

describe('MomentCard', () => {
  test('draws the title, the moment and Ouvrir', () => {
    renderCard()
    expect(screen.getByText('Digest du matin')).toBeInTheDocument()
    expect(screen.getByText('mar. 27 oct. · 1:12')).toBeInTheDocument()
    expect(screen.getByText('Ouvrir')).toBeInTheDocument()
  })

  test('Ouvrir goes to its link', () => {
    renderCard()
    expect(screen.getByRole('link', { name: 'Ouvrir le digest du matin' })).toHaveAttribute('href', '/digest?message=m-1')
  })

  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('renders in the %s theme', (_name, theme) => {
    renderCard(theme)
    expect(screen.getByTestId('moment-card')).toBeInTheDocument()
  })
})

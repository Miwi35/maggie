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
        <MomentCard icon="play" title="Digest du matin" moment="mar. 27 oct. · 1:12" to="/digest?message=m-1" openLabel="Ouvrir" ariaLabel="Ouvrir le digest du matin" messageId="m-1" />
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

  test("carries its message's handle and sits at the start, 88 % wide", () => {
    renderCard()
    const card = screen.getByTestId('moment-card')
    expect(card).toHaveAttribute('data-message-id', 'm-1')
    expect(card).toHaveStyle({ width: '88%', alignSelf: 'flex-start' })
  })

  test.each([
    ['light', veilleuseLightTheme],
    ['dark', veilleuseDarkTheme],
  ])('renders in the %s theme', (_name, theme) => {
    renderCard(theme)
    expect(screen.getByTestId('moment-card')).toBeInTheDocument()
  })

  test('the moon card draws its tinted disc', () => {
    render(
      <MemoryRouter>
        <ThemeProvider theme={veilleuseDarkTheme}>
          <MomentCard icon="moon" title="Bilan de la journée" moment="lun. 26 oct. · 5 étapes" to="/activites/bilan" openLabel="Ouvrir" />
        </ThemeProvider>
      </MemoryRouter>,
    )
    expect(screen.getByTestId('moment-card')).toHaveTextContent('Bilan de la journée')
    expect(screen.getByTestId('BedtimeOutlinedIcon')).toBeInTheDocument()
    expect(screen.queryByTestId('PlayArrowIcon')).not.toBeInTheDocument()
  })
})

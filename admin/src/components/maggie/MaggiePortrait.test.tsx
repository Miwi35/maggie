import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme } from '../../theme'
import { MaggiePortrait, PORTRAIT_MARGIN, PORTRAIT_SIZE } from './MaggiePortrait'

const renderPortrait = (unread = false) => {
  const onClick = vi.fn()
  const view = render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MaggiePortrait unread={unread} onClick={onClick} />
    </ThemeProvider>,
  )

  return { onClick, ...view }
}

describe('MaggiePortrait', () => {
  it('is a 64 px button, bottom right, that offers to open the conversation and tells the click', async () => {
    const { onClick } = renderPortrait()

    const portrait = screen.getByRole('button', { name: 'Ouvrir la conversation avec Maggie' })
    expect(portrait).toHaveStyle({
      width: `${PORTRAIT_SIZE}px`,
      height: `${PORTRAIT_SIZE}px`,
      right: `${PORTRAIT_MARGIN}px`,
      bottom: `${PORTRAIT_MARGIN}px`,
    })

    await userEvent.click(portrait)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('shows the unread dot only when something is unread', () => {
    const { container, rerender } = renderPortrait(true)
    expect(container.querySelector('.MuiBadge-badge:not(.MuiBadge-invisible)')).not.toBeNull()

    rerender(
      <ThemeProvider theme={veilleuseDarkTheme}>
        <MaggiePortrait unread={false} onClick={vi.fn()} />
      </ThemeProvider>,
    )
    expect(container.querySelector('.MuiBadge-badge:not(.MuiBadge-invisible)')).toBeNull()
  })

  it('shows how many messages are unread when the server says so', () => {
    render(
      <ThemeProvider theme={veilleuseDarkTheme}>
        <MaggiePortrait unread count={3} onClick={vi.fn()} />
      </ThemeProvider>,
    )
    expect(screen.getByText('3')).toBeInTheDocument()
  })
})

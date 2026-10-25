import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme } from '../../theme'
import { MaggiePortrait, PORTRAIT_SIZE } from './MaggiePortrait'

const renderPortrait = (props: Partial<React.ComponentProps<typeof MaggiePortrait>> = {}) => {
  const onClick = vi.fn()
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MaggiePortrait chatOpen={false} unread={false} right={24} onClick={onClick} {...props} />
    </ThemeProvider>,
  )

  return onClick
}

describe('MaggiePortrait', () => {
  it('is a 64 px button that offers to open the conversation, and tells the click', async () => {
    const onClick = renderPortrait()

    const portrait = screen.getByRole('button', { name: 'Ouvrir la conversation avec Maggie' })
    expect(portrait).toHaveAttribute('aria-expanded', 'false')
    expect(portrait).toHaveStyle({ width: `${PORTRAIT_SIZE}px`, height: `${PORTRAIT_SIZE}px`, right: '24px' })

    await userEvent.click(portrait)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('offers to fold the conversation away once it is out, and sits clear of the column', () => {
    renderPortrait({ chatOpen: true, right: 404 })

    const portrait = screen.getByRole('button', { name: 'Replier la conversation avec Maggie' })
    expect(portrait).toHaveAttribute('aria-expanded', 'true')
    expect(portrait).toHaveStyle({ right: '404px' })
  })

  it('shows the unread dot while folded, and not once the conversation is out', () => {
    const { container, rerender } = render(
      <ThemeProvider theme={veilleuseDarkTheme}>
        <MaggiePortrait chatOpen={false} unread right={24} onClick={vi.fn()} />
      </ThemeProvider>,
    )
    expect(container.querySelector('.MuiBadge-badge:not(.MuiBadge-invisible)')).not.toBeNull()

    rerender(
      <ThemeProvider theme={veilleuseDarkTheme}>
        <MaggiePortrait chatOpen unread right={24} onClick={vi.fn()} />
      </ThemeProvider>,
    )
    expect(container.querySelector('.MuiBadge-badge:not(.MuiBadge-invisible)')).toBeNull()
  })
})

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MaggieInterruption } from './MaggieInterruption'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { PHONE_WIDTH, resetViewport, setViewportWidth } from '../../test/viewport'
import { playChime } from './chime'

vi.mock('./chime', () => ({ playChime: vi.fn() }))

const MESSAGE = 'Ton rendez-vous de 15 h a été déplacé à 16 h.'

function setup(theme = veilleuseDarkTheme, open = true) {
  const onAction = vi.fn()
  const onLater = vi.fn()
  const view = (isOpen: boolean) => (
    <ThemeProvider theme={theme}>
      <button>Avant</button>
      <MaggieInterruption
        open={isOpen}
        message={MESSAGE}
        actionLabel="Ouvrir le chat"
        onAction={onAction}
        onLater={onLater}
      />
    </ThemeProvider>
  )
  const utils = render(view(open))
  return { onAction, onLater, rerender: (isOpen: boolean) => utils.rerender(view(isOpen)) }
}

describe('MaggieInterruption', () => {
  beforeEach(() => {
    vi.mocked(playChime).mockClear()
  })

  afterEach(() => {
    resetViewport()
  })

  test('appears as a modal alert dialog with her message and two actions', () => {
    setup()

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAccessibleName('MAGGIE · maintenant')
    expect(dialog).toHaveAccessibleDescription(MESSAGE)
    expect(screen.getByText(MESSAGE)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ouvrir le chat' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Plus tard' })).toBeInTheDocument()
    expect(screen.getByTestId('maggie-avatar')).toBeInTheDocument()
  })

  test('puts the focus on the proposed action and plays the chime', () => {
    setup()

    expect(screen.getByRole('button', { name: 'Ouvrir le chat' })).toHaveFocus()
    expect(playChime).toHaveBeenCalledTimes(1)
  })

  test('renders nothing, and stays silent, while closed', () => {
    setup(veilleuseDarkTheme, false)

    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(playChime).not.toHaveBeenCalled()
  })

  test('the action button runs the proposed action', async () => {
    const { onAction, onLater } = setup()

    await userEvent.click(screen.getByRole('button', { name: 'Ouvrir le chat' }))

    expect(onAction).toHaveBeenCalledTimes(1)
    expect(onLater).not.toHaveBeenCalled()
  })

  test('« Plus tard » and Escape both put it off', async () => {
    const { onAction, onLater } = setup()

    await userEvent.click(screen.getByRole('button', { name: 'Plus tard' }))
    await userEvent.keyboard('{Escape}')

    expect(onLater).toHaveBeenCalledTimes(2)
    expect(onAction).not.toHaveBeenCalled()
  })

  test('Tab never leaves the box', async () => {
    setup()
    const action = screen.getByRole('button', { name: 'Ouvrir le chat' })
    const later = screen.getByRole('button', { name: 'Plus tard' })

    await userEvent.tab()
    expect(later).toHaveFocus()
    await userEvent.tab()
    expect(action).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(later).toHaveFocus()
  })

  test('gives the focus back when it closes', () => {
    const before = document.createElement('button')
    document.body.appendChild(before)
    before.focus()
    const { rerender } = setup(veilleuseDarkTheme, false)
    rerender(true)
    expect(screen.getByRole('button', { name: 'Ouvrir le chat' })).toHaveFocus()

    rerender(false)

    expect(before).toHaveFocus()
    before.remove()
  })

  test.each([
    ['dark', veilleuseDarkTheme],
    ['light', veilleuseLightTheme],
  ])('renders in the %s theme', (_mode, theme) => {
    setup(theme)

    expect(screen.getByText('MAGGIE · maintenant')).toBeVisible()
    expect(screen.getByText(MESSAGE)).toBeVisible()
  })

  test('keeps working under prefers-reduced-motion', () => {
    const original = window.matchMedia
    window.matchMedia = ((query: string) =>
      ({
        matches: query.includes('prefers-reduced-motion'),
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
      }) as unknown as MediaQueryList) as typeof window.matchMedia

    setup()

    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ouvrir le chat' })).toHaveFocus()
    window.matchMedia = original
  })

  test('on a phone it keeps a 72 px avatar and 44 px buttons', () => {
    setViewportWidth(PHONE_WIDTH)
    setup()

    expect(screen.getByTestId('maggie-avatar')).toHaveStyle({ width: '72px', height: '72px' })
    expect(screen.getByRole('button', { name: 'Plus tard' })).toHaveStyle({ minHeight: '44px' })
  })

  test('on a desk the avatar is 96 px', () => {
    setup()

    expect(screen.getByTestId('maggie-avatar')).toHaveStyle({ width: '96px', height: '96px' })
  })
})

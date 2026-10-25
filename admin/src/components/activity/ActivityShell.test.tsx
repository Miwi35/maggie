import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme } from '../../theme'
import { ActivityShell } from './ActivityShell'

vi.mock('../chat/ChatWidget', () => ({
  ChatWidget: ({ open }: { open: boolean }) => (open ? <div data-testid="chat-panel">Conversation</div> : null),
}))

interface Fullscreen {
  requestFullscreen: ReturnType<typeof vi.fn>
  exitFullscreen: ReturnType<typeof vi.fn>
  release: ReturnType<typeof vi.fn>
  request: ReturnType<typeof vi.fn>
}

/** Installs a Fullscreen API and a Wake Lock that answer like a browser that accepts both. */
const installBrowser = (options: { fullscreen?: 'accepts' | 'refuses' | 'absent'; wakeLock?: 'accepts' | 'refuses' | 'absent' } = {}): Fullscreen => {
  const { fullscreen = 'accepts', wakeLock = 'accepts' } = options
  let current: Element | null = null

  const requestFullscreen = vi.fn(function (this: Element) {
    if (fullscreen === 'refuses') return Promise.reject(new Error('refused'))
    // eslint-disable-next-line @typescript-eslint/no-this-alias -- the element the browser puts in full screen is the one called
    current = this
    document.dispatchEvent(new Event('fullscreenchange'))

    return Promise.resolve()
  })
  const exitFullscreen = vi.fn(() => {
    current = null
    document.dispatchEvent(new Event('fullscreenchange'))

    return Promise.resolve()
  })
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => current })
  Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: exitFullscreen })
  if (fullscreen === 'absent') delete (HTMLElement.prototype as Partial<HTMLElement>).requestFullscreen
  else Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, writable: true, value: requestFullscreen })

  const release = vi.fn(() => Promise.resolve())
  const request = vi.fn(() => (wakeLock === 'refuses' ? Promise.reject(new Error('refused')) : Promise.resolve({ released: false, release })))
  Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: wakeLock === 'absent' ? undefined : { request } })

  return { requestFullscreen, exitFullscreen, release, request }
}

const pressEscapeOutOfFullscreen = () =>
  act(() => {
    // What the browser does on Échap: leaves full screen, and tells so.
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => null })
    document.dispatchEvent(new Event('fullscreenchange'))
  })

const renderShell = (props: Partial<React.ComponentProps<typeof ActivityShell>> = {}) => {
  const onQuit = vi.fn()
  const view = render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <ActivityShell color="#8FB8FF" name="Cuisine" onQuit={onQuit} {...props}>
        <p>Contenu</p>
      </ActivityShell>
    </ThemeProvider>,
  )

  return { onQuit, ...view }
}

describe('ActivityShell', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    Reflect.deleteProperty(document, 'fullscreenElement')
    Reflect.deleteProperty(navigator, 'wakeLock')
  })

  test("draws the activity's colour as a radial halo on the night background (MAG-617)", () => {
    installBrowser()
    renderShell({ color: '#8FB8FF' })

    const background = window.getComputedStyle(screen.getByTestId('activity-shell')).backgroundImage
    expect(background).toContain('radial-gradient(900px 640px at 78% 30%')
    expect(background).toContain('rgba(143, 184, 255, 0.16)')
  })

  test('tints the halo with the colour it is given, not with the accent of the theme', () => {
    installBrowser()
    renderShell({ color: '#7FD1AE' })

    const background = window.getComputedStyle(screen.getByTestId('activity-shell')).backgroundImage
    expect(background).toContain('rgba(127, 209, 174, 0.16)')
    expect(background).not.toContain('143, 184, 255')
  })

  test('takes the screen as it opens and keeps it on', async () => {
    const browser = installBrowser()
    renderShell()

    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))
    expect(browser.requestFullscreen).toHaveBeenCalledTimes(1)
    expect(browser.request).toHaveBeenCalledWith('screen')
    expect(screen.getByText('Contenu')).toBeInTheDocument()
  })

  test('covers the whole window, without chrome, when full screen is refused', async () => {
    installBrowser({ fullscreen: 'refuses' })
    renderShell()

    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'window'))
    expect(screen.getByTestId('activity-shell')).toHaveStyle({ position: 'fixed', width: '100vw' })
  })

  test('covers the window when the browser has no Fullscreen API', async () => {
    installBrowser({ fullscreen: 'absent' })
    renderShell()

    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'window'))
  })

  test('still runs when the screen cannot be kept on', async () => {
    installBrowser({ wakeLock: 'refuses' })
    renderShell()

    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))
    expect(screen.getByText('Contenu')).toBeInTheDocument()
  })

  test('releases the screen and the wake lock on leaving', async () => {
    const browser = installBrowser()
    const { unmount } = renderShell()
    await waitFor(() => expect(browser.request).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))

    unmount()

    expect(browser.exitFullscreen).toHaveBeenCalled()
    expect(browser.release).toHaveBeenCalled()
  })

  test('takes the wake lock again when the tab comes back', async () => {
    const browser = installBrowser()
    // The browser dropped the first lock while the tab was hidden.
    browser.request.mockResolvedValueOnce({ released: true, release: browser.release })
    renderShell()
    await waitFor(() => expect(browser.request).toHaveBeenCalledTimes(1))

    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    await waitFor(() => expect(browser.request).toHaveBeenCalledTimes(2))
  })

  test('the ✕ leaves: full screen is given back, then onQuit', async () => {
    const browser = installBrowser()
    const { onQuit } = renderShell()
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))

    await userEvent.click(screen.getByRole('button', { name: 'Quitter Cuisine' }))

    await waitFor(() => expect(onQuit).toHaveBeenCalledTimes(1))
    expect(browser.exitFullscreen).toHaveBeenCalled()
  })

  test('Échap out of full screen leaves without asking when nothing is at stake', async () => {
    installBrowser()
    const { onQuit } = renderShell()
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))

    pressEscapeOutOfFullscreen()

    expect(onQuit).toHaveBeenCalledTimes(1)
    expect(screen.queryByText("Quitter l'activité ?")).not.toBeInTheDocument()
  })

  test('Échap in the window fallback leaves too', async () => {
    installBrowser({ fullscreen: 'refuses' })
    const { onQuit } = renderShell()
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'window'))

    await userEvent.keyboard('{Escape}')

    await waitFor(() => expect(onQuit).toHaveBeenCalledTimes(1))
  })

  test('with confirmQuit, the ✕ asks first and « Rester » keeps the activity', async () => {
    installBrowser()
    const { onQuit } = renderShell({ confirmQuit: true, confirmDetail: 'Le minuteur sera annulé.' })
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))

    await userEvent.click(screen.getByRole('button', { name: 'Quitter Cuisine' }))
    expect(await screen.findByText("Quitter l'activité ?")).toBeInTheDocument()
    expect(screen.getByText('Le minuteur sera annulé.')).toBeInTheDocument()
    expect(onQuit).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Rester' }))

    await waitFor(() => expect(screen.queryByText("Quitter l'activité ?")).not.toBeInTheDocument())
    expect(onQuit).not.toHaveBeenCalled()
  })

  test('with confirmQuit, « Quitter » leaves', async () => {
    installBrowser()
    const { onQuit } = renderShell({ confirmQuit: true })
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))

    await userEvent.click(screen.getByRole('button', { name: 'Quitter Cuisine' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Quitter' }))

    await waitFor(() => expect(onQuit).toHaveBeenCalledTimes(1))
  })

  test('with confirmQuit, Échap out of full screen asks instead of leaving', async () => {
    installBrowser()
    const { onQuit } = renderShell({ confirmQuit: true })
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))

    pressEscapeOutOfFullscreen()

    expect(await screen.findByText("Quitter l'activité ?")).toBeInTheDocument()
    expect(onQuit).not.toHaveBeenCalled()
  })

  test('Maggie opens from the frame', async () => {
    installBrowser()
    renderShell()

    await userEvent.click(screen.getByRole('button', { name: 'Maggie' }))

    expect(await screen.findByTestId('chat-panel')).toBeInTheDocument()
  })

  test('the buttons of the frame are 64 px', async () => {
    installBrowser()
    renderShell()
    await waitFor(() => expect(screen.getByTestId('activity-shell')).toHaveAttribute('data-mode', 'fullscreen'))

    expect(screen.getByRole('button', { name: 'Quitter Cuisine' })).toHaveStyle({ width: '64px', height: '64px' })
  })
})

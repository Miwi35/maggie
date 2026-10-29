import { lazy, Suspense, type ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme } from '../../theme'
import { Layout } from './Layout'

vi.mock('react-admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-admin')>()),
  // Like the real one: the children sit under a Suspense of its own.
  Layout: ({ children }: { children: ReactNode }) => <Suspense fallback={<p>layout fallback</p>}>{children}</Suspense>,
  Loading: () => <p>page loading</p>,
}))
vi.mock('../chat/ChatWidget', () => ({
  ChatWidget: ({ open, onClose, openMessageId }: { open: boolean; onClose: () => void; openMessageId?: string | null }) => (
    <div data-testid="chat-panel" data-open={String(open)} data-message={openMessageId ?? ''}>
      <button onClick={onClose}>Fermer la conversation</button>
    </div>
  ),
}))
vi.mock('../maggie/MaggieInterruptionHost', () => ({ MaggieInterruptionHost: () => null }))
vi.mock('./AppBar', () => ({ CustomAppBar: () => null }))
vi.mock('./Breadcrumbs', () => ({ Breadcrumbs: () => null, PageHeading: () => null }))
vi.mock('./ThemePreferenceSync', () => ({ ThemePreferenceSync: () => null }))
vi.mock('./Menu', () => ({ CustomMenu: () => null }))
vi.mock('./Sidebar', () => ({ CustomSidebar: () => null }))
vi.mock('../../hooks/useNarrowScreen', () => ({ useNarrowScreen: () => false }))

describe('Layout', () => {
  it('keeps the chat panel mounted while a lazy page is still loading', () => {
    const NeverLoaded = lazy(() => new Promise<never>(() => {}))

    render(
      <MemoryRouter>
      <ThemeProvider theme={veilleuseDarkTheme}>
        <Layout>
          <NeverLoaded />
        </Layout>
      </ThemeProvider>
      </MemoryRouter>,
    )

    expect(screen.getByText('page loading')).toBeInTheDocument()
    expect(screen.queryByText('layout fallback')).not.toBeInTheDocument()
    expect(screen.getByTestId('chat-panel')).toHaveAttribute('data-open', 'false')
  })

  it('opens with the portrait alone: it opens the conversation and goes away; closing the chat brings it back', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
      <ThemeProvider theme={veilleuseDarkTheme}>
        <Layout>
          <p>page</p>
        </Layout>
      </ThemeProvider>
      </MemoryRouter>,
    )

    expect(screen.getByTestId('chat-panel')).toHaveAttribute('data-open', 'false')
    const portrait = screen.getByTestId('maggie-portrait')
    expect(portrait).toHaveAccessibleName('Ouvrir la conversation avec Maggie')

    await user.click(portrait)
    expect(screen.getByTestId('chat-panel')).toHaveAttribute('data-open', 'true')
    expect(screen.queryByTestId('maggie-portrait')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Fermer la conversation' }))
    expect(screen.getByTestId('chat-panel')).toHaveAttribute('data-open', 'false')
    expect(screen.getByTestId('maggie-portrait')).toBeInTheDocument()
  })

  it('a link to /chat?message= opens the chat on that message and leaves the route', () => {
    render(
      <MemoryRouter initialEntries={['/chat?message=01K0MSG']}>
        <ThemeProvider theme={veilleuseDarkTheme}>
          <Layout>
            <p>page</p>
          </Layout>
        </ThemeProvider>
      </MemoryRouter>,
    )

    expect(screen.getByTestId('chat-panel')).toHaveAttribute('data-open', 'true')
    expect(screen.getByTestId('chat-panel')).toHaveAttribute('data-message', '01K0MSG')
  })
})

import { lazy, Suspense, type ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Layout } from './Layout'

vi.mock('react-admin', () => ({
  // Like the real one: the children sit under a Suspense of its own.
  Layout: ({ children }: { children: ReactNode }) => <Suspense fallback={<p>layout fallback</p>}>{children}</Suspense>,
  Loading: () => <p>page loading</p>,
}))
vi.mock('../chat/ChatWidget', () => ({
  ChatWidget: ({ open }: { open: boolean }) => <div data-testid="chat-panel" data-open={String(open)} />,
}))
vi.mock('../maggie/MaggieInterruptionHost', () => ({ MaggieInterruptionHost: () => null }))
vi.mock('./AppBar', () => ({ CustomAppBar: () => null }))
vi.mock('./Breadcrumbs', () => ({ Breadcrumbs: () => null }))
vi.mock('./ThemePreferenceSync', () => ({ ThemePreferenceSync: () => null }))
vi.mock('./Menu', () => ({ CustomMenu: () => null }))
vi.mock('./Sidebar', () => ({ CustomSidebar: () => null }))
vi.mock('../../hooks/useNarrowScreen', () => ({ useNarrowScreen: () => false }))

describe('Layout', () => {
  it('keeps the chat panel mounted while a lazy page is still loading', () => {
    const NeverLoaded = lazy(() => new Promise<never>(() => {}))

    render(
      <Layout>
        <NeverLoaded />
      </Layout>,
    )

    expect(screen.getByText('page loading')).toBeInTheDocument()
    expect(screen.queryByText('layout fallback')).not.toBeInTheDocument()
    expect(screen.getByTestId('chat-panel')).toHaveAttribute('data-open', 'true')
  })
})

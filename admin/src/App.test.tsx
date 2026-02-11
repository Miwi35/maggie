import { describe, test, expect, vi } from 'vitest'
import { render } from '@testing-library/react'
import App from './App'

// Mock HydraAdmin since it needs network access
vi.mock('@api-platform/admin', () => ({
  HydraAdmin: ({ children }: { children: React.ReactNode }) => <div data-testid="hydra-admin">{children}</div>,
}))

vi.mock('./components/layout/Layout', () => ({
  Layout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('./modules/agenda', () => ({
  agendaResources: <div data-testid="agenda-resources" />,
}))

describe('App', () => {
  test('renders without crashing', () => {
    const { getByTestId } = render(<App />)
    expect(getByTestId('hydra-admin')).toBeInTheDocument()
    expect(getByTestId('agenda-resources')).toBeInTheDocument()
  })
})

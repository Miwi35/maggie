import { describe, test, expect, vi } from 'vitest'
import { render } from '@testing-library/react'
import App from './App'

// Mock HydraAdmin since it needs network access
vi.mock('@api-platform/admin', () => ({
  HydraAdmin: ({ children }: { children: React.ReactNode }) => <div data-testid="hydra-admin">{children}</div>,
  fetchHydra: vi.fn(),
  hydraDataProvider: vi.fn(() => ({})),
}))

vi.mock('./components/layout/Layout', () => ({
  Layout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('./modules/calendar', () => ({
  calendarResources: <div data-testid="calendar-resources" />,
}))

vi.mock('./modules/grocery', () => ({
  groceryResources: <div data-testid="grocery-resources" />,
}))

vi.mock('./modules/cookbook', () => ({
  cookbookResources: <div data-testid="cookbook-resources" />,
}))

vi.mock('./modules/finance', () => ({
  financeResources: <div data-testid="finance-resources" />,
}))

vi.mock('./modules/settings', () => ({
  settingsResources: <div data-testid="settings-resources" />,
}))

vi.mock('./modules/search', () => ({
  searchResources: <div data-testid="search-resources" />,
}))

vi.mock('./auth/authProvider', () => ({
  authProvider: {},
  routeVisitorWithoutSessionToLogin: vi.fn(),
}))

vi.mock('./auth/LoginPage', () => ({
  LoginPage: () => <div data-testid="login-page" />,
}))

describe('App', () => {
  test('renders without crashing', () => {
    const { getByTestId } = render(<App />)
    expect(getByTestId('hydra-admin')).toBeInTheDocument()
    expect(getByTestId('calendar-resources')).toBeInTheDocument()
  })
})

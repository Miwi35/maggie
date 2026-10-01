import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ChatContext } from '../../components/layout/ChatContext'
import { UserPreferenceSettings } from './UserPreferenceSettings'

vi.mock('react-admin', () => ({
  useDataProvider: () => ({ getList: vi.fn().mockResolvedValue({ data: [] }) }),
  useNotify: () => vi.fn(),
  useStore: () => ['light', vi.fn()],
}))

vi.mock('../../hooks/useMercure', () => ({ useMercure: vi.fn() }))

vi.mock('../../hooks/useUserPreferences', () => ({
  useUserPreferences: () => ({
    preferences: {
      id: 'p1',
      theme: 'light',
      locale: 'fr',
      timezone: 'Europe/Paris',
      defaultCalendarView: 'month',
      enabledAgendaIds: [],
      notificationsEnabled: true,
    },
    updatePreference: vi.fn(),
    loading: false,
    refresh: vi.fn(),
  }),
}))

const baseContext = {
  chatOpen: false,
  sidebarTab: 'chat' as const,
  onChatToggle: () => {},
  onMindToggle: () => {},
  unreadChat: false,
  onVoiceMessage: () => {},
  wakeWordEnabled: false,
  wakeWordListening: false,
  wakeWordTriggered: false,
  toggleWakeWord: vi.fn(),
  pauseWakeWord: () => {},
  resumeWakeWord: () => {},
  clearWakeWordTrigger: () => {},
}

const renderSettings = (overrides: Partial<typeof baseContext> = {}) =>
  render(
    <ChatContext.Provider value={{ ...baseContext, ...overrides }}>
      <UserPreferenceSettings />
    </ChatContext.Provider>,
  )

describe('UserPreferenceSettings — wake word', () => {
  beforeEach(() => {
    baseContext.toggleWakeWord = vi.fn()
  })

  test('shows the wake word switch, off by default', async () => {
    renderSettings()

    const toggle = await screen.findByRole('switch', { name: /mot d.activation/i })
    expect(toggle).not.toBeChecked()
  })

  test('reflects the enabled state', async () => {
    renderSettings({ wakeWordEnabled: true })

    expect(await screen.findByRole('switch', { name: /mot d.activation/i })).toBeChecked()
  })

  test('turning the switch on enables the wake word', async () => {
    renderSettings()

    await userEvent.click(await screen.findByRole('switch', { name: /mot d.activation/i }))

    await waitFor(() => expect(baseContext.toggleWakeWord).toHaveBeenCalledWith(true))
  })

  test('turning the switch off disables the wake word', async () => {
    renderSettings({ wakeWordEnabled: true })

    await userEvent.click(await screen.findByRole('switch', { name: /mot d.activation/i }))

    await waitFor(() => expect(baseContext.toggleWakeWord).toHaveBeenCalledWith(false))
  })
})

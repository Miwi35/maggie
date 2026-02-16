import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ChatWidget } from './ChatWidget'

// Mock EventSource globally before any render
class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}
vi.stubGlobal('EventSource', MockEventSource)

const defaultProps = {
  open: true,
  onClose: vi.fn(),
  onUnread: vi.fn(),
}

describe('ChatWidget', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    defaultProps.onClose = vi.fn()
    defaultProps.onUnread = vi.fn()
  })

  test('renders header and input when open', () => {
    render(<ChatWidget {...defaultProps} />)

    expect(screen.getByText('Maggie')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Demande à Maggie...')).toBeInTheDocument()
  })

  test('sends message on button click', async () => {
    const mockResponse = { response: 'Hello from Maggie!' }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve(mockResponse),
      }),
    )

    const user = userEvent.setup()
    render(<ChatWidget {...defaultProps} />)

    // Type a message
    const input = screen.getByPlaceholderText('Demande à Maggie...')
    await user.type(input, 'Hello Maggie')

    // Click Send
    const sendButton = screen.getByTestId('SendIcon').closest('button')!
    await user.click(sendButton)

    // Verify user message appears
    expect(screen.getByText('Hello Maggie')).toBeInTheDocument()

    // Verify fetch was called with correct payload
    expect(fetch).toHaveBeenCalledWith('/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Hello Maggie', user_id: 'default' }),
    })

    // Verify assistant response appears
    await waitFor(() => {
      expect(screen.getByText('Hello from Maggie!')).toBeInTheDocument()
    })
  })
})

import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import { BankConnectionsPage } from './BankConnectionsPage'

const PENDING = {
  id: 'c1',
  bankName: 'Revolut',
  country: 'FR',
  status: 'pending',
  consentExpiresAt: null,
  daysBeforeExpiry: null,
  lastSyncedAt: null,
  needsReconnecting: false,
}

const json = (body: unknown) => ({ ok: true, json: async () => body }) as Response

const fetchMock = vi.fn()

const renderPage = () =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <BankConnectionsPage />
    </AdminContext>,
  )

describe('BankConnectionsPage', () => {
  beforeEach(() => {
    fetchMock.mockImplementation((url: string) => {
      if (url.startsWith('/api/finance/banks')) {
        return Promise.resolve(json({ banks: [] }))
      }

      return Promise.resolve(json({ connections: [PENDING] }))
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('a journey left unfinished says so and offers to resume it', async () => {
    renderPage()

    expect(await screen.findByText('Revolut')).toBeInTheDocument()
    expect(screen.getByText(/n'a pas été menée jusqu'au bout/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reprendre' })).toBeInTheDocument()
  })

  test('resuming sends the user back to their bank', async () => {
    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign })
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url.endsWith('/reconnect') && init?.method === 'POST') {
        return Promise.resolve(json({ authorizationUrl: 'https://bank.example/again' }))
      }
      if (url.startsWith('/api/finance/banks')) {
        return Promise.resolve(json({ banks: [] }))
      }

      return Promise.resolve(json({ connections: [PENDING] }))
    })

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Reprendre' }))

    await waitFor(() => expect(assign).toHaveBeenCalledWith('https://bank.example/again'))
  })

  test('removing a link asks first', async () => {
    vi.stubGlobal(
      'confirm',
      vi.fn(() => false),
    )

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Supprimer' }))

    // Answering no touches nothing.
    expect(
      fetchMock.mock.calls.some(
        ([, init]) => (init as RequestInit | undefined)?.method === 'DELETE',
      ),
    ).toBe(false)
  })

  test('confirming removes the link', async () => {
    vi.stubGlobal(
      'confirm',
      vi.fn(() => true),
    )

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Supprimer' }))

    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(
          ([, init]) => (init as RequestInit | undefined)?.method === 'DELETE',
        ),
      ).toBe(true),
    )
  })
})

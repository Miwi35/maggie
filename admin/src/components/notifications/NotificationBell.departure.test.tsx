import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationBell } from './NotificationBell'

const update = vi.fn()
const redirect = vi.fn()
let listed: unknown[] = []

vi.mock('react-admin', () => ({
  useGetList: () => ({ data: listed, refetch: vi.fn() }),
  useDataProvider: () => ({ update, delete: vi.fn() }),
  useRedirect: () => redirect,
}))

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

const GOOGLE_MAPS = 'https://www.google.com/maps/dir/?api=1&destination=48.1%2C-1.7&travelmode=walking'
const fetchMock = vi.fn()
const open = vi.fn()

const departure = {
  id: '/api/notifications/01K0DEPART',
  type: 'departure',
  title: 'Pars maintenant pour Dentiste',
  body: null,
  relatedEntityIri: '/api/trips/01TRIP',
  readAt: null,
  createdAt: '2026-10-27T13:34:00+00:00',
}

describe('NotificationBell — departure alert (MAG-659)', () => {
  beforeEach(() => {
    update.mockReset().mockResolvedValue({ data: { ...departure, readAt: '2026-10-27T13:35:00+00:00' } })
    redirect.mockReset()
    open.mockReset()
    vi.stubGlobal('open', open)
    fetchMock.mockReset().mockResolvedValue({ ok: true, status: 200, json: async () => ({ navigationUrl: GOOGLE_MAPS }) })
    vi.stubGlobal('fetch', fetchMock)
    listed = [departure]
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('opens the navigation link of the trip in a new tab and marks the alert read', async () => {
    render(<NotificationBell />)
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/trips/01TRIP', expect.anything()))

    await userEvent.click(screen.getByText('Pars maintenant pour Dentiste'))

    expect(open).toHaveBeenCalledWith(GOOGLE_MAPS, '_blank', 'noopener,noreferrer')
    expect(update).toHaveBeenCalledWith('notifications', expect.objectContaining({ id: departure.id }))
    expect(redirect).not.toHaveBeenCalled()
  })

  test('opens nothing, and still marks it read, when the trip is gone', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404, json: async () => ({}) })
    render(<NotificationBell />)
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }))

    await userEvent.click(screen.getByText('Pars maintenant pour Dentiste'))

    expect(open).not.toHaveBeenCalled()
    expect(update).toHaveBeenCalled()
  })

  test('another kind of notification opens no tab', async () => {
    listed = [{ ...departure, type: 'reminder', relatedEntityIri: '/api/events/01EVENT' }]
    render(<NotificationBell />)
    await userEvent.click(screen.getByRole('button', { name: 'Notifications' }))

    await userEvent.click(screen.getByText('Pars maintenant pour Dentiste'))

    expect(open).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

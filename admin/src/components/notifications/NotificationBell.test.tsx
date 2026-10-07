import { describe, test, expect, vi, beforeEach } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { NotificationBell } from './NotificationBell'

/**
 * The bell is fed by Elasticsearch, which the worker indexes after the write:
 * the Mercure event leaves first, so a `refetch()` on it reads an index that
 * does not hold the notification yet (MAG-343). The event carries the
 * notification; the bell has to use it.
 */

const refetch = vi.fn()
const update = vi.fn()
let listed: unknown[] = []
let onMercure: (data?: string) => void = () => {}

vi.mock('react-admin', () => ({
  useGetList: () => ({ data: listed, refetch }),
  useDataProvider: () => ({ update }),
  useRedirect: () => vi.fn(),
}))

vi.mock('../../hooks/useMercure', () => ({
  useMercure: (_topics: string[], cb: (data?: string) => void) => {
    onMercure = cb
  },
}))

const film = {
  id: '01K0FILM',
  type: 'system',
  title: 'Votre film commence',
  body: null,
  relatedEntityIri: null,
  readAt: null,
  createdAt: '2026-10-07T18:23:00+00:00',
}

const dentist = { ...film, id: '01K0DENT', title: 'Dentiste demain', createdAt: '2026-10-07T10:00:00+00:00' }

const emit = (payload: Record<string, unknown>) => act(() => onMercure(JSON.stringify(payload)))

// MUI keeps the last count in the DOM while the badge fades out: hidden means zero.
const unread = () => {
  const badge = document.querySelector('.MuiBadge-badge')
  return !badge || badge.classList.contains('MuiBadge-invisible') ? '' : (badge.textContent ?? '')
}

async function openBell() {
  await userEvent.click(screen.getByRole('button', { name: 'Notifications' }))
}

describe('NotificationBell — live updates from Mercure', () => {
  beforeEach(() => {
    refetch.mockReset()
    update.mockReset()
    listed = [dentist]
  })

  test('a created notification appears and counts as unread, while the list read still lacks it', async () => {
    render(<NotificationBell />)
    expect(unread()).toBe('1')

    emit({ '@id': '/api/notifications/01K0FILM', ...film })

    expect(unread()).toBe('2')
    await openBell()
    expect(screen.getByText('Votre film commence')).toBeInTheDocument()
    expect(screen.getByText('Dentiste demain')).toBeInTheDocument()
    expect(refetch).not.toHaveBeenCalled()
  })

  test('a notification created after the others is listed first', async () => {
    render(<NotificationBell />)
    emit({ '@id': '/api/notifications/01K0FILM', ...film })

    await openBell()
    const titles = screen.getAllByRole('button').map((b) => b.textContent)
    expect(titles.findIndex((t) => t?.includes('Votre film'))).toBeLessThan(
      titles.findIndex((t) => t?.includes('Dentiste')),
    )
  })

  test('a notification marked read elsewhere is read here, even when the list read still says unread', () => {
    render(<NotificationBell />)
    expect(unread()).toBe('1')

    emit({ '@id': '/api/notifications/01K0DENT', ...dentist, readAt: '2026-10-07T18:30:00+00:00' })

    expect(unread()).toBe('')
  })

  test('a differential update is merged into the notification already known', async () => {
    render(<NotificationBell />)

    emit({ '@id': '/api/notifications/01K0DENT', readAt: '2026-10-07T18:30:00+00:00' })

    expect(unread()).toBe('')
    await openBell()
    expect(screen.getByText('Dentiste demain')).toBeInTheDocument()
  })

  test('a deleted notification disappears, even when the list read still holds it', async () => {
    render(<NotificationBell />)

    emit({ '@id': '/api/notifications/01K0DENT', deleted: true })

    expect(unread()).toBe('')
    await openBell()
    expect(screen.getByText('Aucune notification')).toBeInTheDocument()
  })

  test('falls back to a refetch when the event cannot be applied', () => {
    render(<NotificationBell />)

    act(() => onMercure('not json'))
    emit({ '@id': '/api/notifications/01K0UNKNOWN', readAt: '2026-10-07T18:30:00+00:00' })

    expect(refetch).toHaveBeenCalledTimes(2)
  })

  test('marking a notification read updates the bell from the answer, not from a list read', async () => {
    update.mockResolvedValue({ data: { ...dentist, readAt: '2026-10-07T18:31:00+00:00' } })
    render(<NotificationBell />)

    await openBell()
    await userEvent.click(screen.getByText('Dentiste demain'))

    expect(update).toHaveBeenCalledOnce()
    expect(unread()).toBe('')
    expect(refetch).not.toHaveBeenCalled()
  })
})

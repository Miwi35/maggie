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
const remove = vi.fn()
let listed: unknown[] = []
let onMercure: (data?: string) => void = () => {}

vi.mock('react-admin', () => ({
  useGetList: () => ({ data: listed, refetch }),
  useDataProvider: () => ({ update, delete: remove }),
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

// The Hydra provider replaces `id` by the IRI and keeps the ULID in `originId`;
// the Mercure payload carries the ULID as `id` and the IRI as `@id`.
const iri = (id: string) => `/api/notifications/${id}`
const dentistPayload = { ...film, id: '01K0DENT', title: 'Dentiste demain', createdAt: '2026-10-07T10:00:00+00:00' }
const dentist = { ...dentistPayload, id: iri('01K0DENT'), '@id': iri('01K0DENT'), originId: '01K0DENT' }

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
    remove.mockReset()
    listed = [dentist]
  })

  test('a created notification appears and counts as unread, while the list read still lacks it', async () => {
    render(<NotificationBell />)
    expect(unread()).toBe('1')

    emit({ '@id': iri('01K0FILM'), ...film })

    expect(unread()).toBe('2')
    await openBell()
    expect(screen.getByText('Votre film commence')).toBeInTheDocument()
    expect(screen.getByText('Dentiste demain')).toBeInTheDocument()
    expect(refetch).not.toHaveBeenCalled()
  })

  test('a notification created after the others is listed first', async () => {
    render(<NotificationBell />)
    emit({ '@id': iri('01K0FILM'), ...film })

    await openBell()
    const titles = screen.getAllByRole('button').map((b) => b.textContent)
    expect(titles.findIndex((t) => t?.includes('Votre film'))).toBeLessThan(
      titles.findIndex((t) => t?.includes('Dentiste')),
    )
  })

  test('a notification marked read elsewhere is read here, even when the list read still says unread', () => {
    render(<NotificationBell />)
    expect(unread()).toBe('1')

    emit({ '@id': iri('01K0DENT'), ...dentistPayload, readAt: '2026-10-07T18:30:00+00:00' })

    expect(unread()).toBe('')
  })

  test('a differential update is merged into the notification already known', async () => {
    render(<NotificationBell />)

    emit({ '@id': iri('01K0DENT'), readAt: '2026-10-07T18:30:00+00:00' })

    expect(unread()).toBe('')
    await openBell()
    expect(screen.getByText('Dentiste demain')).toBeInTheDocument()
  })

  test('a deleted notification disappears, even when the list read still holds it', async () => {
    render(<NotificationBell />)

    emit({ '@id': iri('01K0DENT'), deleted: true })

    expect(unread()).toBe('')
    await openBell()
    expect(screen.getByText('Aucune notification')).toBeInTheDocument()
  })

  test('a notification already listed is updated in place, not listed twice', async () => {
    render(<NotificationBell />)

    emit({ '@id': iri('01K0DENT'), ...dentistPayload, readAt: '2026-10-07T18:30:00+00:00' })

    await openBell()
    expect(screen.getAllByText('Dentiste demain')).toHaveLength(1)
    expect(unread()).toBe('')
  })

  test('a notification that arrived by event is marked read through its IRI', async () => {
    update.mockResolvedValue({ data: { ...film, id: iri('01K0FILM'), readAt: '2026-10-07T18:31:00+00:00' } })
    render(<NotificationBell />)
    emit({ '@id': iri('01K0FILM'), ...film })

    await openBell()
    await userEvent.click(screen.getByText('Votre film commence'))

    expect(update).toHaveBeenCalledWith('notifications', expect.objectContaining({ id: iri('01K0FILM') }))
    expect(unread()).toBe('1')
  })

  test('falls back to a refetch when the event cannot be applied', () => {
    render(<NotificationBell />)

    act(() => onMercure('not json'))
    emit({ '@id': iri('01K0UNKNOWN'), readAt: '2026-10-07T18:30:00+00:00' })

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

describe('NotificationBell — deleting', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    refetch.mockReset()
    remove.mockReset()
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    listed = [dentist, { ...dentist, id: iri('01K0FILM'), title: 'Votre film commence', createdAt: '2026-10-07T09:00:00+00:00' }]
  })

  test('the trash icon removes that notification only, without reloading the list', async () => {
    remove.mockResolvedValue({ data: { id: dentist.id } })
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getAllByRole('button', { name: 'Supprimer' })[0])

    expect(remove).toHaveBeenCalledWith('notifications', expect.objectContaining({ id: dentist.id }))
    expect(screen.queryByText('Dentiste demain')).not.toBeInTheDocument()
    expect(screen.getByText('Votre film commence')).toBeInTheDocument()
    expect(refetch).not.toHaveBeenCalled()
  })

  test('a failed deletion says so and leaves the list as it was', async () => {
    remove.mockRejectedValue(new Error('boom'))
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getAllByRole('button', { name: 'Supprimer' })[0])

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de supprimer la notification.')
    expect(screen.getByText('Dentiste demain')).toBeInTheDocument()
    expect(screen.getByText('Votre film commence')).toBeInTheDocument()
  })

  test('"Tout effacer" asks first, and cancelling deletes nothing', async () => {
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Tout effacer' }))
    expect(await screen.findByText('Effacer toutes les notifications ?')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Annuler' }))

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByText('Dentiste demain')).toBeInTheDocument()
  })

  test('confirming empties the list through DELETE /api/notifications', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ deleted: 2 }) })
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Tout effacer' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Effacer' }))

    expect(fetchMock).toHaveBeenCalledWith('/api/notifications', expect.objectContaining({ method: 'DELETE' }))
    expect(await screen.findByText('Aucune notification')).toBeInTheDocument()
    expect(unread()).toBe('')
  })

  test('an API error on "Tout effacer" says so and keeps every notification', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500 })
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Tout effacer' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Effacer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Impossible d'effacer les notifications.")
    expect(screen.getByText('Dentiste demain')).toBeInTheDocument()
    expect(screen.getByText('Votre film commence')).toBeInTheDocument()
  })

  test('"Tout effacer" is not offered when the bell is empty', async () => {
    listed = []
    render(<NotificationBell />)
    await openBell()

    expect(screen.queryByRole('button', { name: 'Tout effacer' })).not.toBeInTheDocument()
  })
})

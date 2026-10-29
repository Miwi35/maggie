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
const redirect = vi.fn()
let listed: unknown[] = []
let onMercure: (data?: string) => void = () => {}

vi.mock('react-admin', () => ({
  useGetList: () => ({ data: listed, refetch }),
  useDataProvider: () => ({ update, delete: remove }),
  useRedirect: () => redirect,
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
    redirect.mockReset()
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

describe('NotificationBell — opening what a notification is about', () => {
  beforeEach(() => {
    refetch.mockReset()
    update.mockReset()
    redirect.mockReset()
  })

  test.each([
    ['a task due', 'Dans 3 j : Appeler le garage', '/api/tasks/01K0TASK', '/tasks?task=01K0TASK'],
    ['a budget alert', 'Enveloppe dépassée', '/finance/budgets', '/envelopes'],
    ['a message of Maggie', 'Maggie a écrit', '/chat?message=01K0MSG', '/chat?message=01K0MSG'],
  ])('%s opens the admin screen of its IRI, not the raw API path', async (_, title, relatedEntityIri, path) => {
    const read = { ...dentist, title, relatedEntityIri, readAt: '2026-10-07T10:05:00+00:00' }
    listed = [read]
    render(<NotificationBell />)

    await openBell()
    await userEvent.click(screen.getByText(title))

    expect(redirect).toHaveBeenCalledExactlyOnceWith(path)
  })

  test('a notification with no IRI opens nothing', async () => {
    listed = [{ ...dentist, readAt: '2026-10-07T10:05:00+00:00' }]
    render(<NotificationBell />)

    await openBell()
    await userEvent.click(screen.getByText('Dentiste demain'))

    expect(redirect).not.toHaveBeenCalled()
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

describe('NotificationBell — answering a task reminder (MAG-510)', () => {
  const fetchMock = vi.fn()
  const reminder = {
    ...dentist,
    id: iri('01K0NUDGE'),
    type: 'task_nudge',
    title: 'Appeler le garage, ça te prend 5 minutes',
    relatedEntityIri: '/api/tasks/01K0TASK',
    reminderId: '01K0REMIND',
    actions: ['start', 'in10', 'tonight'],
  }
  const answer = (notification: unknown) => ({
    ok: true,
    status: 200,
    json: async () => ({ reminder: {}, next: null, reward: null, notification }),
  })
  const posted = () => fetchMock.mock.calls.map(([url, init]) => [url, init?.method, JSON.parse(String(init?.body))])

  beforeEach(() => {
    refetch.mockReset()
    redirect.mockReset()
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    listed = [reminder, film]
  })

  test('shows its answers under the title, in the given order; a plain notification has none', async () => {
    render(<NotificationBell />)
    await openBell()

    const names = ["Je m'y mets", 'Dans 10 min', 'Ce soir']
    for (const name of names) expect(screen.getByRole('button', { name })).toBeInTheDocument()
    const shown = screen.getAllByRole('button').map((b) => b.textContent ?? '')
    expect(shown.filter((t) => names.includes(t))).toEqual(names)
    expect(screen.queryByRole('button', { name: 'Fait' })).toBeNull()
  })

  test('shows its body under the title, as the phone does', async () => {
    listed = [{ ...reminder, body: 'Premier pas : Trouver le numéro' }, film]
    render(<NotificationBell />)
    await openBell()

    expect(screen.getByRole('button', { name: new RegExp(reminder.title) })).toHaveTextContent('Premier pas : Trouver le numéro')
  })

  test.each([
    ["Je m'y mets", 'start'],
    ['Dans 10 min', 'in10'],
    ['Ce soir', 'tonight'],
  ])('« %s » posts { action: %s } to the reminder', async (name, action) => {
    fetchMock.mockResolvedValue(answer({ id: '01K0NUDGE', title: reminder.title, body: null, actions: [] }))
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name }))

    expect(posted()).toEqual([['/api/task-reminders/01K0REMIND/action', 'POST', { action }]])
    // The button answers; it does not also open the notification.
    expect(update).not.toHaveBeenCalled()
  })

  test('« Dans 10 min » takes the notification out of the bell: it comes back later', async () => {
    fetchMock.mockResolvedValue(answer(null))
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Dans 10 min' }))

    expect(screen.queryByText(reminder.title)).toBeNull()
    expect(screen.getByText('Votre film commence')).toBeInTheDocument()
    expect(redirect).not.toHaveBeenCalled()
  })

  test('« Fait » shows the bravo in place of the reminder, read, with no buttons left', async () => {
    listed = [{ ...reminder, actions: ['done', 'tomorrow'] }]
    const bravo = "Étoile filante : celle-là patientait depuis 33 jours. C'est réglé."
    fetchMock.mockResolvedValue(answer({ id: '01K0NUDGE', title: bravo, body: null, actions: [] }))
    render(<NotificationBell />)
    await openBell()
    expect(unread()).toBe('1')

    await userEvent.click(screen.getByRole('button', { name: 'Fait' }))

    expect(posted()).toEqual([['/api/task-reminders/01K0REMIND/action', 'POST', { action: 'done' }]])
    expect(await screen.findByText(bravo)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Fait' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Demain' })).toBeNull()
    expect(unread()).toBe('')
  })

  test("« Je m'y mets » opens the task and closes the bell", async () => {
    fetchMock.mockResolvedValue(answer({ id: '01K0NUDGE', title: reminder.title, body: null, actions: [] }))
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: "Je m'y mets" }))

    expect(redirect).toHaveBeenCalledExactlyOnceWith('/tasks?task=01K0TASK')
    // Read: only the film is left unread.
    expect(unread()).toBe('1')
  })

  test('a refused answer (409) says why and keeps the reminder as it was', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 409, json: async () => ({ error: 'Ce soir est déjà passé.' }) })
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Ce soir' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ce soir est déjà passé.')
    expect(screen.getByText(reminder.title)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ce soir' })).toBeEnabled()
  })
})

describe('NotificationBell — a routine reminder (MAG-512)', () => {
  const fetchMock = vi.fn()
  const routine = {
    ...dentist,
    id: iri('01K0ROUTINE'),
    type: 'routine',
    title: 'Routines · soir',
    body: 'Lire dix pages',
    relatedEntityIri: '/tasks',
    reminderId: '01K0REMIND',
    actions: ['done', 'in10', 'open'],
  }
  const answer = (notification: unknown) => ({
    ok: true,
    status: 200,
    json: async () => ({ reminder: {}, next: null, reward: null, notification }),
  })
  const posted = () => fetchMock.mock.calls.map(([url, init]) => [url, init?.method, JSON.parse(String(init?.body))])

  beforeEach(() => {
    redirect.mockReset()
    update.mockReset()
    update.mockResolvedValue({ data: {} })
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    listed = [routine]
  })

  test('offers « Fait », « Dans 10 min » and « Voir », in that order, for one routine', async () => {
    render(<NotificationBell />)
    await openBell()

    const names = ['Fait', 'Dans 10 min', 'Voir']
    expect(screen.getAllByRole('button').map((b) => b.textContent ?? '').filter((t) => names.includes(t))).toEqual(names)
  })

  test('offers « Voir » and « Dans 10 min », no « Fait », for several routines', async () => {
    listed = [{ ...routine, actions: ['open', 'in10'] }]
    render(<NotificationBell />)
    await openBell()

    const names = ['Voir', 'Dans 10 min']
    expect(screen.getAllByRole('button').map((b) => b.textContent ?? '').filter((t) => [...names, 'Fait'].includes(t))).toEqual(names)
  })

  test('« Voir » posts { action: open } and opens the tasks', async () => {
    fetchMock.mockResolvedValue(answer({ id: '01K0ROUTINE', title: routine.title, body: routine.body, actions: [] }))
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Voir' }))

    expect(posted()).toEqual([['/api/task-reminders/01K0REMIND/action', 'POST', { action: 'open' }]])
    expect(redirect).toHaveBeenCalledExactlyOnceWith('/tasks')
  })

  test('« Fait » ticks in place and stays on the bell', async () => {
    fetchMock.mockResolvedValue(answer({ id: '01K0ROUTINE', title: 'Routines · soir', body: 'Tout est fait', actions: [] }))
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Fait' }))

    expect(posted()).toEqual([['/api/task-reminders/01K0REMIND/action', 'POST', { action: 'done' }]])
    expect(await screen.findByText('Tout est fait')).toBeInTheDocument()
    expect(redirect).not.toHaveBeenCalled()
  })

  test('clicking the notification itself opens the tasks', async () => {
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: /Routines · soir/ }))

    expect(redirect).toHaveBeenCalledExactlyOnceWith('/tasks')
  })

  test('a failed « Voir » says so and does not navigate', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500, json: async () => ({}) })
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Voir' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de répondre au rappel.')
    expect(redirect).not.toHaveBeenCalled()
  })
})

describe('NotificationBell — a missing product (MAG-570)', () => {
  const fetchMock = vi.fn()
  const shortage = {
    ...dentist,
    id: iri('01K0SHORT'),
    type: 'grocery',
    title: 'Il manque : parmesan',
    body: 'Pour Pâtes carbonara, ce soir.',
    relatedEntityIri: '/api/shortage-alerts/01K0SHORTAGE0000000000000A',
    reminderId: null,
    actions: ['add'],
  }
  const url = '/api/shortage-alerts/01K0SHORTAGE0000000000000A/action'

  beforeEach(() => {
    refetch.mockReset()
    update.mockReset()
    redirect.mockReset()
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
    listed = [shortage, film]
  })

  test('shows « Ajouter à la liste » under the notification; a plain one has none', async () => {
    render(<NotificationBell />)
    await openBell()

    expect(screen.getAllByRole('button', { name: 'Ajouter à la liste' })).toHaveLength(1)
  })

  test('posts { action: add } to the alert and takes the notification out of the bell', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({ alert: { resolved: true }, notification: null }) })
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Ajouter à la liste' }))

    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(url, expect.objectContaining({ method: 'POST', body: JSON.stringify({ action: 'add' }) }))
    expect(screen.queryByText(shortage.title)).toBeNull()
    expect(screen.getByText('Votre film commence')).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()
    expect(redirect).not.toHaveBeenCalled()
  })

  test('an alert already settled (409) says why and keeps the notification', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 409, json: async () => ({ error: 'Déjà ajouté à la liste.' }) })
    render(<NotificationBell />)
    await openBell()

    await userEvent.click(screen.getByRole('button', { name: 'Ajouter à la liste' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Déjà ajouté à la liste.')
    expect(screen.getByText(shortage.title)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ajouter à la liste' })).toBeEnabled()
  })

  test('disappears when Mercure says the shortage is settled', async () => {
    render(<NotificationBell />)
    await openBell()

    emit({ '@id': shortage.id, deleted: true })

    expect(screen.queryByText(shortage.title)).toBeNull()
  })
})

describe('NotificationBell — a cooking timer that rang (MAG-582)', () => {
  const rang = {
    ...dentistPayload,
    id: '01K0TIMR',
    type: 'cooking_timer',
    title: "Pâtes : c'est prêt",
    body: "minuteur de l'étape 3 · Pâtes aux légumes",
    relatedEntityIri: '/api/cooking/timers/01K0TIMER000000000000000A',
    createdAt: '2026-10-25T19:00:00+00:00',
  }

  beforeEach(() => {
    refetch.mockReset()
    update.mockReset()
    redirect.mockReset()
    listed = []
  })

  test('is listed with its body, unread, and opens no screen', async () => {
    update.mockResolvedValue({ data: { ...rang, id: iri('01K0TIMR'), readAt: '2026-10-25T19:01:00+00:00' } })
    render(<NotificationBell />)

    emit({ '@id': iri('01K0TIMR'), ...rang })

    expect(unread()).toBe('1')
    await openBell()
    expect(screen.getByText("Pâtes : c'est prêt")).toBeInTheDocument()
    expect(screen.getByText("minuteur de l'étape 3 · Pâtes aux légumes")).toBeInTheDocument()
    await userEvent.click(screen.getByText("Pâtes : c'est prêt"))
    expect(update).toHaveBeenCalledWith('notifications', expect.objectContaining({ id: iri('01K0TIMR') }))
    expect(redirect).not.toHaveBeenCalled()
  })

  test('leaves the unread count once « OK » in the activity marks it read', () => {
    render(<NotificationBell />)
    emit({ '@id': iri('01K0TIMR'), ...rang })

    emit({ '@id': iri('01K0TIMR'), readAt: '2026-10-25T19:01:00+00:00' })

    expect(unread()).toBe('')
  })
})

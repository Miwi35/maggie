import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { GoogleCalendarSettings } from './GoogleCalendarSettings'

const notify = vi.fn()

/**
 * One object, not a fresh one per render: `loadData` is a `useCallback` keyed on
 * the identity, so a new reference on every render would refire the effect that
 * calls it and the screen would never leave its spinner.
 */
const identity = { id: 'u1', fullName: 'Owner' }

vi.mock('react-admin', () => ({
  useNotify: () => notify,
  useGetIdentity: () => ({ identity }),
}))

/**
 * Choosing the Google Tasks list to sync with (MAG-118).
 *
 * This screen called `/calendar/google/task-lists`, `connect-tasks` and
 * `disconnect-tasks` long before any of them existed: the select was always
 * empty and "Connecter" posted into the void, while the API synced whichever
 * list Google returned first. The endpoints exist now, and what is asserted
 * here is that the screen asks the question the account actually poses — one
 * list is stated, several are offered.
 */

const ONE_LIST = [{ id: 'list-chores', title: 'Mes tâches' }]
const TWO_LISTS = [
  { id: 'list-chores', title: 'Mes tâches' },
  { id: 'list-courses', title: 'Courses' },
]

interface ApiState {
  taskLists?: unknown[]
  taskListsStatus?: number
  googleTaskListId?: string | null
  connectStatus?: number
  disconnectStatus?: number
}

function stubApi({
  taskLists = ONE_LIST,
  taskListsStatus = 200,
  googleTaskListId = null,
  connectStatus = 200,
  disconnectStatus = 200,
}: ApiState = {}) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)

    if (url.endsWith('/calendar/google/task-lists')) {
      return taskListsStatus === 200
        ? new Response(JSON.stringify(taskLists))
        : new Response(JSON.stringify({ error: 'Google Tasks not authorized.' }), { status: taskListsStatus })
    }
    if (url.endsWith('/users/me')) {
      return new Response(JSON.stringify({ id: 'u1', googleTaskListId }))
    }
    if (url.endsWith('/calendar/google/connect-tasks')) {
      const body = JSON.parse(String(init?.body)) as { googleTaskListId: string }
      return connectStatus === 200
        ? new Response(JSON.stringify({ googleTaskListId: body.googleTaskListId, title: 'Courses' }))
        : new Response(JSON.stringify({ error: 'Google Tasks list not found.' }), { status: connectStatus })
    }
    if (url.endsWith('/calendar/google/disconnect-tasks')) {
      return disconnectStatus === 200
        ? new Response(JSON.stringify({ googleTaskListId: null }))
        : new Response(JSON.stringify({ error: 'Erreur serveur' }), { status: disconnectStatus })
    }

    throw new Error(`Unexpected request: ${init?.method ?? 'GET'} ${url}`)
  })

  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const posted = (fetchMock: ReturnType<typeof stubApi>, path: string) =>
  fetchMock.mock.calls
    .filter(([input, init]) => init?.method === 'POST' && String(input).endsWith(path))
    .map(([, init]) => (init?.body ? JSON.parse(String(init.body)) : null))

describe('GoogleCalendarSettings — the Google Tasks list', () => {
  beforeEach(() => {
    notify.mockClear()
  })

  test('states the only list of the account instead of offering a menu', async () => {
    stubApi({ taskLists: ONE_LIST })
    render(<GoogleCalendarSettings />)

    expect(await screen.findByText(/Liste Google Tasks\s*:\s*«\s*Mes tâches\s*»/)).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  test('connects the only list of the account in one click', async () => {
    const fetchMock = stubApi({ taskLists: ONE_LIST })
    render(<GoogleCalendarSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Connecter' }))

    await waitFor(() =>
      expect(posted(fetchMock, '/calendar/google/connect-tasks')).toEqual([{ googleTaskListId: 'list-chores' }]),
    )
    expect(notify).toHaveBeenCalledWith('Tâches synchronisées avec Google Tasks', { type: 'success' })
    expect(await screen.findByText(/Synchronisée avec\s*«\s*Mes tâches\s*»/)).toBeInTheDocument()
  })

  test('offers the choice when the account holds several lists', async () => {
    const fetchMock = stubApi({ taskLists: TWO_LISTS })
    render(<GoogleCalendarSettings />)

    await userEvent.click(await screen.findByRole('combobox'))
    await userEvent.click(await screen.findByRole('option', { name: 'Courses' }))
    await userEvent.click(screen.getByRole('button', { name: 'Connecter' }))

    await waitFor(() =>
      expect(posted(fetchMock, '/calendar/google/connect-tasks')).toEqual([{ googleTaskListId: 'list-courses' }]),
    )
    expect(await screen.findByText(/Synchronisée avec\s*«\s*Courses\s*»/)).toBeInTheDocument()
  })

  test('nothing is posted until a list is picked out of several', async () => {
    const fetchMock = stubApi({ taskLists: TWO_LISTS })
    render(<GoogleCalendarSettings />)

    expect(await screen.findByRole('button', { name: 'Connecter' })).toBeDisabled()
    expect(posted(fetchMock, '/calendar/google/connect-tasks')).toEqual([])
  })

  test('shows the list already chosen, and disconnects it', async () => {
    const fetchMock = stubApi({ taskLists: TWO_LISTS, googleTaskListId: 'list-courses' })
    render(<GoogleCalendarSettings />)

    expect(await screen.findByText(/Synchronisée avec\s*«\s*Courses\s*»/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Déconnecter' }))

    await waitFor(() => expect(posted(fetchMock, '/calendar/google/disconnect-tasks')).toEqual([null]))
    expect(notify).toHaveBeenCalledWith('Tâches déconnectées de Google Tasks', { type: 'success' })
    expect(await screen.findByRole('combobox')).toBeInTheDocument()
  })

  /**
   * The chosen list vanished from the account — the deleted-on-Google case.
   *
   * The API drops the choice at the next sync; the owner is on the screen now,
   * so the screen says so and offers the lists that are left rather than
   * displaying a raw identifier as if everything were fine.
   */
  test('warns when the chosen list is gone from Google and offers the others', async () => {
    stubApi({ taskLists: TWO_LISTS, googleTaskListId: 'list-deleted' })
    render(<GoogleCalendarSettings />)

    expect(await screen.findByText(/n’existe plus sur Google/)).toBeInTheDocument()
    expect(screen.getByRole('combobox')).toBeInTheDocument()
    expect(screen.queryByText('list-deleted')).not.toBeInTheDocument()
  })

  test('asks for a Google account rather than an empty menu when nothing is authorized', async () => {
    stubApi({ taskListsStatus: 403 })
    render(<GoogleCalendarSettings />)

    expect(await screen.findByText(/Connectez votre compte Google/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Connecter' })).not.toBeInTheDocument()
  })

  /**
   * A failed fetch is not an empty account, and not a deleted list.
   *
   * Both wrong statements come from the same place: no list on screen. The
   * screen says what it knows instead — the lists did not load.
   */
  test('says the lists did not load rather than inventing an empty account', async () => {
    stubApi({ taskListsStatus: 500 })
    render(<GoogleCalendarSettings />)

    expect(await screen.findByText(/n’ont pas pu être chargées/)).toBeInTheDocument()
    expect(screen.queryByText(/Aucune liste Google Tasks/)).not.toBeInTheDocument()
    expect(notify).toHaveBeenCalledWith('Erreur lors du chargement des listes Google Tasks', {
      type: 'error',
    })
  })

  test('never claims a stored list is gone when the lists did not load', async () => {
    stubApi({ taskListsStatus: 500, googleTaskListId: 'list-chores' })
    render(<GoogleCalendarSettings />)

    expect(await screen.findByText(/Synchronisée avec\s*«\s*list-chores\s*»/)).toBeInTheDocument()
    expect(screen.queryByText(/n’existe plus sur Google/)).not.toBeInTheDocument()
  })

  test('reports the error the API gives when connecting fails', async () => {
    stubApi({ taskLists: ONE_LIST, connectStatus: 404 })
    render(<GoogleCalendarSettings />)

    await userEvent.click(await screen.findByRole('button', { name: 'Connecter' }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Google Tasks list not found.', { type: 'error' }),
    )
    expect(screen.queryByText(/Synchronisée avec/)).not.toBeInTheDocument()
  })
})

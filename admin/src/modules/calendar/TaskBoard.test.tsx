import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskBoard } from './TaskBoard'
import type { ViewTask } from './taskViews'

const mockNotify = vi.fn()
const dataProvider = { getList: vi.fn().mockResolvedValue({ data: [], total: 0 }), update: vi.fn(), create: vi.fn() }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => mockNotify,
}))

class MockEventSource {
  static last: MockEventSource | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    MockEventSource.last = this
  }
}

const task = (id: string, title: string, extra: Partial<ViewTask> = {}): ViewTask => ({
  id,
  '@id': `/api/tasks/${id}`,
  title,
  priority: 'medium',
  criticality: 'low',
  dueDate: null,
  completedAt: null,
  parent: null,
  urgency: 'normal',
  orderScore: 50,
  ...extra,
})

const COUNTS = { today: 2, upcoming: 1, someday: 1, done: 1 }

const VIEWS: Record<string, ViewTask[]> = {
  today: [
    task('01LATE', 'Renvoyer le formulaire', { dueDate: '2026-10-12', overdueDays: 3, urgency: 'high' }),
    task('01MOVING', 'Préparer le déménagement', { dueDate: '2026-10-15' }),
    task('01BOXES', 'Réserver le camion', { parent: '/api/tasks/01MOVING' }),
  ],
  upcoming: [task('01SOON', 'Appeler le garage', { dueDate: '2026-10-20' })],
  someday: [task('01SOMEDAY', 'Trier les photos')],
  done: [task('01DONE', 'Sortir les poubelles', { completedAt: '2026-10-14T10:00:00+00:00' })],
}

const viewResponse = (view: string, member = VIEWS[view], counts = COUNTS) =>
  new Response(JSON.stringify({ '@type': 'Collection', totalItems: member.length, counts, member }), {
    status: 200,
    headers: { 'Content-Type': 'application/ld+json' },
  })

const mockFetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()

const calls = (method = 'GET') =>
  mockFetch.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === method).map(([url]) => String(url))

const respondFromViews = () =>
  mockFetch.mockImplementation((url, init) => {
    const match = /\/tasks\/view\/(\w+)/.exec(String(url))
    if (match) return Promise.resolve(viewResponse(match[1]))
    return Promise.resolve(new Response('{}', { status: init?.method === 'POST' ? 201 : 200 }))
  })

describe('TaskBoard — the four views (MAG-408)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 10, 0))
    vi.stubGlobal('fetch', mockFetch)
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    respondFromViews()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('draws the four tabs with their counts and « Aujourd\'hui » first', async () => {
    render(<TaskBoard />)

    expect(await screen.findByRole('tab', { name: "Aujourd'hui (2)" })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'À venir (1)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'À faire un jour (1)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Faites (1)' })).toBeInTheDocument()
    expect(calls()).toEqual(['http://localhost/api/tasks/view/today'])
  })

  test('shows urgency, lateness, the original due day, and a subtask under its task', async () => {
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    const late = screen.getByText('Renvoyer le formulaire').closest('li')!
    expect(late).toHaveTextContent('En retard de 3 j')
    expect(late).toHaveTextContent('prévue le 12 oct.')
    expect(within(late).getByTestId('urgency-badge')).toHaveAttribute('data-urgency', 'high')

    const titles = within(screen.getByRole('list', { name: "Aujourd'hui" }))
      .getAllByRole('listitem')
      .map((li) => li.textContent)
    expect(titles[1]).toContain('Préparer le déménagement')
    expect(titles[2]).toContain('Réserver le camion')
    expect(screen.getByText('Réserver le camion').closest('li')).toHaveStyle({ paddingLeft: '32px' })
    expect(screen.getByText('Préparer le déménagement').closest('li')).not.toHaveStyle({ paddingLeft: '32px' })
  })

  test('switching tab fetches that view, and « Faites » asks for its first page', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    await user.click(screen.getByRole('tab', { name: 'À venir (1)' }))
    expect(await screen.findByText('Appeler le garage')).toBeInTheDocument()
    expect(screen.queryByText('Renvoyer le formulaire')).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'À faire un jour (1)' }))
    expect(await screen.findByText('Trier les photos')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Faites (1)' }))
    expect(await screen.findByText('Sortir les poubelles')).toBeInTheDocument()
    expect(calls()).toEqual([
      'http://localhost/api/tasks/view/today',
      'http://localhost/api/tasks/view/upcoming',
      'http://localhost/api/tasks/view/someday',
      'http://localhost/api/tasks/view/done?page=1&itemsPerPage=30',
    ])
  })

  test('ticking a task sends a merge-patch on completedAt and refetches the view', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    await user.click(screen.getByRole('checkbox', { name: 'Terminer Renvoyer le formulaire' }))

    await waitFor(() => expect(calls()).toHaveLength(2))
    const [url, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
    expect(url).toBe('http://localhost/api/tasks/01LATE')
    expect((init!.headers as Record<string, string>)['Content-Type']).toBe('application/merge-patch+json')
    expect(JSON.parse(init!.body as string)).toEqual({ completedAt: '2026-10-15T08:00:00.000Z' })
    expect(mockNotify).toHaveBeenCalledWith('Tâche terminée', { type: 'success' })
  })

  test('unticking a done task sends completedAt null', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')
    await user.click(screen.getByRole('tab', { name: 'Faites (1)' }))

    const box = await screen.findByRole('checkbox', { name: 'Rouvrir Sortir les poubelles' })
    expect(box).toBeChecked()
    await user.click(box)

    await waitFor(() => expect(mockFetch.mock.calls.some(([, i]) => i?.method === 'PATCH')).toBe(true))
    const [, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
    expect(JSON.parse(init!.body as string)).toEqual({ completedAt: null })
  })

  test('a failing tick says so and leaves the list as it was', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    mockFetch.mockImplementation((url, init) => {
      if (init?.method === 'PATCH') return Promise.resolve(new Response('{}', { status: 500 }))
      return Promise.resolve(viewResponse(/view\/(\w+)/.exec(String(url))![1]))
    })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    await user.click(screen.getByRole('checkbox', { name: 'Terminer Renvoyer le formulaire' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Tâche : 500', { type: 'error' }))
    expect(screen.getByText('Renvoyer le formulaire')).toBeInTheDocument()
  })

  test('Enter creates the task due today in « Aujourd\'hui » and refetches', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    await user.type(screen.getByRole('textbox', { name: 'Nouvelle tâche' }), 'Acheter du pain{Enter}')

    await waitFor(() => expect(calls('POST')).toHaveLength(1))
    const [, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'POST')!
    expect(JSON.parse(init!.body as string)).toEqual({ title: 'Acheter du pain', dueDate: '2026-10-15' })
    await waitFor(() => expect(calls()).toHaveLength(2))
    expect(screen.getByRole('textbox', { name: 'Nouvelle tâche' })).toHaveValue('')
  })

  test('the quick create defaults to tomorrow in « À venir » and to no date in « À faire un jour »', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    await user.click(screen.getByRole('tab', { name: 'À venir (1)' }))
    await user.type(await screen.findByRole('textbox', { name: 'Nouvelle tâche' }), 'Réserver le train{Enter}')
    await user.click(screen.getByRole('tab', { name: /À faire un jour/ }))
    await user.type(await screen.findByRole('textbox', { name: 'Nouvelle tâche' }), 'Ranger le garage{Enter}')

    await waitFor(() => expect(calls('POST')).toHaveLength(2))
    const bodies = mockFetch.mock.calls.filter(([, i]) => i?.method === 'POST').map(([, i]) => JSON.parse(i!.body as string))
    expect(bodies).toEqual([
      { title: 'Réserver le train', dueDate: '2026-10-16' },
      { title: 'Ranger le garage' },
    ])
  })

  test('a blank title creates nothing, and « Faites » has no quick create', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    await user.type(screen.getByRole('textbox', { name: 'Nouvelle tâche' }), '   {Enter}')
    expect(calls('POST')).toHaveLength(0)

    await user.click(screen.getByRole('tab', { name: 'Faites (1)' }))
    await screen.findByText('Sortir les poubelles')
    expect(screen.queryByRole('textbox', { name: 'Nouvelle tâche' })).not.toBeInTheDocument()
  })

  test('opens the detail of a task in the edit form', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    render(<TaskBoard />)

    await user.click(await screen.findByText('Renvoyer le formulaire'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Modifier la tâche' })).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/Titre/)).toHaveValue('Renvoyer le formulaire')
  })

  test.each([
    ['today', "Aujourd'hui", "Rien à faire aujourd'hui"],
    ['upcoming', 'À venir', 'Aucune tâche à venir'],
    ['someday', 'À faire un jour', 'Aucune tâche pour plus tard'],
    ['done', 'Faites', 'Aucune tâche terminée'],
  ])('says so when the « %s » view is empty', async (view, label, message) => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    mockFetch.mockImplementation((url) => {
      const name = /view\/(\w+)/.exec(String(url))![1]
      return Promise.resolve(viewResponse(name, name === view ? [] : VIEWS[name], { today: 2, upcoming: 1, someday: 1, done: 1, [view]: 0 }))
    })
    render(<TaskBoard />)
    await screen.findByRole('tab', { name: /Aujourd'hui/ })

    if (view !== 'today') await user.click(screen.getByRole('tab', { name: new RegExp(label) }))

    expect(await screen.findByText(message)).toBeInTheDocument()
  })

  test('shows an error, not a crash, when the API fails', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    mockFetch.mockResolvedValue(new Response('{"error":"boom"}', { status: 500 }))
    render(<TaskBoard />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Tâches indisponibles')
    expect(screen.getByRole('tab', { name: "Aujourd'hui" })).toBeInTheDocument()
  })

  test('a network failure on another tab shows the error too, and the next tab recovers', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')

    mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await user.click(screen.getByRole('tab', { name: 'À venir (1)' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Tâches indisponibles')

    await user.click(screen.getByRole('tab', { name: 'À faire un jour (1)' }))
    expect(await screen.findByText('Trier les photos')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('refetches the active view on a Mercure task update', async () => {
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')
    expect(decodeURIComponent(MockEventSource.last!.url)).toContain('/users/U1/api/tasks/:id')

    MockEventSource.last!.onmessage!({ data: '{}' } as MessageEvent)

    await waitFor(() => expect(calls()).toHaveLength(2))
  })

  test('« Faites » is paged: a second page is asked for from the pager', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    mockFetch.mockImplementation((url) => {
      const name = /view\/(\w+)/.exec(String(url))![1]
      if (name !== 'done') return Promise.resolve(viewResponse(name))
      return Promise.resolve(
        new Response(
          JSON.stringify({ totalItems: 45, counts: COUNTS, page: 1, itemsPerPage: 30, member: VIEWS.done }),
          { status: 200 },
        ),
      )
    })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')
    await user.click(screen.getByRole('tab', { name: 'Faites (1)' }))
    await screen.findByText('Sortir les poubelles')

    await user.click(screen.getByRole('button', { name: /page 2/i }))

    await waitFor(() => expect(calls()).toContain('http://localhost/api/tasks/view/done?page=2&itemsPerPage=30'))
  })

  test('« Faites » goes back a page when the last one is emptied', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    let doneTotal = 31
    mockFetch.mockImplementation((url, init) => {
      const match = /view\/(\w+)(\?page=(\d+))?/.exec(String(url))
      if (!match) return Promise.resolve(new Response('{}', { status: init?.method === 'PATCH' ? 200 : 201 }))
      if (match[1] !== 'done') return Promise.resolve(viewResponse(match[1]))
      const onPage2 = match[3] === '2'
      const member = onPage2 ? (doneTotal > 30 ? [task('01LAST', 'Dernière faite', { completedAt: '2026-10-01T10:00:00+00:00' })] : []) : VIEWS.done
      return Promise.resolve(new Response(JSON.stringify({ totalItems: doneTotal, counts: COUNTS, member }), { status: 200 }))
    })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')
    await user.click(screen.getByRole('tab', { name: 'Faites (1)' }))
    await screen.findByText('Sortir les poubelles')
    await user.click(screen.getByRole('button', { name: /page 2/i }))
    await screen.findByText('Dernière faite')

    doneTotal = 30
    await user.click(within(screen.getByText('Dernière faite').closest('li') as HTMLElement).getByRole('checkbox'))

    await screen.findByText('Sortir les poubelles')
    expect(screen.queryByText('Aucune tâche terminée')).toBeNull()
  })

  test('a tick answered after a tab switch reloads the tab shown, not the one ticked on', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    let release: (response: Response) => void = () => {}
    mockFetch.mockImplementation((url, init) => {
      const match = /\/tasks\/view\/(\w+)/.exec(String(url))
      if (match) return Promise.resolve(viewResponse(match[1]))
      if (init?.method === 'PATCH') return new Promise<Response>((resolve) => (release = resolve))
      return Promise.resolve(new Response('{}', { status: 200 }))
    })
    render(<TaskBoard />)
    await screen.findByText('Renvoyer le formulaire')
    await user.click(within(screen.getByText('Renvoyer le formulaire').closest('li') as HTMLElement).getByRole('checkbox'))
    await user.click(screen.getByRole('tab', { name: /À venir/ }))
    await screen.findByText('Appeler le garage')

    release(new Response('{}', { status: 200 }))

    await waitFor(() => expect(calls().filter((url) => url.includes('/view/upcoming')).length).toBeGreaterThan(1))
    expect(screen.getByText('Appeler le garage')).toBeTruthy()
    expect(screen.queryByText('Renvoyer le formulaire')).toBeNull()
  })
})

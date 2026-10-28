import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render as renderInto, screen, waitFor, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { TaskBoard } from './TaskBoard'
import type { ViewTask } from './taskViews'
import { resetMorningOffer } from './dayPlan'
import type { DayTask } from './dayPlan'
import type { Reward } from './reward'

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
    // The board's own stream, not the routines band's (MAG-512).
    if (decodeURIComponent(url).includes('/api/tasks/')) MockEventSource.last = this
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

const dayTask = (id: string, title: string, extra: Partial<DayTask> = {}): DayTask => ({
  id,
  title,
  estimatedMinutes: null,
  firstStep: null,
  completedAt: null,
  dueDate: null,
  postponedSince: null,
  urgency: 'normal',
  criticality: 'low',
  ...extra,
})

const COUNTS = { today: 3, upcoming: 2, someday: 2, done: 1 }

const LATE = dayTask('01LATE', 'Rappeler le plombier', { dueDate: '2026-10-10' })

const VIEWS: Record<string, ViewTask[]> = {
  today: [],
  upcoming: [
    task('01MOVING', 'Préparer le déménagement', { dueDate: '2026-10-20' }),
    task('01BOXES', 'Réserver le camion', { parent: '/api/tasks/01MOVING' }),
  ],
  someday: [
    task('01FORM', 'Renvoyer le formulaire', { postponedSince: '2026-10-01', overdueDays: 14, dueDate: '2026-10-01', urgency: 'high' }),
    task('01FINE', "Payer l'amende", { postponedSince: '2026-10-01', overdueDays: 14, dueDate: '2026-10-01', urgency: 'critical' }),
  ],
  done: [task('01DONE', 'Sortir les poubelles', { completedAt: '2026-10-14T10:00:00+00:00' })],
}

// 2026-10-15 is a Thursday: the 12th a Monday, the 14th yesterday.
const BANK = dayTask('01BANK', 'Appeler la banque', { postponedSince: '2026-10-12', firstStep: 'Trouver le RIB', estimatedMinutes: 10 })
const CANTEEN = dayTask('01CANTEEN', 'Payer la cantine', { postponedSince: '2026-10-12', urgency: 'critical', criticality: 'critical' })
const WATER = dayTask('01WATER', 'Arroser')
const BOOK = dayTask('01BOOK', 'Rendre le livre', { postponedSince: '2026-10-14' })
const PHOTOS = dayTask('01PHOTOS', 'Trier les photos')

/** The day the mocked API holds; the PUT and the PATCH write it as the real one would. */
let state: { slots: DayTask[]; reserve: DayTask[] }
let morningTime: string | null = null
let eveningTime: string | null = null
let confirmedAt: string | null = null
let reviewedAt: string | null = null
/** The rewards the ticks lay down, by task id (MAG-508); none answers 404. */
let rewards: Record<string, Reward> = {}
let cheerSettings: Record<string, boolean> = {}
/** The stars of the month the API counts (MAG-509). */
let stars = 5

const describeDay = () => {
  const slots = state.slots.map((t, i) => ({ position: i + 1, type: 'task', task: t }))

  return {
    id: '01DAY',
    date: '2026-10-15',
    confirmedAt,
    slots,
    now: slots.find((slot) => slot.task.completedAt == null) ?? null,
    reserve: state.reserve,
    done: slots.filter((slot) => slot.task.completedAt != null).length,
    total: slots.length,
  }
}

/** The routines due on the 15th, as `GET /api/routines/day/{date}` sends them (MAG-512). */
let routinesDay: unknown = { date: '2026-10-15', moments: [] }

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

const viewResponse = (view: string, member = VIEWS[view], counts = COUNTS) =>
  new Response(JSON.stringify({ '@type': 'Collection', totalItems: member.length, counts, member }), {
    status: 200,
    headers: { 'Content-Type': 'application/ld+json' },
  })

const mockFetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()

const respond = (url: string, init?: RequestInit): Response => {
  const method = init?.method ?? 'GET'
  const view = /\/tasks\/view\/(\w+)/.exec(url)
  if (view) return viewResponse(view[1])

  if (url.endsWith('/routines/day/2026-10-15')) return json(routinesDay)

  if (url.endsWith('/task-settings/me'))
    return json({ ...(morningTime ? { morningTime } : {}), ...(eveningTime ? { eveningTime } : {}), ...cheerSettings })

  if (/\/stars(\?month=[\d-]+)?$/.test(url))
    return json({ month: '2026-10', count: stars, total: 12, best: null, latestAt: '2026-10-15T09:00:00+00:00' })

  const rewarded = /\/rewards\/task\/(\w+)$/.exec(url)
  if (rewarded) return rewards[rewarded[1]] ? json(rewards[rewarded[1]]) : json({ error: 'No reward' }, 404)

  if (url.endsWith('/day-reviews/2026-10-15')) {
    if (method === 'PUT') reviewedAt = '2026-10-15T19:10:00+00:00'

    return json({ id: '01REVIEW', date: '2026-10-15', helped: [], reviewedAt })
  }

  if (url.endsWith('/day-plans/2026-10-15/proposal')) {
    const entry = (t: DayTask, reason: string) => ({ reason, minutes: t.estimatedMinutes ?? 15, task: t })

    return json({
      date: '2026-10-15',
      slots: [BANK, CANTEEN].map((t, i) => ({ position: i + 1, type: 'task', ...entry(t, 'postponed') })),
      reserveRanked: [entry(WATER, 'whenever'), entry(BOOK, 'quick')],
      totalMinutes: 25,
      freeMinutes: 150,
    })
  }

  if (/\/day-plans\/2026-10-15$/.test(url)) {
    if (method === 'PUT') {
      confirmedAt = '2026-10-15T08:40:00+00:00'
      const ids = (JSON.parse(init!.body as string) as { slots: { taskId: string }[] }).slots.map((s) => s.taskId)
      const all = [...state.slots, ...state.reserve]
      state = {
        slots: ids.map((id) => all.find((t) => t.id === id)!),
        reserve: all.filter((t) => !ids.includes(t.id) && t.completedAt == null),
      }
    }

    return json(describeDay())
  }

  if (method === 'POST' && url.endsWith('/tasks')) {
    const created = dayTask('01NEW', (JSON.parse(init!.body as string) as { title: string }).title)
    state = { ...state, reserve: [created, ...state.reserve] }

    return json({ id: '01NEW', '@id': '/api/tasks/01NEW', title: created.title }, 201)
  }

  const item = /\/tasks\/(\w+)$/.exec(url)
  if (item && method === 'PATCH') {
    const { completedAt } = JSON.parse(init!.body as string) as { completedAt: string | null }
    state = { ...state, slots: state.slots.map((t) => (t.id === item[1] ? { ...t, completedAt } : t)) }

    return json({})
  }
  if (item) {
    const found = [...state.slots, ...state.reserve].find((t) => t.id === item[1])!

    return json(task(found.id, found.title, { firstStep: found.firstStep, estimatedMinutes: found.estimatedMinutes, description: 'Le RIB est dans le tiroir' }))
  }

  return json({})
}

/** Where the board is opened: `/tasks`, or `/tasks?task=<id>` from a notification or a search hit (MAG-640). */
let boardUrl = '/tasks'
const render = (ui: ReactElement) => renderInto(<MemoryRouter initialEntries={[boardUrl]}>{ui}</MemoryRouter>)

const calls = (method = 'GET') =>
  mockFetch.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === method).map(([url]) => String(url))

const viewCalls = () => calls().filter((url) => url.includes('/tasks/view/'))
const dayCalls = () => calls().filter((url) => url.includes('/day-plans/'))

const putBodies = () =>
  mockFetch.mock.calls
    .filter(([, init]) => init?.method === 'PUT')
    .map(([, init]) => (JSON.parse(init!.body as string) as { slots: { taskId: string }[] }).slots.map((s) => s.taskId))

const dayList = () => screen.getByRole('list', { name: "Aujourd'hui" })

const rowTitles = (list: HTMLElement = dayList()) => within(list).getAllByRole('listitem').map((li) => li.textContent)

const nowCard = () => screen.getByRole('region', { name: 'Maintenant' })

const play = vi.fn(() => Promise.resolve())
const AudioMock = vi.fn(function (this: { play: typeof play; volume: number }) {
  this.play = play
  this.volume = 1
})

const reward = (taskId: string, message: string, surprise: Reward['surprise'] = null): Reward => ({
  id: `01RW${taskId}`,
  subjectType: 'task',
  subjectId: taskId,
  level: surprise ? 'surprise' : 'standard',
  surprise,
  messageKey: surprise ? `${surprise}.1` : 'direct.3',
  message,
  title: null,
  star: true,
  earnedAt: '2026-10-15T08:00:00+00:00',
})

const setup = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

describe('TaskBoard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 10, 0))
    routinesDay = { date: '2026-10-15', moments: [] }
    window.location.hash = ''
    boardUrl = '/tasks'
    morningTime = null
    eveningTime = null
    confirmedAt = null
    reviewedAt = null
    rewards = {}
    cheerSettings = {}
    stars = 5
    play.mockClear()
    resetMorningOffer()
    vi.stubGlobal('fetch', mockFetch)
    vi.stubGlobal('EventSource', MockEventSource)
    vi.stubGlobal('Audio', AudioMock)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    state = { slots: [BANK, CANTEEN, WATER], reserve: [BOOK, PHOTOS] }
    mockFetch.mockImplementation((url, init) => Promise.resolve(respond(String(url), init)))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  describe('the page (MAG-644)', () => {
    test('is headed « Tâches · <day> », with the quick add and the way to « Routines »', async () => {
      render(<TaskBoard />)

      expect(await screen.findByText('Tâches · jeudi 15 octobre')).toBeInTheDocument()
      expect(screen.getByRole('textbox', { name: 'Ajouter une tâche' })).toHaveAttribute('placeholder', 'Appeler le garage demain')
      expect(screen.getByRole('link', { name: 'Routines' })).toHaveAttribute('href', '/tasks/routines')
    })

    test.each([
      ['/tasks/upcoming', 'upcoming'],
      ['/tasks/someday', 'someday'],
      ['/tasks/done', 'done'],
    ])('%s reads the « %s » view only, not the day', async (path, view) => {
      boardUrl = path
      render(<TaskBoard />)

      await waitFor(() => expect(viewCalls()).toHaveLength(1))
      expect(viewCalls()[0]).toContain(`/tasks/view/${view}`)
      expect(dayCalls()).toEqual([])
    })

    test('« Faites » asks for its first page', async () => {
      boardUrl = '/tasks/done'
      render(<TaskBoard />)

      expect(await screen.findByText('Sortir les poubelles')).toBeInTheDocument()
      expect(viewCalls()).toEqual(['http://localhost/api/tasks/view/done?page=1&itemsPerPage=30'])
    })

    test('a path the page does not know is the day', async () => {
      boardUrl = '/tasks/unknown'
      render(<TaskBoard />)

      expect(await screen.findByRole('list', { name: "Aujourd'hui" })).toBeInTheDocument()
    })
  })

  describe('« Aujourd\'hui » — the day of five (MAG-497)', () => {
    test('« Maintenant » holds the first task not done, with its first step and its minutes', async () => {
      render(<TaskBoard />)

      const card = await screen.findByRole('region', { name: 'Maintenant' })
      expect(card).toHaveTextContent('Appeler la banque')
      expect(card).toHaveTextContent('Trouver le RIB')
      expect(card).toHaveTextContent('10 min')
      expect(within(card).getByRole('button', { name: "C'est fait" })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: 'Juste 10 minutes' })).toBeInTheDocument()
    })

    test('the ring counts the done slots out of the day, in words too', async () => {
      render(<TaskBoard />)

      const ring = await screen.findByRole('img', { name: '0 sur 3' })
      expect(ring).toHaveTextContent('0 sur 3')
      expect(ring.querySelectorAll('path')).toHaveLength(3)
    })

    test('the list takes the slots in order, then the reserve: « reportée » in words, the critical mark instead, the minutes', async () => {
      render(<TaskBoard />)
      await screen.findByRole('list', { name: "Aujourd'hui" })

      expect(rowTitles()).toEqual([
        expect.stringContaining('Appeler la banque'),
        expect.stringContaining('Payer la cantine'),
        expect.stringContaining('Arroser'),
        expect.stringContaining('Rendre le livre'),
        expect.stringContaining('Trier les photos'),
      ])
      const [bank, canteen, water, book] = within(dayList()).getAllByRole('listitem')
      expect(bank).toHaveTextContent('reportée depuis lundi')
      expect(bank).toHaveTextContent('10 min')
      expect(bank).not.toHaveTextContent(/retard/i)
      expect(within(canteen).getByTestId('urgency-badge')).toHaveAttribute('data-urgency', 'critical')
      expect(canteen).not.toHaveTextContent('reportée')
      expect(within(water).getByTestId('urgency-badge')).toHaveAttribute('data-urgency', 'normal')
      expect(book).toHaveTextContent('reportée depuis hier')
      expect(screen.getByRole('heading', { name: "Aujourd'hui · 5" })).toBeInTheDocument()
    })

    test('draws the Urgence and Échéance columns, a day in the « Échéance » one', async () => {
      state = { slots: [{ ...BANK, dueDate: '2026-10-15' }, CANTEEN, WATER], reserve: [] }
      render(<TaskBoard />)
      await screen.findByRole('list', { name: "Aujourd'hui" })

      expect(screen.getByText('Urgence')).toBeInTheDocument()
      expect(screen.getByText('Échéance')).toBeInTheDocument()
      expect(within(dayList()).getAllByRole('listitem')[0]).toHaveTextContent('15 oct.')
    })

    test('the tasks due before today are apart, under « En retard »', async () => {
      state = { slots: [BANK, CANTEEN, WATER], reserve: [BOOK, LATE] }
      render(<TaskBoard />)

      const late = await screen.findByRole('list', { name: 'En retard' })
      expect(screen.getByRole('heading', { name: 'En retard · 1' })).toBeInTheDocument()
      expect(rowTitles(late)).toEqual([expect.stringContaining('Rappeler le plombier')])
      expect(within(late).getByRole('listitem')).toHaveTextContent('prévue le 10 oct.')
      expect(rowTitles()).toHaveLength(4)
      expect(screen.getByRole('heading', { name: "Aujourd'hui · 4" })).toBeInTheDocument()
    })

    test('ticking « Maintenant » sends the merge-patch, then the next task takes its place', async () => {
      const user = setup()
      render(<TaskBoard />)
      await screen.findByRole('region', { name: 'Maintenant' })

      await user.click(within(nowCard()).getByRole('button', { name: "C'est fait" }))

      await waitFor(() => expect(nowCard()).toHaveTextContent('Payer la cantine'))
      const [url, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
      expect(url).toBe('http://localhost/api/tasks/01BANK')
      expect((init!.headers as Record<string, string>)['Content-Type']).toBe('application/merge-patch+json')
      expect(JSON.parse(init!.body as string)).toEqual({ completedAt: '2026-10-15T08:00:00.000Z' })
      expect(screen.getByRole('img', { name: '1 sur 3' })).toBeInTheDocument()
      expect(screen.getByRole('checkbox', { name: 'Rouvrir Appeler la banque' })).toBeChecked()
      expect(mockNotify).toHaveBeenCalledWith('Tâche terminée', { type: 'success' })
    })

    test('a task is ticked in one gesture from its checkbox, the reserve ones too', async () => {
      const user = setup()
      render(<TaskBoard />)

      await user.click(await screen.findByRole('checkbox', { name: 'Terminer Arroser' }))

      await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Rouvrir Arroser' })).toBeChecked())
      expect(nowCard()).toHaveTextContent('Appeler la banque')

      await user.click(screen.getByRole('checkbox', { name: 'Terminer Rendre le livre' }))
      expect(mockFetch.mock.calls.filter(([, i]) => i?.method === 'PATCH').map(([url]) => url)).toContain('http://localhost/api/tasks/01BOOK')
    })

    test('a task done is unticked from the same checkbox', async () => {
      const user = setup()
      state = { slots: [{ ...BANK, completedAt: '2026-10-15T07:00:00+00:00' }, CANTEEN, WATER], reserve: [] }
      render(<TaskBoard />)

      await user.click(await screen.findByRole('checkbox', { name: 'Rouvrir Appeler la banque' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Tâche rouverte', { type: 'success' }))
      const [, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
      expect(JSON.parse(init!.body as string)).toEqual({ completedAt: null })
    })

    test('a click on a row opens its sheet in a panel beside the list; the cross closes it', async () => {
      const user = setup()
      render(<TaskBoard />)

      await user.click(await screen.findByRole('button', { name: 'Ouvrir Appeler la banque' }))

      const panel = await screen.findByRole('complementary', { name: 'Fiche de la tâche' })
      expect(within(panel).getByRole('heading', { name: 'Modifier la tâche' })).toBeInTheDocument()
      expect(within(panel).getByLabelText(/Titre/)).toHaveValue('Appeler la banque')
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(dayList()).toBeInTheDocument()

      await user.click(within(panel).getByRole('button', { name: 'Fermer la fiche' }))

      expect(screen.queryByRole('complementary', { name: 'Fiche de la tâche' })).toBeNull()
    })

    test('a task that cannot be read says so and opens nothing', async () => {
      const user = setup()
      mockFetch.mockImplementation((url, init) =>
        Promise.resolve(/\/tasks\/01WATER$/.test(String(url)) && !init?.method ? json({}, 500) : respond(String(url), init)),
      )
      render(<TaskBoard />)

      await user.click(await screen.findByRole('button', { name: 'Ouvrir Arroser' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Tâche : 500', { type: 'error' }))
      expect(screen.queryByRole('complementary', { name: 'Fiche de la tâche' })).toBeNull()
    })

    describe('the quick add (MAG-644)', () => {
      const bodies = () => mockFetch.mock.calls.filter(([, i]) => i?.method === 'POST').map(([, i]) => JSON.parse(i!.body as string))
      const field = () => screen.findByRole('textbox', { name: 'Ajouter une tâche' })

      test('a task without a day takes the first free place of the day', async () => {
        const user = setup()
        render(<TaskBoard />)

        await user.type(await field(), 'Acheter du pain{Enter}')

        await waitFor(() => expect(putBodies()).toEqual([['01BANK', '01CANTEEN', '01WATER', '01NEW']]))
        expect(bodies()).toEqual([{ title: 'Acheter du pain' }])
        await waitFor(() => expect(rowTitles()[3]).toContain('Acheter du pain'))
        expect(screen.getByRole('textbox', { name: 'Ajouter une tâche' })).toHaveValue('')
      })

      test('never rewrites a day it could not read: the field waits for it, and stays off when it fails', async () => {
        vi.spyOn(console, 'error').mockImplementation(() => {})
        mockFetch.mockImplementation((url, init) =>
          Promise.resolve(String(url).includes('/day-plans/') ? json({ error: 'boom' }, 500) : respond(String(url), init)),
        )
        render(<TaskBoard />)

        await screen.findByRole('alert')
        expect(await field()).toBeDisabled()
        expect(calls('PUT')).toHaveLength(0)
      })

      test('« Appeler le garage demain » is the task « Appeler le garage », due tomorrow, and not in today', async () => {
        const user = setup()
        render(<TaskBoard />)

        await user.type(await field(), 'Appeler le garage demain{Enter}')

        await waitFor(() => expect(bodies()).toEqual([{ title: 'Appeler le garage', dueDate: '2026-10-16' }]))
        await waitFor(() =>
          expect(mockNotify).toHaveBeenCalledWith('Tâche ajoutée pour le vendredi 16 octobre', { type: 'success' }),
        )
        expect(calls('PUT')).toHaveLength(0)
        expect(screen.getByRole('textbox', { name: 'Ajouter une tâche' })).toHaveValue('')
      })

      test('« aujourd\'hui » is a day of the day: the task is put in it', async () => {
        const user = setup()
        render(<TaskBoard />)

        await user.type(await field(), 'Poster le colis aujourd\'hui{Enter}')

        await waitFor(() => expect(putBodies()).toHaveLength(1))
        expect(bodies()).toEqual([{ title: 'Poster le colis', dueDate: '2026-10-15' }])
      })

      test('with the five taken, the task stays out of the day and shows in the list', async () => {
        const user = setup()
        state = {
          slots: [BANK, CANTEEN, WATER, dayTask('01ERRAND', 'Faire les courses'), dayTask('01MAIL', 'Poster la lettre')],
          reserve: [BOOK],
        }
        render(<TaskBoard />)

        await user.type(await field(), 'Acheter du pain{Enter}')

        expect(await screen.findByText('Acheter du pain')).toBeInTheDocument()
        expect(calls('POST')).toHaveLength(1)
        expect(calls('PUT')).toHaveLength(0)
      })

      test('a blank title creates nothing', async () => {
        const user = setup()
        render(<TaskBoard />)

        await user.type(await field(), '   {Enter}')

        expect(calls('POST')).toHaveLength(0)
      })

      test('a refused creation says why and keeps what was typed', async () => {
        const user = setup()
        mockFetch.mockImplementation((url, init) =>
          Promise.resolve(init?.method === 'POST' ? json({}, 500) : respond(String(url), init)),
        )
        render(<TaskBoard />)

        await user.type(await field(), 'Acheter du pain{Enter}')

        await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Tâche : 500', { type: 'error' }))
        expect(screen.getByRole('textbox', { name: 'Ajouter une tâche' })).toHaveValue('Acheter du pain')
      })
    })

    describe('the Focus (MAG-499)', () => {
      const focus = () => screen.getByRole('region', { name: 'Appeler la banque' })

      test('« Juste 10 minutes » opens the Focus of « Maintenant »: first step, ring at 10:00, back to the day', async () => {
        const user = setup()
        render(<TaskBoard />)
        await screen.findByRole('region', { name: 'Maintenant' })

        await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))

        expect(focus()).toHaveTextContent('Trouver le RIB')
        expect(focus()).toHaveTextContent('2 min')
        expect(screen.getByRole('timer', { name: 'Minuteur' })).toHaveTextContent('10:00')
        expect(screen.queryByRole('list', { name: "Aujourd'hui" })).toBeNull()
        expect(dataProvider.getList).toHaveBeenCalledWith('tasks', expect.objectContaining({ filter: { parent: '/api/tasks/01BANK' } }))

        await user.click(screen.getByRole('button', { name: 'Retour à la journée' }))

        expect(dayList()).toBeInTheDocument()
        expect(calls('PUT')).toHaveLength(0)
      })

      test('a task of the day opens its Focus from its row, and a done one has no such button', async () => {
        const user = setup()
        state = { slots: [{ ...BANK, completedAt: '2026-10-15T07:00:00+00:00' }, CANTEEN, WATER], reserve: [] }
        render(<TaskBoard />)
        await screen.findByRole('list', { name: "Aujourd'hui" })
        expect(screen.queryByRole('button', { name: 'Se concentrer sur Appeler la banque' })).toBeNull()

        await user.click(screen.getByRole('button', { name: 'Se concentrer sur Arroser' }))

        expect(screen.getByRole('region', { name: 'Arroser' })).toBeInTheDocument()
        expect(screen.getByRole('timer', { name: 'Minuteur' })).toHaveTextContent('10:00')
      })

      test('« Plus tard, sans souci » puts the task after the slots not done, and « Maintenant » shows the next one', async () => {
        const user = setup()
        render(<TaskBoard />)
        await screen.findByRole('region', { name: 'Maintenant' })
        await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))

        await user.click(screen.getByRole('button', { name: 'Plus tard, sans souci' }))

        await waitFor(() => expect(nowCard()).toHaveTextContent('Payer la cantine'))
        expect(putBodies()).toEqual([['01CANTEEN', '01WATER', '01BANK']])
        expect(rowTitles()[2]).toContain('Appeler la banque')
        expect(screen.queryByRole('region', { name: 'Appeler la banque' })).toBeNull()
        expect(document.body).not.toHaveTextContent(/retard|en retard|reproche/i)
      })

      test('« Plus tard » keeps the done slots where they are', async () => {
        const user = setup()
        state = { slots: [{ ...CANTEEN, completedAt: '2026-10-15T07:00:00+00:00' }, BANK, WATER], reserve: [] }
        render(<TaskBoard />)
        await screen.findByRole('region', { name: 'Maintenant' })
        await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))

        await user.click(screen.getByRole('button', { name: 'Plus tard, sans souci' }))

        await waitFor(() => expect(nowCard()).toHaveTextContent('Arroser'))
        expect(putBodies()).toEqual([['01CANTEEN', '01WATER', '01BANK']])
      })

      test('« Plus tard » on the last task left writes nothing and returns to the day', async () => {
        const user = setup()
        state = { slots: [{ ...CANTEEN, completedAt: '2026-10-15T07:00:00+00:00' }, BANK], reserve: [] }
        render(<TaskBoard />)
        await screen.findByRole('region', { name: 'Maintenant' })
        await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))

        await user.click(screen.getByRole('button', { name: 'Plus tard, sans souci' }))

        await waitFor(() => expect(dayList()).toBeInTheDocument())
        expect(calls('PUT')).toHaveLength(0)
      })

      test('a refused « Plus tard » says why, stays in the Focus and leaves the day as it was', async () => {
        const user = setup()
        mockFetch.mockImplementation((url, init) =>
          Promise.resolve(init?.method === 'PUT' ? json({ error: 'Journée refusée' }, 400) : respond(String(url), init)),
        )
        render(<TaskBoard />)
        await screen.findByRole('region', { name: 'Maintenant' })
        await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))

        await user.click(screen.getByRole('button', { name: 'Plus tard, sans souci' }))

        await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Journée refusée', { type: 'error' }))
        expect(focus()).toBeInTheDocument()
        expect(state.slots.map((t) => t.id)).toEqual(['01BANK', '01CANTEEN', '01WATER'])
      })

      test('« C\'est fait » ticks the task, then returns to the day on the next one', async () => {
        const user = setup()
        render(<TaskBoard />)
        await screen.findByRole('region', { name: 'Maintenant' })
        await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))

        await user.click(within(focus()).getByRole('button', { name: "C'est fait" }))

        await waitFor(() => expect(nowCard()).toHaveTextContent('Payer la cantine'))
        const [url, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
        expect(url).toBe('http://localhost/api/tasks/01BANK')
        expect(JSON.parse(init!.body as string)).toEqual({ completedAt: '2026-10-15T08:00:00.000Z' })
        expect(screen.getByRole('img', { name: '1 sur 3' })).toBeInTheDocument()
        expect(screen.queryByRole('timer')).toBeNull()
      })

      test('a refused « C\'est fait » stays in the Focus, the task not done', async () => {
        const user = setup()
        mockFetch.mockImplementation((url, init) =>
          Promise.resolve(init?.method === 'PATCH' ? json({}, 500) : respond(String(url), init)),
        )
        render(<TaskBoard />)
        await screen.findByRole('region', { name: 'Maintenant' })
        await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))

        await user.click(within(focus()).getByRole('button', { name: "C'est fait" }))

        await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Tâche : 500', { type: 'error' }))
        expect(focus()).toBeInTheDocument()
        expect(state.slots[0].completedAt).toBeNull()
      })
    })

    test('an empty slot list shows no « Maintenant » and no ring; the reserve is still listed', async () => {
      state = { slots: [], reserve: [BOOK] }
      render(<TaskBoard />)

      expect(await screen.findByRole('list', { name: "Aujourd'hui" })).toHaveTextContent('Rendre le livre')
      expect(screen.queryByRole('region', { name: 'Maintenant' })).toBeNull()
      expect(screen.queryByRole('img')).toBeNull()
    })

    test('a day with nothing at all says so', async () => {
      state = { slots: [], reserve: [] }
      render(<TaskBoard />)

      expect(await screen.findByText("Rien à faire aujourd'hui")).toBeInTheDocument()
      expect(screen.queryByRole('list', { name: "Aujourd'hui" })).toBeNull()
    })

    test('shows an error, not a crash, when the day cannot be read', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => {})
      mockFetch.mockImplementation((url, init) =>
        Promise.resolve(String(url).includes('/day-plans/') ? json({ error: 'boom' }, 500) : respond(String(url), init)),
      )
      render(<TaskBoard />)

      expect(await screen.findByRole('alert')).toHaveTextContent('Tâches indisponibles')
      expect(screen.getByRole('link', { name: 'Routines' })).toBeInTheDocument()
    })

    test('listens to the tasks and to the day, and reads the day again on an update', async () => {
      render(<TaskBoard />)
      await screen.findByRole('list', { name: "Aujourd'hui" })
      const url = decodeURIComponent(MockEventSource.last!.url)
      expect(url).toContain('/users/U1/api/tasks/:id')
      expect(url).toContain('/users/U1/api/day_plans/:id')

      MockEventSource.last!.onmessage!({ data: '{}' } as MessageEvent)

      await waitFor(() => expect(dayCalls()).toHaveLength(2))
      expect(viewCalls()).toHaveLength(0)
    })
  })

  describe('the other views (MAG-408, MAG-644)', () => {
    const bodies = () => mockFetch.mock.calls.filter(([, i]) => i?.method === 'POST').map(([, i]) => JSON.parse(i!.body as string))

    test('the routines of the moment ride above the day without touching its ring or « Maintenant » (MAG-512)', async () => {
      routinesDay = {
        date: '2026-10-15',
        moments: [
          {
            anchor: 'wake',
            time: '07:00',
            routines: [{ id: '01WATERR', title: "Grand verre d'eau", anchor: 'wake', anchorTime: null, time: '07:00', remind: false, recurrence: { kind: 'daily' }, checked: false }],
          },
        ],
      }
      render(<TaskBoard />)

      const band = await screen.findByRole('region', { name: 'Routines · matin' })
      expect(within(band).getByRole('button', { name: "Grand verre d'eau" })).toHaveAttribute('aria-pressed', 'false')
      expect(screen.getByRole('img', { name: '0 sur 3' })).toBeInTheDocument()
      expect(within(nowCard()).getByText('Appeler la banque')).toBeInTheDocument()
    })

    test('« Routines » reads the routines, not a view of the tasks, and has neither quick add nor its own link (MAG-511)', async () => {
      boardUrl = '/tasks/routines'
      render(<TaskBoard />)

      expect(await screen.findByRole('button', { name: 'Nouvelle routine' })).toBeInTheDocument()
      expect(screen.queryByRole('textbox', { name: 'Ajouter une tâche' })).toBeNull()
      expect(screen.queryByRole('link', { name: 'Routines' })).toBeNull()
      await waitFor(() => expect(calls()).toContain('http://localhost/api/routines'))
      expect(viewCalls()).toEqual([])
      expect(dayCalls()).toEqual([])
    })

    test('the list of a view is full width, named after the view, a subtask under its task', async () => {
      boardUrl = '/tasks/upcoming'
      render(<TaskBoard />)
      await screen.findByText('Préparer le déménagement')

      expect(screen.getByRole('list', { name: 'À venir' })).toBeInTheDocument()
      expect(screen.getByText('Urgence')).toBeInTheDocument()
      expect(screen.getByText('Réserver le camion').closest('li')).toHaveStyle({ paddingLeft: '32px' })
      expect(screen.getByText('Préparer le déménagement').closest('li')).not.toHaveStyle({ paddingLeft: '32px' })
      expect(screen.getByText('Préparer le déménagement').closest('li')).toHaveTextContent('20 oct.')
    })

    test('a put-off task reads « reportée », never late in red; a critical one keeps its mark', async () => {
      boardUrl = '/tasks/someday'
      render(<TaskBoard />)

      const form = (await screen.findByText('Renvoyer le formulaire')).closest('li')!
      expect(form).toHaveTextContent('reportée depuis le 1 oct.')
      expect(form).not.toHaveTextContent(/retard/i)
      expect(within(form).getByTestId('urgency-badge')).toHaveAttribute('data-urgency', 'high')

      const fine = screen.getByText("Payer l'amende").closest('li')!
      expect(fine).not.toHaveTextContent('reportée')
      expect(within(fine).getByTestId('urgency-badge')).toHaveTextContent('critique')
      expect(fine).toHaveTextContent('prévue le 1 oct.')
    })

    test('ticking a task sends a merge-patch on completedAt and refetches the view', async () => {
      const user = setup()
      boardUrl = '/tasks/upcoming'
      render(<TaskBoard />)
      await screen.findByText('Préparer le déménagement')

      await user.click(screen.getByRole('checkbox', { name: 'Terminer Préparer le déménagement' }))

      await waitFor(() => expect(viewCalls().filter((url) => url.includes('/upcoming'))).toHaveLength(2))
      const [url, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
      expect(url).toBe('http://localhost/api/tasks/01MOVING')
      expect(JSON.parse(init!.body as string)).toEqual({ completedAt: '2026-10-15T08:00:00.000Z' })
      expect(mockNotify).toHaveBeenCalledWith('Tâche terminée', { type: 'success' })
    })

    test('unticking a done task sends completedAt null', async () => {
      const user = setup()
      boardUrl = '/tasks/done'
      render(<TaskBoard />)

      const box = await screen.findByRole('checkbox', { name: 'Rouvrir Sortir les poubelles' })
      expect(box).toBeChecked()
      await user.click(box)

      await waitFor(() => expect(mockFetch.mock.calls.some(([, i]) => i?.method === 'PATCH')).toBe(true))
      const [, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
      expect(JSON.parse(init!.body as string)).toEqual({ completedAt: null })
    })

    test('a failing tick says so and leaves the list as it was', async () => {
      const user = setup()
      boardUrl = '/tasks/upcoming'
      mockFetch.mockImplementation((url, init) =>
        Promise.resolve(init?.method === 'PATCH' ? json({}, 500) : respond(String(url), init)),
      )
      render(<TaskBoard />)
      await screen.findByText('Préparer le déménagement')

      await user.click(screen.getByRole('checkbox', { name: 'Terminer Préparer le déménagement' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Tâche : 500', { type: 'error' }))
      expect(screen.getByText('Préparer le déménagement')).toBeInTheDocument()
    })

    test('the quick add defaults to tomorrow in « À venir » and to no date in « Un jour »', async () => {
      const user = setup()
      boardUrl = '/tasks/upcoming'
      const first = render(<TaskBoard />)
      await user.type(await screen.findByRole('textbox', { name: 'Ajouter une tâche' }), 'Réserver le train{Enter}')
      await waitFor(() => expect(calls('POST')).toHaveLength(1))
      first.unmount()

      boardUrl = '/tasks/someday'
      render(<TaskBoard />)
      await user.type(await screen.findByRole('textbox', { name: 'Ajouter une tâche' }), 'Ranger le garage{Enter}')

      await waitFor(() => expect(calls('POST')).toHaveLength(2))
      expect(bodies()).toEqual([{ title: 'Réserver le train', dueDate: '2026-10-16' }, { title: 'Ranger le garage' }])
      expect(calls('PUT')).toHaveLength(0)
    })

    test('a day word of the title wins over the default of the view', async () => {
      const user = setup()
      boardUrl = '/tasks/someday'
      render(<TaskBoard />)

      await user.type(await screen.findByRole('textbox', { name: 'Ajouter une tâche' }), 'Appeler le garage lundi{Enter}')

      await waitFor(() => expect(bodies()).toEqual([{ title: 'Appeler le garage', dueDate: '2026-10-19' }]))
      expect(mockNotify).toHaveBeenCalledWith('Tâche ajoutée pour le lundi 19 octobre', { type: 'success' })
    })

    test('a blank title creates nothing, and « Faites » has no quick add', async () => {
      const user = setup()
      boardUrl = '/tasks/upcoming'
      const first = render(<TaskBoard />)

      await user.type(await screen.findByRole('textbox', { name: 'Ajouter une tâche' }), '   {Enter}')
      expect(calls('POST')).toHaveLength(0)
      first.unmount()

      boardUrl = '/tasks/done'
      render(<TaskBoard />)
      await screen.findByText('Sortir les poubelles')
      expect(screen.queryByRole('textbox', { name: 'Ajouter une tâche' })).not.toBeInTheDocument()
    })

    test('opens the detail of a task in the panel, without a refetch', async () => {
      const user = setup()
      boardUrl = '/tasks/upcoming'
      render(<TaskBoard />)

      await user.click(await screen.findByRole('button', { name: 'Ouvrir Préparer le déménagement' }))

      const panel = await screen.findByRole('complementary', { name: 'Fiche de la tâche' })
      expect(within(panel).getByRole('heading', { name: 'Modifier la tâche' })).toBeInTheDocument()
      expect(within(panel).getByLabelText(/Titre/)).toHaveValue('Préparer le déménagement')
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(screen.getByRole('list', { name: 'À venir' })).toBeInTheDocument()
    })

    test.each([
      ['upcoming', 'Aucune tâche à venir'],
      ['someday', 'Aucune tâche pour plus tard'],
      ['done', 'Aucune tâche terminée'],
    ])('says so when the « %s » view is empty', async (view, message) => {
      boardUrl = `/tasks/${view}`
      mockFetch.mockImplementation((url, init) => {
        const name = /view\/(\w+)/.exec(String(url))?.[1]
        if (!name) return Promise.resolve(respond(String(url), init))
        return Promise.resolve(viewResponse(name, [], { ...COUNTS, [view]: 0 }))
      })
      render(<TaskBoard />)

      expect(await screen.findByText(message)).toBeInTheDocument()
    })

    test('a network failure shows the error, and the next update recovers', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => {})
      boardUrl = '/tasks/upcoming'
      let failed = false
      mockFetch.mockImplementation((url, init) => {
        if (!failed && /\/tasks\/view\/upcoming/.test(String(url))) {
          failed = true
          return Promise.reject(new TypeError('Failed to fetch'))
        }
        return Promise.resolve(respond(String(url), init))
      })
      render(<TaskBoard />)
      expect(await screen.findByRole('alert')).toHaveTextContent('Tâches indisponibles')

      MockEventSource.last!.onmessage!({ data: '{}' } as MessageEvent)

      expect(await screen.findByText('Préparer le déménagement')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    test('« Faites » is paged: a second page is asked for from the pager', async () => {
      const user = setup()
      boardUrl = '/tasks/done'
      mockFetch.mockImplementation((url, init) => {
        const name = /view\/(\w+)/.exec(String(url))?.[1]
        if (name !== 'done') return Promise.resolve(respond(String(url), init))
        return Promise.resolve(json({ totalItems: 45, counts: COUNTS, page: 1, itemsPerPage: 30, member: VIEWS.done }))
      })
      render(<TaskBoard />)
      await screen.findByText('Sortir les poubelles')

      await user.click(screen.getByRole('button', { name: /page 2/i }))

      await waitFor(() => expect(calls()).toContain('http://localhost/api/tasks/view/done?page=2&itemsPerPage=30'))
    })

    test('« Faites » opens on the page the address says', async () => {
      boardUrl = '/tasks/done?page=2'
      mockFetch.mockImplementation((url, init) => {
        const name = /view\/(\w+)/.exec(String(url))?.[1]
        if (name !== 'done') return Promise.resolve(respond(String(url), init))
        return Promise.resolve(json({ totalItems: 45, counts: COUNTS, member: VIEWS.done }))
      })
      render(<TaskBoard />)

      await screen.findByText('Sortir les poubelles')
      expect(viewCalls()).toEqual(['http://localhost/api/tasks/view/done?page=2&itemsPerPage=30'])
    })

    test('« Faites » goes back a page when the last one is emptied', async () => {
      const user = setup()
      boardUrl = '/tasks/done'
      let doneTotal = 31
      mockFetch.mockImplementation((url, init) => {
        const match = /view\/(\w+)(\?page=(\d+))?/.exec(String(url))
        if (!match || match[1] !== 'done') return Promise.resolve(respond(String(url), init))
        const onPage2 = match[3] === '2'
        const member = onPage2 ? (doneTotal > 30 ? [task('01LAST', 'Dernière faite', { completedAt: '2026-10-01T10:00:00+00:00' })] : []) : VIEWS.done
        return Promise.resolve(json({ totalItems: doneTotal, counts: COUNTS, member }))
      })
      render(<TaskBoard />)
      await screen.findByText('Sortir les poubelles')
      await user.click(screen.getByRole('button', { name: /page 2/i }))
      await screen.findByText('Dernière faite')

      doneTotal = 30
      await user.click(within(screen.getByText('Dernière faite').closest('li') as HTMLElement).getByRole('checkbox'))

      await screen.findByText('Sortir les poubelles')
      expect(screen.queryByText('Aucune tâche terminée')).toBeNull()
    })

    test('a tick answered after leaving the view does not draw on the view shown', async () => {
      const user = setup()
      let release: (response: Response) => void = () => {}
      mockFetch.mockImplementation((url, init) => {
        if (init?.method === 'PATCH') return new Promise<Response>((resolve) => (release = resolve))
        return Promise.resolve(respond(String(url), init))
      })
      const Go = () => {
        const navigate = useNavigate()

        return <button onClick={() => navigate('/tasks/someday')}>Un jour</button>
      }
      renderInto(
        <MemoryRouter initialEntries={['/tasks/upcoming']}>
          <Go />
          <TaskBoard />
        </MemoryRouter>,
      )
      await screen.findByText('Préparer le déménagement')
      await user.click(within(screen.getByText('Préparer le déménagement').closest('li') as HTMLElement).getByRole('checkbox'))
      await user.click(screen.getByRole('button', { name: 'Un jour' }))
      await screen.findByText('Renvoyer le formulaire')

      release(json({}))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Tâche terminée', { type: 'success' }))
      expect(screen.getByText('Renvoyer le formulaire')).toBeTruthy()
      expect(screen.queryByText('Préparer le déménagement')).toBeNull()
    })
  })

  describe('« Ta journée » — the morning (MAG-498)', () => {
    test('opens at the first visit after the morning time while the day is not confirmed', async () => {
      morningTime = '08:00'
      render(<TaskBoard />)

      expect(await screen.findByRole('region', { name: 'Ta journée' })).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Maintenant' })).toBeNull()
    })

    test('does not open before the morning time', async () => {
      morningTime = '11:00'
      render(<TaskBoard />)

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(screen.queryByRole('region', { name: 'Ta journée' })).toBeNull()
    })

    test('does not open once the day is confirmed', async () => {
      morningTime = '08:00'
      confirmedAt = '2026-10-15T08:40:00+00:00'
      render(<TaskBoard />)

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(screen.queryByRole('region', { name: 'Ta journée' })).toBeNull()
    })

    test('opens once: back on the page without confirming does not loop', async () => {
      const user = setup()
      morningTime = '08:00'
      const first = render(<TaskBoard />)
      await user.click(await screen.findByRole('button', { name: 'Plus tard' }))
      await screen.findByRole('region', { name: 'Maintenant' })
      first.unmount()

      render(<TaskBoard />)

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(screen.queryByRole('region', { name: 'Ta journée' })).toBeNull()
    })

    test('« C\'est parti » writes the proposal and shows the day', async () => {
      const user = setup()
      morningTime = '08:00'
      render(<TaskBoard />)

      await user.click(await screen.findByRole('button', { name: "C'est parti" }))

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(putBodies()).toEqual([['01BANK', '01CANTEEN']])
      expect(screen.queryByRole('region', { name: 'Ta journée' })).toBeNull()
    })

    test('« Changer » opens it on demand, even when the day is confirmed', async () => {
      const user = setup()
      confirmedAt = '2026-10-15T08:40:00+00:00'
      render(<TaskBoard />)

      await user.click(await screen.findByRole('button', { name: 'Changer' }))

      expect(await screen.findByRole('region', { name: 'Ta journée' })).toBeInTheDocument()
    })

    test('does not open on the other views', async () => {
      morningTime = '08:00'
      boardUrl = '/tasks/upcoming'
      render(<TaskBoard />)

      await screen.findByText('Préparer le déménagement')
      expect(screen.queryByRole('region', { name: 'Ta journée' })).toBeNull()
    })
  })

  describe('« Le soir » — the review (MAG-500)', () => {
    beforeEach(() => {
      vi.setSystemTime(new Date(2026, 9, 15, 21, 5))
      morningTime = '08:00'
      eveningTime = '21:00'
      confirmedAt = '2026-10-15T06:40:00+00:00'
      state = { slots: [{ ...BANK, completedAt: '2026-10-15T09:00:00+00:00' }, CANTEEN, WATER], reserve: [BOOK, PHOTOS] }
    })

    test('opens at the first visit after the evening time, done first', async () => {
      render(<TaskBoard />)

      const evening = await screen.findByRole('region', { name: 'Le soir' })
      expect(within(evening).getByRole('heading', { name: '1 sur 3' })).toBeInTheDocument()
      expect(within(evening).getByRole('list', { name: 'Fait' })).toHaveTextContent('Appeler la banque')
      expect(screen.queryByRole('region', { name: 'Maintenant' })).toBeNull()
    })

    test('wins over the morning of a day never confirmed', async () => {
      confirmedAt = null
      render(<TaskBoard />)

      expect(await screen.findByRole('region', { name: 'Le soir' })).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Ta journée' })).toBeNull()
    })

    test('does not open before the evening time', async () => {
      vi.setSystemTime(new Date(2026, 9, 15, 20, 59))
      render(<TaskBoard />)

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(screen.queryByRole('region', { name: 'Le soir' })).toBeNull()
    })

    test('does not open once « Bonne nuit » was said', async () => {
      reviewedAt = '2026-10-15T19:10:00+00:00'
      render(<TaskBoard />)

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(screen.queryByRole('region', { name: 'Le soir' })).toBeNull()
    })

    test('does not open on an empty day', async () => {
      state = { slots: [], reserve: [BOOK, PHOTOS] }
      render(<TaskBoard />)

      await screen.findByRole('list', { name: "Aujourd'hui" })
      expect(screen.queryByRole('region', { name: 'Le soir' })).toBeNull()
    })

    test('« Bonne nuit » records the review and shows the day; it does not open again', async () => {
      const user = setup()
      const first = render(<TaskBoard />)
      await user.click(await screen.findByRole('button', { name: 'Bonne nuit' }))

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(calls('PUT')).toEqual([expect.stringContaining('/day-reviews/2026-10-15')])
      first.unmount()
      resetMorningOffer()

      render(<TaskBoard />)
      await screen.findByRole('region', { name: 'Maintenant' })
      expect(screen.queryByRole('region', { name: 'Le soir' })).toBeNull()
    })

    test('« Plus tard » shows the day, writes nothing, and « Le soir » opens it on demand', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(await screen.findByRole('button', { name: 'Plus tard' }))

      await screen.findByRole('region', { name: 'Maintenant' })
      expect(calls('PUT')).toEqual([])

      await user.click(screen.getByRole('button', { name: 'Le soir' }))
      expect(await screen.findByRole('region', { name: 'Le soir' })).toBeInTheDocument()
    })
  })

  describe('a task opened from a link (MAG-640)', () => {
    const marked = () => document.querySelectorAll('[aria-current="true"]')

    beforeEach(() => {
      Element.prototype.scrollIntoView = vi.fn()
    })

    test('a task of the day is marked and scrolled to', async () => {
      boardUrl = '/tasks?task=01CANTEEN'
      render(<TaskBoard />)

      const row = (await screen.findByText('Payer la cantine')).closest('li')!
      await waitFor(() => expect(row).toHaveAttribute('aria-current', 'true'))
      expect(marked()).toHaveLength(1)
      expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    })

    test('a task of the reserve is marked in the same list', async () => {
      boardUrl = '/tasks?task=01PHOTOS'
      render(<TaskBoard />)

      const row = (await screen.findByText('Trier les photos')).closest('li')!
      await waitFor(() => expect(row).toHaveAttribute('aria-current', 'true'))
      expect(within(dayList()).getByText('Trier les photos')).toBeInTheDocument()
    })

    test('a task of another view opens that view\'s route', async () => {
      boardUrl = '/tasks?task=01FINE'
      render(<TaskBoard />)

      expect(await screen.findByRole('list', { name: 'Un jour' })).toBeInTheDocument()
      const row = (await screen.findByText("Payer l'amende")).closest('li')!
      await waitFor(() => expect(row).toHaveAttribute('aria-current', 'true'))
      expect(marked()).toHaveLength(1)
    })

    test('a task done opens « Faites »', async () => {
      boardUrl = '/tasks?task=01DONE'
      render(<TaskBoard />)

      expect(await screen.findByRole('list', { name: 'Faites' })).toBeInTheDocument()
      const row = (await screen.findByText('Sortir les poubelles')).closest('li')!
      await waitFor(() => expect(row).toHaveAttribute('aria-current', 'true'))
    })

    test('the morning proposal does not cover the task a link points at', async () => {
      morningTime = '08:00'
      boardUrl = '/tasks?task=01CANTEEN'
      render(<TaskBoard />)

      const row = (await screen.findByText('Payer la cantine')).closest('li')!
      await waitFor(() => expect(row).toHaveAttribute('aria-current', 'true'))
      expect(screen.queryByRole('region', { name: 'Ta journée' })).toBeNull()
    })

    test('an unknown task leaves the day as it is, with no error', async () => {
      boardUrl = '/tasks?task=01NOBODY'
      render(<TaskBoard />)

      expect(await screen.findByRole('list', { name: "Aujourd'hui" })).toBeInTheDocument()
      await waitFor(() => expect(viewCalls().some((url) => url.includes('/view/done'))).toBe(true))
      expect(screen.queryByRole('list', { name: 'Un jour' })).toBeNull()
      expect(marked()).toHaveLength(0)
      expect(screen.queryByRole('alert')).toBeNull()
      expect(mockNotify).not.toHaveBeenCalled()
    })

    test('a link followed while the page is open moves to the task', async () => {
      const Link = () => {
        const navigate = useNavigate()

        return <button onClick={() => navigate('/tasks?task=01MOVING')}>Suivre le lien</button>
      }
      renderInto(
        <MemoryRouter initialEntries={['/tasks']}>
          <Link />
          <TaskBoard />
        </MemoryRouter>,
      )
      await screen.findByRole('list', { name: "Aujourd'hui" })
      expect(marked()).toHaveLength(0)

      await userEvent.click(screen.getByRole('button', { name: 'Suivre le lien' }))

      expect(await screen.findByRole('list', { name: 'À venir' })).toBeInTheDocument()
      const row = (await screen.findByText('Préparer le déménagement')).closest('li')!
      await waitFor(() => expect(row).toHaveAttribute('aria-current', 'true'))
    })
  })

  describe('a link followed from another view (MAG-640)', () => {
    test('lands on the view that holds the task and marks it', async () => {
      Element.prototype.scrollIntoView = vi.fn()
      const Link = () => {
        const navigate = useNavigate()

        return <button onClick={() => navigate('/tasks?task=01BOXES')}>Suivre le lien</button>
      }
      const user = setup()
      renderInto(
        <MemoryRouter initialEntries={['/tasks/upcoming']}>
          <Link />
          <TaskBoard />
        </MemoryRouter>,
      )
      await screen.findByText('Préparer le déménagement')

      await user.click(screen.getByRole('button', { name: 'Suivre le lien' }))

      const row = (await screen.findByText('Réserver le camion')).closest('li')!
      await waitFor(() => expect(row).toHaveAttribute('aria-current', 'true'))
      expect(screen.getByRole('list', { name: 'À venir' })).toBeInTheDocument()
    })
  })

  describe('the bravo (MAG-508)', () => {
    beforeEach(() => {
      rewards = { '01BANK': reward('01BANK', 'Réglé.'), '01MOVING': reward('01MOVING', 'Bouclé.') }
    })

    const bravo = () => screen.getByRole('dialog', { name: 'Appeler la banque · faite' })

    test('ticking « Maintenant » opens the bravo: the message, the day so far, the next task, a little sound', async () => {
      const user = setup()
      render(<TaskBoard />)
      await screen.findByRole('region', { name: 'Maintenant' })

      await user.click(within(nowCard()).getByRole('button', { name: "C'est fait" }))

      await screen.findByRole('dialog', { name: 'Appeler la banque · faite' })
      expect(bravo()).toHaveTextContent('Réglé.')
      expect(bravo()).toHaveTextContent("1 sur 3 aujourd'hui")
      expect(within(bravo()).getAllByTestId('bravo-segment').map((s) => s.dataset.done)).toEqual(['true', 'false', 'false'])
      expect(within(bravo()).getByRole('button', { name: 'Suivante : Payer la cantine' })).toBeInTheDocument()
      expect(within(bravo()).getByTestId('bravo')).toHaveAttribute('data-animated', 'true')
      expect(within(bravo()).queryByTestId('bravo-shooting-star')).toBeNull()
      expect(play).toHaveBeenCalledTimes(1)
      expect(AudioMock).toHaveBeenCalledWith(expect.stringMatching(/\/maggie_bravo\.ogg$/))
      expect(mockNotify).not.toHaveBeenCalledWith('Tâche terminée', expect.anything())
    })

    test('« Suivante » opens the Focus of the next task', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(within(await screen.findByRole('region', { name: 'Maintenant' })).getByRole('button', { name: "C'est fait" }))

      await user.click(await screen.findByRole('button', { name: 'Suivante : Payer la cantine' }))

      expect(await screen.findByRole('heading', { name: 'Payer la cantine' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Retour à la journée' })).toBeInTheDocument()
      expect(screen.queryByRole('dialog')).toBeNull()
    })

    test('« Pause méritée » goes back to the day', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(within(await screen.findByRole('region', { name: 'Maintenant' })).getByRole('button', { name: "C'est fait" }))

      await user.click(await screen.findByRole('button', { name: 'Pause méritée' }))

      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
      expect(nowCard()).toHaveTextContent('Payer la cantine')
    })

    test('a slot ticked from its checkbox and « C\'est fait » in the Focus open the bravo too', async () => {
      rewards['01WATER'] = reward('01WATER', 'Bouclé.')
      const user = setup()
      render(<TaskBoard />)

      await user.click(await screen.findByRole('checkbox', { name: 'Terminer Arroser' }))
      expect(await screen.findByRole('dialog', { name: 'Arroser · faite' })).toHaveTextContent('Bouclé.')
      await user.click(screen.getByRole('button', { name: 'Pause méritée' }))
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

      await user.click(within(nowCard()).getByRole('button', { name: 'Juste 10 minutes' }))
      await user.click(await screen.findByRole('button', { name: "C'est fait" }))
      expect(await screen.findByRole('dialog', { name: 'Appeler la banque · faite' })).toHaveTextContent('Réglé.')
    })

    test('the sound and the animation follow their settings', async () => {
      cheerSettings = { rewardSound: false, rewardAnimation: false }
      const user = setup()
      render(<TaskBoard />)
      await user.click(within(await screen.findByRole('region', { name: 'Maintenant' })).getByRole('button', { name: "C'est fait" }))

      await screen.findByRole('dialog', { name: 'Appeler la banque · faite' })
      expect(within(bravo()).getByTestId('bravo')).toHaveAttribute('data-animated', 'false')
      expect(play).not.toHaveBeenCalled()
    })

    test('a shooting star crosses for a surprise', async () => {
      rewards['01BANK'] = reward('01BANK', 'Étoile filante : celle-là patientait depuis 3 jours. C\'est réglé.', 'waited')
      const user = setup()
      render(<TaskBoard />)
      await user.click(within(await screen.findByRole('region', { name: 'Maintenant' })).getByRole('button', { name: "C'est fait" }))

      await screen.findByRole('dialog', { name: 'Appeler la banque · faite' })
      expect(within(bravo()).getByTestId('bravo-shooting-star')).toBeInTheDocument()
      expect(bravo()).toHaveTextContent('Étoile filante')
    })

    test('the bravo draws the constellation of the month: 5 stars after the 5th tick (MAG-509)', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(within(await screen.findByRole('region', { name: 'Maintenant' })).getByRole('button', { name: "C'est fait" }))

      await screen.findByRole('dialog', { name: 'Appeler la banque · faite' })
      expect(await within(bravo()).findByRole('heading', { name: "Ta constellation d'octobre · 5 étoiles" })).toBeInTheDocument()
      expect(within(bravo()).getAllByTestId('constellation-star')).toHaveLength(5)
      expect(mockFetch).toHaveBeenCalledWith(expect.stringMatching(/\/stars$/), expect.anything())
    })

    test('the bravo still opens when the stars cannot be read (MAG-509)', async () => {
      mockFetch.mockImplementation((url, init) =>
        Promise.resolve(String(url).includes('/stars') ? json({ error: 'boom' }, 500) : respond(String(url), init)),
      )
      const user = setup()
      render(<TaskBoard />)
      await user.click(within(await screen.findByRole('region', { name: 'Maintenant' })).getByRole('button', { name: "C'est fait" }))

      await screen.findByRole('dialog', { name: 'Appeler la banque · faite' })
      expect(bravo()).toHaveTextContent('Réglé.')
      expect(screen.queryByTestId('constellation')).toBeNull()
    })

    test('a task done carries its star in the list, an open one does not (MAG-509)', async () => {
      const user = setup()
      render(<TaskBoard />)
      const list = await screen.findByRole('list', { name: "Aujourd'hui" })
      expect(within(list).queryAllByTestId('task-star')).toHaveLength(0)

      await user.click(within(await screen.findByRole('region', { name: 'Maintenant' })).getByRole('button', { name: "C'est fait" }))
      await user.click(await screen.findByRole('button', { name: 'Pause méritée' }))

      await waitFor(() => expect(within(screen.getByRole('list', { name: "Aujourd'hui" })).getAllByTestId('task-star')).toHaveLength(1))
      const done = within(screen.getByRole('list', { name: "Aujourd'hui" }))
        .getAllByRole('listitem')
        .find((li) => li.textContent?.includes('Appeler la banque'))!
      expect(within(done).getByRole('img', { name: 'Étoile gagnée' })).toBeInTheDocument()
    })

    test('a palier reaching the page by Mercure opens the gold bravo, with the sound (MAG-509)', async () => {
      render(<TaskBoard />)
      await screen.findByRole('list', { name: "Aujourd'hui" })
      expect(decodeURIComponent(MockEventSource.last!.url)).toContain('/users/U1/api/rewards/:id')

      MockEventSource.last!.onmessage!({
        data: JSON.stringify({
          '@id': '/api/rewards/01PALIER',
          subjectType: 'saga',
          subjectId: '01CHAPTER',
          level: 'palier',
          surprise: null,
          messageKey: 'palier-doux.1',
          message: 'Un chapitre entier. Prends le temps de le savourer.',
          title: 'Chapitre 1 · Le départ',
          star: false,
          earnedAt: '2026-10-15T09:00:00+00:00',
        }),
      } as MessageEvent)

      const palier = await screen.findByRole('dialog', { name: 'Chapitre 1 · Le départ' })
      expect(within(palier).getByTestId('palier-message')).toHaveTextContent('Un chapitre entier.')
      expect(play).toHaveBeenCalledTimes(1)
    })

    test('a palier is silent when the sound is off, and a standard reward does not open it (MAG-509)', async () => {
      cheerSettings = { rewardSound: false }
      render(<TaskBoard />)
      await screen.findByRole('list', { name: "Aujourd'hui" })
      const send = (data: object) => MockEventSource.last!.onmessage!({ data: JSON.stringify(data) } as MessageEvent)

      send({ '@id': '/api/rewards/01RW', level: 'standard', message: 'Réglé.', star: true })
      await waitFor(() => expect(dayCalls().length).toBeGreaterThan(1))
      expect(screen.queryByTestId('palier')).toBeNull()

      send({ '@id': '/api/rewards/01PALIER', level: 'palier', message: 'Chapitre terminé.', title: 'Chapitre 2', star: false })
      expect(await screen.findByRole('dialog', { name: 'Chapitre 2' })).toBeInTheDocument()
      expect(play).not.toHaveBeenCalled()

      await userEvent.click(screen.getByRole('button', { name: 'Pause méritée' }))
      await waitFor(() => expect(screen.queryByTestId('palier')).toBeNull())
    })

    test('away from the day, the bravo is a short banner', async () => {
      const user = setup()
      boardUrl = '/tasks/upcoming'
      render(<TaskBoard />)

      await user.click(await screen.findByRole('checkbox', { name: 'Terminer Préparer le déménagement' }))

      const banner = await screen.findByRole('status')
      expect(banner).toHaveTextContent('Préparer le déménagement · faite — Bouclé.')
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(play).toHaveBeenCalledTimes(1)
      expect(mockNotify).not.toHaveBeenCalledWith('Tâche terminée', expect.anything())
    })
  })
})

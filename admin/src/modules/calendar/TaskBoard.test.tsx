import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
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

const VIEWS: Record<string, ViewTask[]> = {
  // Read on « Aujourd'hui » for the other tabs' counts only.
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

  if (url.endsWith('/task-settings/me'))
    return json({ ...(morningTime ? { morningTime } : {}), ...(eveningTime ? { eveningTime } : {}), ...cheerSettings })

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

const calls = (method = 'GET') =>
  mockFetch.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === method).map(([url]) => String(url))

const viewCalls = () => calls().filter((url) => url.includes('/tasks/view/'))
const dayCalls = () => calls().filter((url) => url.includes('/day-plans/'))

const putBodies = () =>
  mockFetch.mock.calls
    .filter(([, init]) => init?.method === 'PUT')
    .map(([, init]) => (JSON.parse(init!.body as string) as { slots: { taskId: string }[] }).slots.map((s) => s.taskId))

const slotTitles = () =>
  within(screen.getByRole('list', { name: 'Mes 5' }))
    .getAllByRole('listitem')
    .map((li) => li.textContent)

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
  star: true,
  earnedAt: '2026-10-15T08:00:00+00:00',
})

const setup = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

describe('TaskBoard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 10, 0))
    morningTime = null
    eveningTime = null
    confirmedAt = null
    reviewedAt = null
    rewards = {}
    cheerSettings = {}
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

  describe('« Aujourd\'hui » — the day of five (MAG-497)', () => {
    test('draws the four tabs, « Aujourd\'hui » first and counting what is left of the day', async () => {
      render(<TaskBoard />)

      expect(await screen.findByRole('tab', { name: "Aujourd'hui (3)" })).toHaveAttribute('aria-selected', 'true')
      expect(screen.getByRole('tab', { name: 'À venir (2)' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'À faire un jour (2)' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'Faites (1)' })).toBeInTheDocument()
      expect(dayCalls()).toEqual(['http://localhost/api/day-plans/2026-10-15'])
    })

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

    test('« Mes 5 » lists the slots in order: « reportée » in words, the critical mark instead, the minutes', async () => {
      render(<TaskBoard />)
      await screen.findByRole('list', { name: 'Mes 5' })

      expect(slotTitles()).toEqual([
        expect.stringContaining('Appeler la banque'),
        expect.stringContaining('Payer la cantine'),
        expect.stringContaining('Arroser'),
      ])
      const [bank, canteen, water] = within(screen.getByRole('list', { name: 'Mes 5' })).getAllByRole('listitem')
      expect(bank).toHaveTextContent('reportée depuis lundi')
      expect(bank).toHaveTextContent('10 min')
      expect(bank).not.toHaveTextContent(/retard/i)
      expect(within(canteen).getByTestId('urgency-badge')).toHaveAttribute('data-urgency', 'critical')
      expect(canteen).not.toHaveTextContent('reportée')
      expect(within(water).queryByTestId('urgency-badge')).toBeNull()
      expect(screen.getByTestId('day-free').children).toHaveLength(2)
    })

    test('the reserve is folded, says its size, and opens on a click', async () => {
      const user = setup()
      render(<TaskBoard />)

      const toggle = await screen.findByRole('button', { name: 'Réserve (2)' })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
      expect(screen.queryByRole('list', { name: 'Réserve' })).toBeNull()
      expect(screen.queryByText('Rendre le livre')).toBeNull()

      await user.click(toggle)

      expect(toggle).toHaveAttribute('aria-expanded', 'true')
      const reserve = screen.getByRole('list', { name: 'Réserve' })
      expect(within(reserve).getByText('Rendre le livre').closest('li')).toHaveTextContent('reportée depuis hier')
      expect(within(reserve).getByText('Trier les photos')).toBeInTheDocument()
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
      expect(screen.getByRole('tab', { name: "Aujourd'hui (2)" })).toBeInTheDocument()
      expect(screen.getByRole('checkbox', { name: 'Rouvrir Appeler la banque' })).toBeChecked()
      expect(mockNotify).toHaveBeenCalledWith('Tâche terminée', { type: 'success' })
    })

    test('a slot is ticked in one gesture from its checkbox', async () => {
      const user = setup()
      render(<TaskBoard />)

      await user.click(await screen.findByRole('checkbox', { name: 'Terminer Arroser' }))

      await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Rouvrir Arroser' })).toBeChecked())
      expect(nowCard()).toHaveTextContent('Appeler la banque')
    })

    test('with room left, a reserve task is added after the last slot', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(await screen.findByRole('button', { name: 'Réserve (2)' }))

      await user.click(screen.getByRole('button', { name: 'Ajouter Rendre le livre à la journée' }))

      await waitFor(() => expect(slotTitles()).toHaveLength(4))
      expect(putBodies()).toEqual([['01BANK', '01CANTEEN', '01WATER', '01BOOK']])
      expect(mockFetch.mock.calls.find(([, i]) => i?.method === 'PUT')![0]).toBe('http://localhost/api/day-plans/2026-10-15')
      expect(screen.getByRole('button', { name: 'Réserve (1)' })).toBeInTheDocument()
    })

    test('with the five taken, a reserve task goes « à la place de » the slot chosen, which goes back to the reserve', async () => {
      const user = setup()
      const ERRAND = dayTask('01ERRAND', 'Faire les courses')
      const MAIL = dayTask('01MAIL', 'Poster la lettre')
      state = { slots: [BANK, CANTEEN, WATER, ERRAND, MAIL], reserve: [BOOK, PHOTOS] }
      render(<TaskBoard />)
      await user.click(await screen.findByRole('button', { name: 'Réserve (2)' }))
      expect(screen.queryByRole('button', { name: 'Ajouter Rendre le livre à la journée' })).toBeNull()

      await user.click(screen.getByRole('button', { name: 'Mettre Rendre le livre à la place de…' }))
      await user.click(screen.getByRole('menuitem', { name: 'À la place de Arroser' }))

      await waitFor(() => expect(putBodies()).toEqual([['01BANK', '01CANTEEN', '01BOOK', '01ERRAND', '01MAIL']]))
      await waitFor(() => expect(slotTitles()[2]).toContain('Rendre le livre'))
      expect(within(screen.getByRole('list', { name: 'Réserve' })).getByText('Arroser')).toBeInTheDocument()
    })

    test('« Remettre dans la réserve » sends the day without it', async () => {
      const user = setup()
      render(<TaskBoard />)

      await user.click(await screen.findByRole('button', { name: 'Remettre Arroser dans la réserve' }))

      await waitFor(() => expect(slotTitles()).toHaveLength(2))
      expect(putBodies()).toEqual([['01BANK', '01CANTEEN']])
      expect(screen.getByRole('button', { name: 'Réserve (3)' })).toBeInTheDocument()
    })

    test('the five are reordered from the keyboard', async () => {
      const user = setup()
      render(<TaskBoard />)
      const down = await screen.findByRole('button', { name: 'Descendre Appeler la banque' })
      expect(screen.getByRole('button', { name: 'Monter Appeler la banque' })).toBeDisabled()

      down.focus()
      await user.keyboard('{Enter}')

      await waitFor(() => expect(slotTitles()[0]).toContain('Payer la cantine'))
      expect(putBodies()).toEqual([['01CANTEEN', '01BANK', '01WATER']])
      expect(slotTitles()[1]).toContain('Appeler la banque')
      expect(nowCard()).toHaveTextContent('Payer la cantine')

      screen.getByRole('button', { name: 'Monter Appeler la banque' }).focus()
      await user.keyboard(' ')

      await waitFor(() => expect(putBodies()).toHaveLength(2))
      expect(putBodies()[1]).toEqual(['01BANK', '01CANTEEN', '01WATER'])
    })

    test('a refused day says why and reads the day again', async () => {
      const user = setup()
      mockFetch.mockImplementation((url, init) =>
        Promise.resolve(
          init?.method === 'PUT' ? json({ error: 'A task takes one slot of the day, not two.' }, 400) : respond(String(url), init),
        ),
      )
      render(<TaskBoard />)

      await user.click(await screen.findByRole('button', { name: 'Remettre Arroser dans la réserve' }))

      await waitFor(() =>
        expect(mockNotify).toHaveBeenCalledWith('Erreur: A task takes one slot of the day, not two.', { type: 'error' }),
      )
      await waitFor(() => expect(dayCalls()).toHaveLength(2))
      expect(slotTitles()).toHaveLength(3)
    })

    test('« Ajouter une tâche » with room creates it, then puts it in the day', async () => {
      const user = setup()
      render(<TaskBoard />)

      await user.type(await screen.findByRole('textbox', { name: 'Ajouter une tâche' }), 'Acheter du pain{Enter}')

      await waitFor(() => expect(putBodies()).toEqual([['01BANK', '01CANTEEN', '01WATER', '01NEW']]))
      const [, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'POST')!
      expect(JSON.parse(init!.body as string)).toEqual({ title: 'Acheter du pain' })
      await waitFor(() => expect(slotTitles()[3]).toContain('Acheter du pain'))
      expect(screen.getByRole('textbox', { name: 'Ajouter une tâche' })).toHaveValue('')
    })

    test('« Ajouter une tâche » with the five taken leaves it in the reserve', async () => {
      const user = setup()
      state = {
        slots: [BANK, CANTEEN, WATER, dayTask('01ERRAND', 'Faire les courses'), dayTask('01MAIL', 'Poster la lettre')],
        reserve: [BOOK],
      }
      render(<TaskBoard />)

      await user.type(await screen.findByRole('textbox', { name: 'Ajouter une tâche' }), 'Acheter du pain{Enter}')

      expect(await screen.findByRole('button', { name: 'Réserve (2)' })).toBeInTheDocument()
      expect(calls('POST')).toHaveLength(1)
      expect(calls('PUT')).toHaveLength(0)
      expect(screen.queryByTestId('day-free')).toBeNull()
    })

    test('a blank title creates nothing', async () => {
      const user = setup()
      render(<TaskBoard />)

      await user.type(await screen.findByRole('textbox', { name: 'Ajouter une tâche' }), '   {Enter}')

      expect(calls('POST')).toHaveLength(0)
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
        expect(screen.queryByRole('list', { name: 'Mes 5' })).toBeNull()
        expect(dataProvider.getList).toHaveBeenCalledWith('tasks', expect.objectContaining({ filter: { parent: '/api/tasks/01BANK' } }))

        await user.click(screen.getByRole('button', { name: 'Retour à la journée' }))

        expect(screen.getByRole('list', { name: 'Mes 5' })).toBeInTheDocument()
        expect(calls('PUT')).toHaveLength(0)
      })

      test('a task of the day opens its Focus from its row, and a done one has no such button', async () => {
        const user = setup()
        state = { slots: [{ ...BANK, completedAt: '2026-10-15T07:00:00+00:00' }, CANTEEN, WATER], reserve: [] }
        render(<TaskBoard />)
        await screen.findByRole('list', { name: 'Mes 5' })
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
        expect(slotTitles()[2]).toContain('Appeler la banque')
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

        await waitFor(() => expect(screen.getByRole('list', { name: 'Mes 5' })).toBeInTheDocument())
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

    test('an empty day shows no « Maintenant » and no ring, only the free places', async () => {
      state = { slots: [], reserve: [BOOK] }
      render(<TaskBoard />)

      expect(await screen.findByRole('tab', { name: "Aujourd'hui (0)" })).toBeInTheDocument()
      expect(await screen.findByRole('button', { name: 'Réserve (1)' })).toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Maintenant' })).toBeNull()
      expect(screen.queryByRole('img')).toBeNull()
      expect(screen.getByTestId('day-free').children).toHaveLength(5)
    })

    test('shows an error, not a crash, when the day cannot be read', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => {})
      mockFetch.mockImplementation((url, init) =>
        Promise.resolve(String(url).includes('/day-plans/') ? json({ error: 'boom' }, 500) : respond(String(url), init)),
      )
      render(<TaskBoard />)

      expect(await screen.findByRole('alert')).toHaveTextContent('Tâches indisponibles')
      expect(screen.getByRole('tab', { name: "Aujourd'hui" })).toBeInTheDocument()
    })

    test('listens to the tasks and to the day, and reads both again on an update', async () => {
      render(<TaskBoard />)
      await screen.findByRole('list', { name: 'Mes 5' })
      const url = decodeURIComponent(MockEventSource.last!.url)
      expect(url).toContain('/users/U1/api/tasks/:id')
      expect(url).toContain('/users/U1/api/day_plans/:id')

      MockEventSource.last!.onmessage!({ data: '{}' } as MessageEvent)

      await waitFor(() => expect(dayCalls()).toHaveLength(2))
      expect(viewCalls()).toHaveLength(2)
    })
  })

  describe('the other three views (MAG-408)', () => {
    test('switching tab fetches that view, and « Faites » asks for its first page', async () => {
      const user = setup()
      render(<TaskBoard />)
      await screen.findByRole('list', { name: 'Mes 5' })

      await user.click(screen.getByRole('tab', { name: 'À venir (2)' }))
      expect(await screen.findByText('Préparer le déménagement')).toBeInTheDocument()
      expect(screen.queryByRole('list', { name: 'Mes 5' })).not.toBeInTheDocument()

      await user.click(screen.getByRole('tab', { name: 'À faire un jour (2)' }))
      expect(await screen.findByText('Renvoyer le formulaire')).toBeInTheDocument()

      await user.click(screen.getByRole('tab', { name: 'Faites (1)' }))
      expect(await screen.findByText('Sortir les poubelles')).toBeInTheDocument()
      expect(viewCalls()).toEqual([
        'http://localhost/api/tasks/view/today',
        'http://localhost/api/tasks/view/upcoming',
        'http://localhost/api/tasks/view/someday',
        'http://localhost/api/tasks/view/done?page=1&itemsPerPage=30',
      ])
    })

    test('a subtask goes under its task', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À venir (2)' }))
      await screen.findByText('Préparer le déménagement')

      expect(screen.getByText('Réserver le camion').closest('li')).toHaveStyle({ paddingLeft: '32px' })
      expect(screen.getByText('Préparer le déménagement').closest('li')).not.toHaveStyle({ paddingLeft: '32px' })
    })

    test('a put-off task reads « reportée », never late in red; a critical one keeps its mark', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À faire un jour (2)' }))

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
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À venir (2)' }))
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
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'Faites (1)' }))

      const box = await screen.findByRole('checkbox', { name: 'Rouvrir Sortir les poubelles' })
      expect(box).toBeChecked()
      await user.click(box)

      await waitFor(() => expect(mockFetch.mock.calls.some(([, i]) => i?.method === 'PATCH')).toBe(true))
      const [, init] = mockFetch.mock.calls.find(([, i]) => i?.method === 'PATCH')!
      expect(JSON.parse(init!.body as string)).toEqual({ completedAt: null })
    })

    test('a failing tick says so and leaves the list as it was', async () => {
      const user = setup()
      mockFetch.mockImplementation((url, init) =>
        Promise.resolve(init?.method === 'PATCH' ? json({}, 500) : respond(String(url), init)),
      )
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À venir (2)' }))
      await screen.findByText('Préparer le déménagement')

      await user.click(screen.getByRole('checkbox', { name: 'Terminer Préparer le déménagement' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Tâche : 500', { type: 'error' }))
      expect(screen.getByText('Préparer le déménagement')).toBeInTheDocument()
    })

    test('the quick create defaults to tomorrow in « À venir » and to no date in « À faire un jour »', async () => {
      const user = setup()
      render(<TaskBoard />)
      await screen.findByRole('list', { name: 'Mes 5' })

      await user.click(screen.getByRole('tab', { name: 'À venir (2)' }))
      await user.type(await screen.findByRole('textbox', { name: 'Nouvelle tâche' }), 'Réserver le train{Enter}')
      await user.click(screen.getByRole('tab', { name: /À faire un jour/ }))
      await user.type(await screen.findByRole('textbox', { name: 'Nouvelle tâche' }), 'Ranger le garage{Enter}')

      await waitFor(() => expect(calls('POST')).toHaveLength(2))
      const bodies = mockFetch.mock.calls.filter(([, i]) => i?.method === 'POST').map(([, i]) => JSON.parse(i!.body as string))
      expect(bodies).toEqual([
        { title: 'Réserver le train', dueDate: '2026-10-16' },
        { title: 'Ranger le garage' },
      ])
      expect(calls('PUT')).toHaveLength(0)
    })

    test('a blank title creates nothing, and « Faites » has no quick create', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À venir (2)' }))

      await user.type(await screen.findByRole('textbox', { name: 'Nouvelle tâche' }), '   {Enter}')
      expect(calls('POST')).toHaveLength(0)

      await user.click(screen.getByRole('tab', { name: 'Faites (1)' }))
      await screen.findByText('Sortir les poubelles')
      expect(screen.queryByRole('textbox', { name: 'Nouvelle tâche' })).not.toBeInTheDocument()
    })

    test('opens the detail of a task in the edit form', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À venir (2)' }))

      await user.click(await screen.findByText('Préparer le déménagement'))

      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByRole('heading', { name: 'Modifier la tâche' })).toBeInTheDocument()
      expect(within(dialog).getByLabelText(/Titre/)).toHaveValue('Préparer le déménagement')
    })

    test.each([
      ['upcoming', 'À venir', 'Aucune tâche à venir'],
      ['someday', 'À faire un jour', 'Aucune tâche pour plus tard'],
      ['done', 'Faites', 'Aucune tâche terminée'],
    ])('says so when the « %s » view is empty', async (view, label, message) => {
      const user = setup()
      mockFetch.mockImplementation((url, init) => {
        const name = /view\/(\w+)/.exec(String(url))?.[1]
        if (!name) return Promise.resolve(respond(String(url), init))
        return Promise.resolve(viewResponse(name, name === view ? [] : VIEWS[name], { ...COUNTS, [view]: 0 }))
      })
      render(<TaskBoard />)
      await screen.findByRole('list', { name: 'Mes 5' })

      await user.click(screen.getByRole('tab', { name: new RegExp(label) }))

      expect(await screen.findByText(message)).toBeInTheDocument()
    })

    test('a network failure on another tab shows the error too, and the next tab recovers', async () => {
      const user = setup()
      vi.spyOn(console, 'error').mockImplementation(() => {})
      render(<TaskBoard />)
      await screen.findByRole('list', { name: 'Mes 5' })

      mockFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
      await user.click(screen.getByRole('tab', { name: 'À venir (2)' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('Tâches indisponibles')

      await user.click(screen.getByRole('tab', { name: 'À faire un jour (2)' }))
      expect(await screen.findByText('Renvoyer le formulaire')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    test('« Faites » is paged: a second page is asked for from the pager', async () => {
      const user = setup()
      mockFetch.mockImplementation((url, init) => {
        const name = /view\/(\w+)/.exec(String(url))?.[1]
        if (name !== 'done') return Promise.resolve(respond(String(url), init))
        return Promise.resolve(json({ totalItems: 45, counts: COUNTS, page: 1, itemsPerPage: 30, member: VIEWS.done }))
      })
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'Faites (1)' }))
      await screen.findByText('Sortir les poubelles')

      await user.click(screen.getByRole('button', { name: /page 2/i }))

      await waitFor(() => expect(calls()).toContain('http://localhost/api/tasks/view/done?page=2&itemsPerPage=30'))
    })

    test('« Faites » goes back a page when the last one is emptied', async () => {
      const user = setup()
      let doneTotal = 31
      mockFetch.mockImplementation((url, init) => {
        const match = /view\/(\w+)(\?page=(\d+))?/.exec(String(url))
        if (!match || match[1] !== 'done') return Promise.resolve(respond(String(url), init))
        const onPage2 = match[3] === '2'
        const member = onPage2 ? (doneTotal > 30 ? [task('01LAST', 'Dernière faite', { completedAt: '2026-10-01T10:00:00+00:00' })] : []) : VIEWS.done
        return Promise.resolve(json({ totalItems: doneTotal, counts: COUNTS, member }))
      })
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'Faites (1)' }))
      await screen.findByText('Sortir les poubelles')
      await user.click(screen.getByRole('button', { name: /page 2/i }))
      await screen.findByText('Dernière faite')

      doneTotal = 30
      await user.click(within(screen.getByText('Dernière faite').closest('li') as HTMLElement).getByRole('checkbox'))

      await screen.findByText('Sortir les poubelles')
      expect(screen.queryByText('Aucune tâche terminée')).toBeNull()
    })

    test('a tick answered after a tab switch reloads the tab shown, not the one ticked on', async () => {
      const user = setup()
      let release: (response: Response) => void = () => {}
      mockFetch.mockImplementation((url, init) => {
        if (init?.method === 'PATCH') return new Promise<Response>((resolve) => (release = resolve))
        return Promise.resolve(respond(String(url), init))
      })
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À venir (2)' }))
      await screen.findByText('Préparer le déménagement')
      await user.click(within(screen.getByText('Préparer le déménagement').closest('li') as HTMLElement).getByRole('checkbox'))
      await user.click(screen.getByRole('tab', { name: /À faire un jour/ }))
      await screen.findByText('Renvoyer le formulaire')

      release(json({}))

      await waitFor(() => expect(viewCalls().filter((url) => url.includes('/view/someday')).length).toBeGreaterThan(1))
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

      await screen.findByRole('list', { name: 'Mes 5' })
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

    test('away from the day, the bravo is a short banner', async () => {
      const user = setup()
      render(<TaskBoard />)
      await user.click(await screen.findByRole('tab', { name: 'À venir (2)' }))

      await user.click(await screen.findByRole('checkbox', { name: 'Terminer Préparer le déménagement' }))

      const banner = await screen.findByRole('status')
      expect(banner).toHaveTextContent('Préparer le déménagement · faite — Bouclé.')
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(play).toHaveBeenCalledTimes(1)
      expect(mockNotify).not.toHaveBeenCalledWith('Tâche terminée', expect.anything())
    })
  })
})

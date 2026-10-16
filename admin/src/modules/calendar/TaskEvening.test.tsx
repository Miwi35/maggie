import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskEvening } from './TaskEvening'
import type { DayPlan, DayTask } from './dayPlan'

const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useNotify: () => mockNotify,
}))

const TODAY = '2026-10-14'
const TOMORROW = '2026-10-15'

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

const DONE_AT = '2026-10-14T10:00:00+00:00'
// « 3 sur 5 », the done ones not first in the day: the evening puts them first.
const TASKS = [
  dayTask('T4', 'Réserver le train'),
  dayTask('T1', 'Rappeler le garage', { completedAt: DONE_AT }),
  dayTask('T2', 'Trier le courrier', { completedAt: DONE_AT }),
  dayTask('T5', 'Payer la cantine', { dueDate: '2026-10-16' }),
  dayTask('T3', 'Arroser les plantes', { completedAt: DONE_AT }),
]

const plan = (date: string, tasks: DayTask[], confirmedAt: string | null = null): DayPlan => {
  const slots = tasks.map((task, i) => ({ position: i + 1, type: 'task' as const, task }))

  return {
    id: `day-${date}`,
    date,
    confirmedAt,
    slots,
    now: slots.find((slot) => slot.task.completedAt == null) ?? null,
    reserve: [],
    done: slots.filter((slot) => slot.task.completedAt != null).length,
    total: slots.length,
  }
}

const DAY = plan(TODAY, TASKS, '2026-10-14T06:40:00+00:00')
const OTHERS = ['O1', 'O2', 'O3', 'O4', 'O5'].map((id) => dayTask(id, `Autre ${id}`))

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

const mockFetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()
let tomorrowTasks: DayTask[] = []
let refuse = false
let refuseToday = false

const all = () => [...TASKS, ...OTHERS]

const respond = (url: string, init?: RequestInit): Response => {
  const method = init?.method ?? 'GET'
  const body = init?.body ? (JSON.parse(init.body as string) as Record<string, unknown>) : {}
  const day = /\/day-plans\/([\d-]+)$/.exec(url)
  if (day) {
    if (refuse && method === 'PUT') return json({ error: 'A day holds 5 slots at most' }, 400)
    if (refuseToday && method === 'PUT' && day[1] === TODAY) return json({ error: 'Day plan not found' }, 400)
    const ids = method === 'PUT' ? (body.slots as { taskId: string }[]).map((s) => s.taskId) : null
    if (day[1] === TOMORROW) {
      if (ids) tomorrowTasks = ids.map((id) => all().find((t) => t.id === id)!)

      return json(plan(TOMORROW, tomorrowTasks))
    }

    return json(plan(TODAY, ids ? ids.map((id) => all().find((t) => t.id === id)!) : TASKS))
  }
  if (/\/day-reviews\//.test(url) && method === 'PUT') {
    return json({ id: '01REVIEW', date: TODAY, helped: body.helped, reviewedAt: '2026-10-14T19:10:00+00:00' })
  }
  if (/\/tasks\/\w+$/.test(url) && method === 'PATCH') return json({})

  return json({})
}

const writes = () =>
  mockFetch.mock.calls
    .filter(([, init]) => init?.method === 'PUT' || init?.method === 'PATCH')
    .map(([url, init]) => [init!.method, String(url).replace(/^.*\/api/, ''), JSON.parse(init!.body as string)])

const slotsOf = (body: { slots: { taskId: string }[] }) => body.slots.map((s) => s.taskId)

const setup = () => {
  const onClose = vi.fn()
  const onDone = vi.fn()
  render(<TaskEvening day={DAY} onClose={onClose} onDone={onDone} />)

  return { user: userEvent.setup(), onClose, onDone }
}

const row = (title: string) => {
  const item = within(screen.getByRole('list', { name: 'Reste' }))
    .getAllByRole('listitem')
    .find((li) => li.textContent?.includes(title))
  if (!item) throw new Error(`No row for ${title}`)

  return within(item)
}

describe('TaskEvening — « Le soir » (MAG-500)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    tomorrowTasks = []
    refuse = false
    refuseToday = false
    vi.stubGlobal('fetch', mockFetch)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    mockFetch.mockImplementation((url, init) => Promise.resolve(respond(String(url), init)))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('« 3 sur 5 »: the three done first, then the two left, each « Plus tard » by default', async () => {
    setup()

    expect(screen.getByRole('heading', { name: '3 sur 5' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '3 sur 5' })).toBeInTheDocument()
    const done = within(screen.getByRole('list', { name: 'Fait' })).getAllByRole('listitem').map((li) => li.textContent)
    expect(done).toEqual(['Rappeler le garage', 'Trier le courrier', 'Arroser les plantes'])
    const left = within(screen.getByRole('list', { name: 'Reste' })).getAllByRole('listitem')
    expect(left.map((li) => li.textContent)).toEqual([
      expect.stringContaining('Réserver le train'),
      expect.stringContaining('Payer la cantine'),
    ])
    // « Fait » comes before « Reste » on the page.
    expect(
      screen.getByRole('list', { name: 'Fait' }).compareDocumentPosition(screen.getByRole('list', { name: 'Reste' })) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
    await waitFor(() => expect(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Plus tard' })).toHaveAttribute('aria-pressed', 'true'))
  })

  test('« Demain » writes the task in tomorrow\'s five without settling that day', async () => {
    const { user } = setup()
    await waitFor(() => expect(mockFetch).toHaveBeenCalled())

    await user.click(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Demain' }))

    await waitFor(() => expect(writes()).toEqual([['PUT', `/day-plans/${TOMORROW}`, { slots: [{ type: 'task', taskId: 'T4' }], confirm: false }]]))
    expect(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Demain' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('back to « Plus tard » takes it out of tomorrow again', async () => {
    const { user } = setup()
    await user.click(await row('Réserver le train').findByRole('button', { name: 'Réserver le train : Demain' }))
    await waitFor(() => expect(writes()).toHaveLength(1))

    await user.click(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Plus tard' }))

    await waitFor(() => expect(writes()).toHaveLength(2))
    expect(slotsOf(writes()[1][2])).toEqual([])
    expect(tomorrowTasks).toEqual([])
  })

  test('« Demain » beyond five is refused: the way out stays « Plus tard »', async () => {
    tomorrowTasks = OTHERS
    setup()

    const demain = row('Réserver le train').getByRole('button', { name: 'Réserver le train : Demain' })
    await waitFor(() => expect(demain).toBeDisabled())
    expect(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Plus tard' })).toHaveAttribute('aria-pressed', 'true')
    expect(writes()).toEqual([])
  })

  test('a task already in tomorrow\'s five comes out « Demain »', async () => {
    tomorrowTasks = [TASKS[0]]
    setup()

    await waitFor(() =>
      expect(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Demain' })).toHaveAttribute('aria-pressed', 'true'),
    )
  })

  test('« Mettre de côté » clears the due day and takes it out of today; « Annuler » puts both back', async () => {
    const { user } = setup()
    await waitFor(() => expect(mockFetch).toHaveBeenCalled())

    await user.click(row('Payer la cantine').getByRole('button', { name: 'Payer la cantine : Mettre de côté' }))

    await waitFor(() => expect(writes()).toHaveLength(2))
    expect(writes()[0]).toEqual(['PATCH', '/tasks/T5', { dueDate: null }])
    expect(writes()[1][1]).toBe(`/day-plans/${TODAY}`)
    expect(slotsOf(writes()[1][2])).toEqual(['T4', 'T1', 'T2', 'T3'])
    expect(row('Payer la cantine').getByText('Un jour')).toBeInTheDocument()
    // The review keeps the day it opened on.
    expect(screen.getByRole('heading', { name: '3 sur 5' })).toBeInTheDocument()

    await user.click(row('Payer la cantine').getByRole('button', { name: 'Annuler : Payer la cantine' }))

    await waitFor(() => expect(writes()).toHaveLength(4))
    expect(writes()[2]).toEqual(['PATCH', '/tasks/T5', { dueDate: '2026-10-16' }])
    expect(slotsOf(writes()[3][2])).toEqual(['T4', 'T1', 'T2', 'T5', 'T3'])
    expect(row('Payer la cantine').getByRole('button', { name: 'Payer la cantine : Plus tard' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('a refused write says so and keeps the way out it had', async () => {
    const { user } = setup()
    await waitFor(() => expect(mockFetch).toHaveBeenCalled())
    refuse = true

    await user.click(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Demain' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: A day holds 5 slots at most', { type: 'error' }))
    expect(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Plus tard' })).toHaveAttribute('aria-pressed', 'true')
  })

  test('past midnight, the gestures and « Bonne nuit » still write the day reviewed and the next one', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 0, 5))
    try {
      const { user } = setup()
      await waitFor(() => expect(mockFetch).toHaveBeenCalledWith(expect.stringContaining(`/day-plans/${TOMORROW}`), expect.anything()))

      await user.click(row('Réserver le train').getByRole('button', { name: 'Réserver le train : Demain' }))
      await waitFor(() => expect(writes()).toHaveLength(1))
      await user.click(screen.getByRole('button', { name: 'Bonne nuit' }))

      await waitFor(() => expect(writes()).toHaveLength(2))
      expect(writes().map(([, url]) => url)).toEqual([`/day-plans/${TOMORROW}`, `/day-reviews/${TODAY}`])
    } finally {
      vi.useRealTimers()
    }
  })

  test('a write that fails halfway shows where the task really is', async () => {
    const { user } = setup()
    await waitFor(() => expect(mockFetch).toHaveBeenCalled())
    refuseToday = true

    await user.click(row('Payer la cantine').getByRole('button', { name: 'Payer la cantine : Mettre de côté' }))

    // The due day is gone, the slot is not: « Un jour », and « Annuler » to bring the due day back.
    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Day plan not found', { type: 'error' }))
    expect(row('Payer la cantine').getByText('Un jour')).toBeInTheDocument()
    expect(row('Payer la cantine').getByRole('button', { name: 'Annuler : Payer la cantine' })).toBeEnabled()
  })

  test('« Bonne nuit » records what helped and closes', async () => {
    const { user, onDone, onClose } = setup()

    await user.click(screen.getByRole('checkbox', { name: 'Commencer petit' }))
    await user.click(screen.getByRole('checkbox', { name: 'Le minuteur' }))
    await user.click(screen.getByRole('checkbox', { name: 'Le minuteur' }))
    await user.click(screen.getByRole('button', { name: 'Bonne nuit' }))

    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(writes()).toEqual([['PUT', `/day-reviews/${TODAY}`, { helped: ['start_small'] }]])
    expect(onClose).not.toHaveBeenCalled()
  })

  test('« Plus tard » closes without writing the review', async () => {
    const { user, onClose, onDone } = setup()

    await user.click(screen.getByRole('checkbox', { name: 'Préparer le matin' }))
    await user.click(screen.getByRole('button', { name: 'Plus tard' }))

    expect(onClose).toHaveBeenCalled()
    expect(onDone).not.toHaveBeenCalled()
    expect(writes()).toEqual([])
  })
})

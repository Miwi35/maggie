import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskFocus } from './TaskFocus'
import type { DayTask } from './dayPlan'
import type { TaskData } from './TaskEditDialog'

const mockNotify = vi.fn()
const dataProvider = { getList: vi.fn(), update: vi.fn(), create: vi.fn() }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => mockNotify,
}))

const mockFetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()

const TASK: DayTask = {
  id: '01BANK',
  title: 'Appeler la banque',
  estimatedMinutes: 10,
  firstStep: 'Trouver le RIB',
  completedAt: null,
  dueDate: null,
  postponedSince: null,
  urgency: 'normal',
  criticality: 'low',
}

const step = (id: string, title: string, position: string, extra: Partial<TaskData> = {}): TaskData => ({
  id: `/api/tasks/${id}`,
  title,
  priority: 'medium',
  criticality: 'low',
  parent: '/api/tasks/01BANK',
  position,
  completedAt: null,
  ...extra,
})

const STEPS = [step('01S2', 'Appeler le conseiller', 'b'), step('01S1', 'Chercher le numéro', 'a')]

const props = () => ({ task: TASK, onBack: vi.fn(), onDone: vi.fn(), onLater: vi.fn().mockResolvedValue(undefined) })

const clock = () => screen.getByRole('timer', { name: 'Minuteur' })
const left = () => Number(screen.getByTestId('focus-ring').getAttribute('data-left'))

const advance = (ms: number) => act(() => void vi.advanceTimersByTime(ms))

describe('TaskFocus (MAG-499)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Only the clock, as in TaskBoard.test: the timer tests fake the interval themselves.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 10, 0))
    vi.stubGlobal('fetch', mockFetch)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    dataProvider.getList.mockResolvedValue({ data: STEPS, total: 2 })
    dataProvider.update.mockResolvedValue({ data: {} })
    dataProvider.create.mockImplementation((_resource: string, { data }: { data: { title: string } }) =>
      Promise.resolve({ data: step('01NEW', data.title, 'c') }),
    )
    mockFetch.mockResolvedValue(new Response('{}', { status: 200 }))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  const setup = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

  describe('render', () => {
    test('draws the task, its first step marked « 2 min », the ring at 10:00 and the petits pas in order', async () => {
      render(<TaskFocus {...props()} />)

      expect(screen.getByRole('heading', { name: 'Appeler la banque' })).toBeInTheDocument()
      expect(screen.getByText('Trouver le RIB')).toBeInTheDocument()
      expect(screen.getByText('2 min')).toBeInTheDocument()
      expect(clock()).toHaveTextContent('10:00')
      expect(left()).toBe(1)
      expect(screen.getByRole('button', { name: 'Démarrer' })).toBeInTheDocument()

      const boxes = await screen.findAllByRole('checkbox')
      expect(boxes.map((box) => (box as HTMLInputElement).closest('label')?.textContent)).toEqual([
        'Chercher le numéro',
        'Appeler le conseiller',
      ])
      expect(dataProvider.getList).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({ filter: { parent: '/api/tasks/01BANK' } }),
      )
    })

    test('has no « Faire avec Maggie » and no first step line when the task has none', async () => {
      render(<TaskFocus {...props()} task={{ ...TASK, firstStep: null }} />)
      await screen.findAllByRole('checkbox')

      expect(screen.queryByText('2 min')).toBeNull()
      expect(screen.queryByRole('button', { name: /Maggie/ })).toBeNull()
      expect(screen.getByRole('button', { name: "C'est fait" })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Je bloque : découpe-la' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Plus tard, sans souci' })).toBeInTheDocument()
    })

    test('a failing read of the petits pas says so and leaves the Focus usable', async () => {
      dataProvider.getList.mockRejectedValue(new Error('Boom'))
      render(<TaskFocus {...props()} />)

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Boom', { type: 'error' }))
      expect(screen.queryByRole('checkbox')).toBeNull()
      expect(screen.getByRole('button', { name: 'Démarrer' })).toBeEnabled()
    })
  })

  describe('the timer', () => {
    // The ticks are faked here, so these tests wait on nothing: they move the clock by hand.
    beforeEach(() => {
      vi.useRealTimers()
      vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
      vi.setSystemTime(new Date(2026, 9, 15, 10, 0))
    })

    test('start: the ring runs down from 10:00', async () => {
      const user = setup()
      render(<TaskFocus {...props()} />)

      await user.click(screen.getByRole('button', { name: 'Démarrer' }))
      expect(clock()).toHaveTextContent('10:00')
      advance(90_000)

      expect(clock()).toHaveTextContent('08:30')
      expect(left()).toBeCloseTo(0.85, 2)
      expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument()
    })

    test('pause: the time left stops', async () => {
      const user = setup()
      render(<TaskFocus {...props()} />)
      await user.click(screen.getByRole('button', { name: 'Démarrer' }))
      advance(60_000)

      await user.click(screen.getByRole('button', { name: 'Pause' }))
      advance(120_000)

      expect(clock()).toHaveTextContent('09:00')
      expect(screen.getByRole('button', { name: 'Reprendre' })).toBeInTheDocument()
    })

    test('resume: it goes on from where it stopped', async () => {
      const user = setup()
      render(<TaskFocus {...props()} />)
      await user.click(screen.getByRole('button', { name: 'Démarrer' }))
      advance(60_000)
      await user.click(screen.getByRole('button', { name: 'Pause' }))
      advance(120_000)

      await user.click(screen.getByRole('button', { name: 'Reprendre' }))
      advance(30_000)

      expect(clock()).toHaveTextContent('08:30')
      expect(screen.getByRole('button', { name: 'Pause' })).toBeInTheDocument()
    })

    test('finish: it reaches 00:00, the ring is empty and nothing blames', async () => {
      const user = setup()
      render(<TaskFocus {...props()} />)
      await user.click(screen.getByRole('button', { name: 'Démarrer' }))

      advance(10 * 60 * 1000)

      expect(clock()).toHaveTextContent('00:00')
      expect(left()).toBe(0)
      expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull()
      expect(screen.getByRole('button', { name: 'Encore 10 minutes' })).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Encore 10 minutes' }))
      expect(clock()).toHaveTextContent('10:00')
    })

    test('it keeps the time left across a re-render and a tab that comes back from the background', async () => {
      const user = setup()
      const { rerender } = render(<TaskFocus {...props()} />)
      await user.click(screen.getByRole('button', { name: 'Démarrer' }))

      // The tab sleeps: no tick runs, the clock moves on all the same.
      vi.setSystemTime(Date.now() + 4 * 60 * 1000)
      rerender(<TaskFocus {...props()} />)
      act(() => void document.dispatchEvent(new Event('visibilitychange')))

      expect(clock()).toHaveTextContent('06:00')
    })
  })

  describe('the petits pas', () => {
    test('ticking one writes it on the subtask alone, and unticking writes it back', async () => {
      const user = setup()
      render(<TaskFocus {...props()} />)

      const box = await screen.findByRole('checkbox', { name: 'Chercher le numéro' })
      await user.click(box)

      expect(box).toBeChecked()
      expect(dataProvider.update).toHaveBeenCalledWith('tasks', {
        id: '/api/tasks/01S1',
        data: { completedAt: expect.stringMatching(/^2026-10-15T/) },
        previousData: expect.objectContaining({ title: 'Chercher le numéro' }),
      })

      await user.click(box)
      expect(box).not.toBeChecked()
      expect(dataProvider.update).toHaveBeenLastCalledWith('tasks', expect.objectContaining({ data: { completedAt: null } }))
    })

    test('a refused tick is undone and says why', async () => {
      const user = setup()
      dataProvider.update.mockRejectedValue(new Error('Refusé'))
      render(<TaskFocus {...props()} />)

      const box = await screen.findByRole('checkbox', { name: 'Chercher le numéro' })
      await user.click(box)

      await waitFor(() => expect(box).not.toBeChecked())
      expect(mockNotify).toHaveBeenCalledWith('Erreur: Refusé', { type: 'error' })
    })

    test('« Je bloque » opens a field: a petit pas is created under the task, by hand', async () => {
      const user = setup()
      render(<TaskFocus {...props()} />)
      await screen.findAllByRole('checkbox')
      expect(screen.queryByRole('textbox', { name: 'Nouveau petit pas' })).toBeNull()

      await user.click(screen.getByRole('button', { name: 'Je bloque : découpe-la' }))
      await user.type(screen.getByRole('textbox', { name: 'Nouveau petit pas' }), 'Sortir la carte{Enter}')

      await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Sortir la carte' })).not.toBeChecked())
      expect(dataProvider.create).toHaveBeenCalledWith('tasks', {
        data: { title: 'Sortir la carte', parent: '/api/tasks/01BANK' },
      })
      expect(screen.getByRole('textbox', { name: 'Nouveau petit pas' })).toHaveValue('')
    })

    test('« Je bloque » works on a task with no petit pas yet, and the button adds too', async () => {
      const user = setup()
      dataProvider.getList.mockResolvedValue({ data: [], total: 0 })
      render(<TaskFocus {...props()} />)

      await user.click(screen.getByRole('button', { name: 'Je bloque : découpe-la' }))
      await user.type(screen.getByRole('textbox', { name: 'Nouveau petit pas' }), 'Ouvrir le courrier')
      await user.click(screen.getByRole('button', { name: 'Ajouter un petit pas' }))

      expect(await screen.findByRole('checkbox', { name: 'Ouvrir le courrier' })).toBeInTheDocument()
    })

    test('a refused creation says so and keeps what was typed', async () => {
      const user = setup()
      dataProvider.create.mockRejectedValue(new Error('Refusé'))
      render(<TaskFocus {...props()} />)
      await screen.findAllByRole('checkbox')

      await user.click(screen.getByRole('button', { name: 'Je bloque : découpe-la' }))
      await user.type(screen.getByRole('textbox', { name: 'Nouveau petit pas' }), 'Sortir la carte{Enter}')

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Refusé', { type: 'error' }))
      expect(screen.getByRole('textbox', { name: 'Nouveau petit pas' })).toHaveValue('Sortir la carte')
      expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    })
  })

  describe('the exits', () => {
    test('« C\'est fait » sends the merge-patch on completedAt, then hands back to the day', async () => {
      const user = setup()
      const p = props()
      render(<TaskFocus {...p} />)

      await user.click(screen.getByRole('button', { name: "C'est fait" }))

      await waitFor(() => expect(p.onDone).toHaveBeenCalledTimes(1))
      const [url, init] = mockFetch.mock.calls[0]
      expect(url).toBe('http://localhost/api/tasks/01BANK')
      expect(init?.method).toBe('PATCH')
      expect(JSON.parse(init!.body as string)).toEqual({ completedAt: '2026-10-15T08:00:00.000Z' })
      expect(mockNotify).toHaveBeenCalledWith('Tâche terminée', { type: 'success' })
    })

    test('a refused « C\'est fait » says so and stays in the Focus', async () => {
      const user = setup()
      mockFetch.mockResolvedValue(new Response('{}', { status: 500 }))
      const p = props()
      render(<TaskFocus {...p} />)

      await user.click(screen.getByRole('button', { name: "C'est fait" }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Tâche : 500', { type: 'error' }))
      expect(p.onDone).not.toHaveBeenCalled()
      expect(screen.getByRole('button', { name: "C'est fait" })).toBeEnabled()
    })

    test('« Plus tard, sans souci » asks the day to move the task', async () => {
      const user = setup()
      const p = props()
      render(<TaskFocus {...p} />)

      await user.click(screen.getByRole('button', { name: 'Plus tard, sans souci' }))

      expect(p.onLater).toHaveBeenCalledTimes(1)
    })

    test('a refused « Plus tard » says so and stays in the Focus', async () => {
      const user = setup()
      const p = props()
      p.onLater.mockRejectedValue(new Error('Journée : 422'))
      render(<TaskFocus {...p} />)

      await user.click(screen.getByRole('button', { name: 'Plus tard, sans souci' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Journée : 422', { type: 'error' }))
      expect(screen.getByRole('button', { name: 'Plus tard, sans souci' })).toBeEnabled()
    })

    test('« Retour à la journée » leaves without writing anything', async () => {
      const user = setup()
      const p = props()
      render(<TaskFocus {...p} />)

      await user.click(screen.getByRole('button', { name: 'Retour à la journée' }))

      expect(p.onBack).toHaveBeenCalledTimes(1)
      expect(mockFetch).not.toHaveBeenCalled()
    })
  })
})

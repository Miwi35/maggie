import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../../theme'
import { localDay } from '../../../dates'
import { emitMercure } from '../../../test/mercure'
import { cardRegistry } from '../cardRegistry'
import { TasksCard } from './TasksCard'
import type { HomeDay } from '../homeDay'
import type { TasksHomeState } from './tasksCardState'

vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

const base: TasksHomeState = {
  module: 'tasks',
  day: 'today',
  editable: true,
  tasks: [
    { id: 'T1', title: 'Appeler le plombier', done: false, overdueDays: 2, parentId: null },
    { id: 'T2', title: 'Réserver le train', done: false, overdueDays: null, parentId: null },
    { id: 'T3', title: 'Imprimer les billets', done: false, overdueDays: null, parentId: 'T2' },
    { id: 'T4', title: 'Payer la cantine', done: true, overdueDays: null, parentId: null },
  ],
  done: 1,
  total: 4,
  more: 0,
  overdue: 1,
  routines: [
    { id: 'R1', title: 'Étirements', anchor: 'wake', time: '07:30', checked: false },
    { id: 'R2', title: 'Vitamines', anchor: 'wake', time: '07:30', checked: true },
  ],
  saga: null,
}

const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: () => Promise.resolve(body) })

/** The API: `home` answers the reads, `writes` the PATCH/PUT/DELETE; every call is kept. */
const stubApi = (home: TasksHomeState | (() => TasksHomeState), writes: (url: string, init: RequestInit) => unknown = () => json({})) => {
  const calls: { url: string; method: string }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ url, method })
      if (url.includes('/api/home/tasks')) return json(typeof home === 'function' ? home() : home)

      return writes(url, init)
    }),
  )

  return calls
}

const renderCard = (day: HomeDay = 'today') =>
  render(
    <MemoryRouter>
      <ThemeProvider theme={veilleuseLightTheme}>
        <TasksCard day={day} />
      </ThemeProvider>
    </MemoryRouter>,
  )

describe('TasksCard', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  test('is registered on the Accueil, left column, first', () => {
    expect(cardRegistry.find((entry) => entry.module === 'tasks')).toMatchObject({ zone: 'left', order: 0 })
  })

  test('shows the skeleton, then the tasks, the ring, what is late and the routines', async () => {
    stubApi(base)
    renderCard()

    expect(screen.getByTestId('home-card-tasks')).toBeInTheDocument()
    expect(screen.getByTestId('home-tasks-loading')).toBeInTheDocument()

    expect(await screen.findByRole('checkbox', { name: 'Appeler le plombier : fait' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Payer la cantine : fait' })).toBeChecked()
    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('1 sur 4')
    expect(screen.getByRole('progressbar', { name: 'Tâches faites' })).toHaveAttribute('aria-valuetext', '1 sur 4')
    expect(screen.getByText('1 en retard')).toBeInTheDocument()
    expect(screen.getByText('2 j de retard')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tâches' })).toHaveAttribute('href', '/tasks')

    const routines = screen.getByRole('region', { name: 'Routines' })
    expect(within(routines).getByRole('link', { name: 'Routines' })).toHaveAttribute('href', '/tasks?tab=routines')
    expect(within(routines).getByRole('checkbox', { name: 'Étirements : fait' })).not.toBeChecked()
    expect(within(routines).getByRole('checkbox', { name: 'Vitamines : fait' })).toBeChecked()
    expect(screen.queryByText(/autres?$/)).not.toBeInTheDocument()
  })

  test('hides what is empty: no late word, no saga, no more', async () => {
    stubApi({ ...base, overdue: 0, more: 0, saga: null, tasks: base.tasks.map((t) => ({ ...t, overdueDays: null })) })
    renderCard()

    await screen.findByRole('checkbox', { name: 'Appeler le plombier : fait' })
    expect(screen.queryByText(/en retard/)).not.toBeInTheDocument()
    expect(screen.queryByText(/ · \d+\/\d+/)).not.toBeInTheDocument()
  })

  test('says the saga and the tasks left out, which open the tasks', async () => {
    stubApi({ ...base, more: 3, saga: { title: 'Déménagement', done: 2, total: 7 } })
    renderCard()

    expect(await screen.findByText('Déménagement · 2/7')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '+ 3 autres' })).toHaveAttribute('href', '/tasks')
  })

  test('says one more in the singular', async () => {
    stubApi({ ...base, more: 1 })
    renderCard()

    expect(await screen.findByRole('link', { name: '+ 1 autre' })).toBeInTheDocument()
  })

  test('says there is nothing planned, for the day it shows', async () => {
    stubApi({ ...base, tasks: [], routines: [], done: 0, total: 0, overdue: 0 })
    const { unmount } = renderCard()
    expect(await screen.findByText("Rien de prévu aujourd'hui")).toBeInTheDocument()
    expect(screen.queryByTestId('home-tasks-ring')).not.toBeInTheDocument()
    unmount()

    stubApi({ ...base, day: 'tomorrow', editable: false, tasks: [], routines: [], done: 0, total: 0, overdue: 0 })
    renderCard('tomorrow')
    expect(await screen.findByText('Rien de prévu demain')).toBeInTheDocument()
  })

  test('ticks a task at once, sends the done call, then reads the card again', async () => {
    const user = userEvent.setup()
    let answer = base
    const calls = stubApi(() => answer)
    renderCard()
    await screen.findByTestId('home-tasks-ring')

    answer = {
      ...base,
      tasks: base.tasks.map((t) => (t.id === 'T1' ? { ...t, done: true } : t)),
      done: 2,
      overdue: 0,
    }
    await user.click(screen.getByRole('checkbox', { name: 'Appeler le plombier : fait' }))

    expect(screen.getByRole('checkbox', { name: 'Appeler le plombier : fait' })).toBeChecked()
    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('2 sur 4')
    expect(screen.queryByText('1 en retard')).not.toBeInTheDocument()

    await waitFor(() => expect(calls.filter((c) => c.url.includes('/api/home/tasks'))).toHaveLength(2))
    expect(calls).toContainEqual({ url: expect.stringMatching(/\/tasks\/T1$/), method: 'PATCH' })
    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('2 sur 4')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('reopens a done task', async () => {
    const user = userEvent.setup()
    const calls = stubApi(base)
    renderCard()

    await user.click(await screen.findByRole('checkbox', { name: 'Payer la cantine : fait' }))

    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('0 sur 4')
    await waitFor(() => expect(calls).toContainEqual({ url: expect.stringMatching(/\/tasks\/T4$/), method: 'PATCH' }))
  })

  test('puts a task back and says so in words when the tick fails', async () => {
    const user = userEvent.setup()
    stubApi(base, () => json({ detail: 'SQLSTATE[23000] secret' }, 500))
    renderCard()

    await user.click(await screen.findByRole('checkbox', { name: 'Réserver le train : fait' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("Impossible d'enregistrer")
    expect(alert).not.toHaveTextContent(/SQLSTATE|500|Tâche :/)
    expect(screen.getByRole('checkbox', { name: 'Réserver le train : fait' })).not.toBeChecked()
    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('1 sur 4')
  })

  test('ticks a routine for the local day, and unticks it', async () => {
    const user = userEvent.setup()
    const writes: { url: string; method: string }[] = []
    stubApi(base, (url, init) => {
      writes.push({ url, method: init.method ?? 'GET' })

      return json({})
    })
    renderCard()
    const today = localDay(new Date())

    await user.click(await screen.findByRole('checkbox', { name: 'Étirements : fait' }))
    expect(screen.getByRole('checkbox', { name: 'Étirements : fait' })).toBeChecked()
    await waitFor(() => expect(writes).toHaveLength(1))
    expect(writes[0].method).toBe('PUT')
    expect(writes[0].url).toMatch(new RegExp(`/routines/R1/checks/${today}$`))

    await user.click(screen.getByRole('checkbox', { name: 'Vitamines : fait' }))
    await waitFor(() => expect(writes).toHaveLength(2))
    expect(writes[1].method).toBe('DELETE')
    expect(writes[1].url).toMatch(new RegExp(`/routines/R2/checks/${today}$`))
  })

  test('puts a routine back when its tick fails', async () => {
    const user = userEvent.setup()
    stubApi(base, () => json({ error: 'boom' }, 500))
    renderCard()

    await user.click(await screen.findByRole('checkbox', { name: 'Étirements : fait' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Impossible d'enregistrer")
    expect(screen.getByRole('checkbox', { name: 'Étirements : fait' })).not.toBeChecked()
  })

  test('leaves everything untickable for tomorrow', async () => {
    stubApi({ ...base, day: 'tomorrow', editable: false, done: 0, overdue: 0 })
    renderCard('tomorrow')

    await screen.findByRole('checkbox', { name: 'Appeler le plombier : fait' })
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(6)
    for (const box of boxes) expect(box).toBeDisabled()
    expect(screen.queryByTestId('home-tasks-ring')).not.toBeInTheDocument()
  })

  test('says the read failed, in words, and reads again on demand', async () => {
    const user = userEvent.setup()
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json({ detail: 'SQLSTATE secret' }, 500))
      .mockResolvedValueOnce(json(base))
    vi.stubGlobal('fetch', fetchMock)
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    expect(screen.queryByText(/SQLSTATE/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('home-tasks-ring')).toHaveTextContent('1 sur 4')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('reads again when the module publishes on home/tasks', async () => {
    let answer = base
    stubApi(() => answer)
    renderCard()
    await screen.findByTestId('home-tasks-ring')

    answer = { ...base, done: 3 }
    await emitMercure('/home/{module}', { action: 'refresh', module: 'tasks' })

    await waitFor(() => expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('3 sur 4'))
  })
})

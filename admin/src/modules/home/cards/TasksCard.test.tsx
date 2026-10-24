import { afterEach, beforeEach, describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../../theme'
import { addDays, localDay } from '../../../dates'
import { emitMercure } from '../../../test/mercure'
import { cardRegistry } from '../cardRegistry'
import homeConfig from '../home.config'
import { Home } from '../Home'
import { TasksCard } from './TasksCard'
import type { HomeDay } from '../homeDay'
import type { DayMoment } from '../../calendar/routinesApi'
import type { ViewTask } from '../../calendar/taskViews'

vi.mock('../../../hooks/useMercure', async () => (await import('../../../test/mercure')).mercureModule)

const task = (id: string, title: string, extra: Partial<ViewTask> = {}) =>
  ({ id, title, priority: 'normal', criticality: 'normal', ...extra }) as ViewTask

interface Reads {
  /** The open tasks: the « Aujourd'hui » view, and the tasks due tomorrow. */
  open: ViewTask[]
  done: ViewTask[]
  moments: DayMoment[]
}

const wake: DayMoment = {
  anchor: 'wake',
  time: '07:30',
  routines: [
    { id: 'R1', title: 'Étirements', anchor: 'wake', anchorTime: null, time: '07:30', remind: false, recurrence: { kind: 'daily' }, checked: false },
    { id: 'R2', title: 'Vitamines', anchor: 'wake', anchorTime: null, time: '07:30', remind: false, recurrence: { kind: 'daily' }, checked: true },
  ],
}

const base: Reads = {
  open: [
    task('T1', 'Appeler le plombier', { overdueDays: 2 }),
    task('T2', 'Réserver le train'),
    task('T3', 'Imprimer les billets', { parent: '/api/tasks/T2' }),
  ],
  done: [task('T4', 'Payer la cantine')],
  moments: [wake],
}

const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: () => Promise.resolve(body) })

/** The API: `reads` answers the tasks and routines resources, `writes` the PATCH/PUT/DELETE; every call is kept. */
const stubApi = (reads: Reads | (() => Reads), writes: (url: string, init: RequestInit) => unknown = () => json({}), status: () => number = () => 200) => {
  const calls: { url: string; method: string }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ url, method })
      if (method !== 'GET') return writes(url, init)
      if (status() !== 200) return json({ detail: 'SQLSTATE secret' }, status())
      const now = typeof reads === 'function' ? reads() : reads
      if (url.includes('/routines/day/')) return json({ date: 'd', moments: now.moments })
      if (decodeURIComponent(url).includes('completedAt[')) return json({ member: now.done })

      return json({ member: now.open })
    }),
  )

  return calls
}

const reads = (calls: { url: string }[], part: string) => calls.filter((c) => c.url.includes(part))

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

  test('is on the Accueil layout, left column, first', () => {
    expect(homeConfig.card?.[0]).toMatchObject({ key: 'tasks', column: 'left' })
    expect(cardRegistry.tasks).toBe(TasksCard)
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

  test('reads the business resources and never the Accueil endpoint', async () => {
    const calls = stubApi(base)
    renderCard()
    await screen.findByTestId('home-tasks-ring')

    expect(calls.some((c) => c.url.includes('/home/'))).toBe(false)
    expect(reads(calls, '/tasks/view/today')).toHaveLength(1)
    expect(reads(calls, '/routines/day/')).toHaveLength(1)
    const done = new URL(reads(calls, 'completedAt%5B')[0].url)
    expect(done.searchParams.get('completedAt[after]')).toMatch(/^\d{4}-\d\d-\d\dT00:00:00[+-]\d\d:\d\d$/)
    expect(done.searchParams.get('order[completedAt]')).toBe('desc')
    expect(done.searchParams.get('itemsPerPage')).toBe('5')
  })

  test('indents a subtask under its task', async () => {
    stubApi(base)
    renderCard()

    const row = (await screen.findByRole('checkbox', { name: 'Imprimer les billets : fait' })).closest('li')
    expect(getComputedStyle(row as HTMLElement).paddingLeft).not.toBe('0px')
  })

  test('hides what is empty: no late word, no more', async () => {
    stubApi({ ...base, open: base.open.map((t) => ({ ...t, overdueDays: null })) })
    renderCard()

    await screen.findByRole('checkbox', { name: 'Appeler le plombier : fait' })
    expect(screen.queryByText(/en retard/)).not.toBeInTheDocument()
    expect(screen.queryByText(/ · \d+\/\d+/)).not.toBeInTheDocument()
  })

  test('says the tasks left out, which open the tasks', async () => {
    const many = Array.from({ length: 7 }, (_, i) => task(`M${i}`, `Tâche ${i}`))
    stubApi({ ...base, open: many })
    renderCard()

    expect(await screen.findByRole('link', { name: '+ 3 autres' })).toHaveAttribute('href', '/tasks')
    expect(screen.getAllByRole('checkbox', { name: /Tâche \d : fait/ })).toHaveLength(4)
    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('1 sur 5')
  })

  test('says one more in the singular', async () => {
    stubApi({ ...base, open: Array.from({ length: 5 }, (_, i) => task(`M${i}`, `Tâche ${i}`)) })
    renderCard()

    expect(await screen.findByRole('link', { name: '+ 1 autre' })).toBeInTheDocument()
  })

  test('is not drawn at all with no task and no routine, whatever the day', async () => {
    stubApi({ open: [], done: [], moments: [] })
    const { unmount } = renderCard()
    expect(screen.getByTestId('home-tasks-loading')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByTestId('home-card-tasks')).not.toBeInTheDocument())
    expect(screen.queryByText(/Rien de prévu/)).not.toBeInTheDocument()
    unmount()

    stubApi({ open: [], done: [], moments: [] })
    renderCard('tomorrow')
    await waitFor(() => expect(screen.queryByTestId('home-tasks-loading')).not.toBeInTheDocument())
    expect(screen.queryByTestId('home-card-tasks')).not.toBeInTheDocument()
  })

  test('stays drawn with only a routine', async () => {
    stubApi({ ...base, open: [], done: [] })
    renderCard()

    expect(await screen.findByRole('region', { name: 'Routines' })).toBeInTheDocument()
    expect(screen.getByTestId('home-card-tasks')).toBeInTheDocument()
  })

  test('is not drawn when the tasks module is absent (404), and the Accueil says nothing is planned', async () => {
    stubApi(base, undefined, () => 404)
    render(
      <MemoryRouter>
        <ThemeProvider theme={veilleuseLightTheme}>
          <Home config={{ card: [{ key: 'tasks', column: 'left' }] }} registry={{ tasks: TasksCard }} />
        </ThemeProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByTestId('home-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('home-card-tasks')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('ticks a task at once, sends the done call, then reads the card again', async () => {
    const user = userEvent.setup()
    let answer = base
    const calls = stubApi(() => answer)
    renderCard()
    await screen.findByTestId('home-tasks-ring')

    answer = { ...base, open: base.open.slice(1), done: [task('T1', 'Appeler le plombier'), ...base.done] }
    await user.click(screen.getByRole('checkbox', { name: 'Appeler le plombier : fait' }))

    expect(screen.getByRole('checkbox', { name: 'Appeler le plombier : fait' })).toBeChecked()
    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('2 sur 4')
    expect(screen.queryByText('1 en retard')).not.toBeInTheDocument()

    await waitFor(() => expect(reads(calls, '/tasks/view/today')).toHaveLength(2))
    expect(calls).toContainEqual({ url: expect.stringMatching(/\/tasks\/T1$/), method: 'PATCH' })
    await waitFor(() => expect(screen.getByRole('checkbox', { name: 'Appeler le plombier : fait' })).toBeChecked())
    expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('2 sur 4')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test('reopens a done task', async () => {
    const user = userEvent.setup()
    const calls = stubApi(base, () => new Promise(() => {}))
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

      return new Promise(() => {})
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

  test('leaves everything untickable for tomorrow, and reads that day', async () => {
    const calls = stubApi(base)
    renderCard('tomorrow')

    await screen.findByRole('checkbox', { name: 'Appeler le plombier : fait' })
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(5)
    for (const box of boxes) expect(box).toBeDisabled()
    expect(screen.queryByTestId('home-tasks-ring')).not.toBeInTheDocument()
    expect(reads(calls, '/tasks/view/today')).toHaveLength(0)
    expect(reads(calls, 'completedAt%5B')).toHaveLength(0)
    const tomorrow = addDays(localDay(new Date()), 1)
    const due = new URL(reads(calls, 'dueDate')[0].url)
    expect(due.searchParams.get('exists[completedAt]')).toBe('false')
    expect(due.searchParams.get('dueDate[after]')).toBe(tomorrow)
    expect(due.searchParams.get('dueDate[before]')).toBe(tomorrow)
    expect(reads(calls, `/routines/day/${tomorrow}`)).toHaveLength(1)
  })

  test('says the read failed, in words, and reads again on demand', async () => {
    const user = userEvent.setup()
    let failing = 500
    stubApi(base, undefined, () => failing)
    renderCard()

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
    expect(screen.queryByText(/SQLSTATE/)).not.toBeInTheDocument()

    failing = 200
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('home-tasks-ring')).toHaveTextContent('1 sur 4')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  test.each([['/api/tasks/{id}'], ['/api/routines/{id}'], ['/api/routine_checks/{id}']])('reads again when %s publishes', async (topic) => {
    let answer = base
    stubApi(() => answer)
    renderCard()
    await screen.findByTestId('home-tasks-ring')

    answer = { ...base, open: base.open.slice(1), done: [task('T1', 'Appeler le plombier'), ...base.done] }
    await emitMercure(topic, { '@id': '/api/x/1' })

    await waitFor(() => expect(screen.getByTestId('home-tasks-ring')).toHaveTextContent('2 sur 4'))
  })
})

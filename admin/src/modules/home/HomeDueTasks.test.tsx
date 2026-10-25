import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { Home } from './Home'
import type { ViewTask } from '../calendar/taskViews'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const json = (body: unknown, status = 200) => ({ ok: status < 400, status, json: () => Promise.resolve(body) })

const dueToday = [
  { id: 'T1', title: 'Appeler le plombier', priority: 'normal', criticality: 'normal', dueDate: '2026-10-25' },
  { id: 'T2', title: 'Réserver le train', priority: 'normal', criticality: 'normal', dueDate: '2026-10-25' },
] as ViewTask[]

describe('Home with the real layout and cards', () => {
  beforeEach(() => localStorage.setItem('token', 'test-jwt'))
  afterEach(() => vi.unstubAllGlobals())

  /** The API as the Accueil reads it: two tasks due today; `secondary` answers the routines and the day moments. */
  const stubApi = (secondary: (url: string) => ReturnType<typeof json>) =>
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const decoded = decodeURIComponent(url)
        if (decoded.includes('/tasks/view/today')) return json({ member: dueToday, counts: { today: 2, upcoming: 0, someday: 0, done: 0 }, totalItems: 2 })
        if (decoded.includes('/task-settings/me') || decoded.includes('/routines/day/')) return secondary(decoded)
        if (decoded.includes('/api/tasks?')) return json({ member: [] })

        return json({ member: [] }, 404)
      }),
    )

  const renderHome = () =>
    render(
      <MemoryRouter>
        <ThemeProvider theme={veilleuseLightTheme}>
          <Home />
        </ThemeProvider>
      </MemoryRouter>,
    )

  const expectTheTwoTasks = async () => {
    expect(await screen.findByRole('checkbox', { name: 'Appeler le plombier : fait' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Réserver le train : fait' })).toBeInTheDocument()
    expect(screen.getByTestId('home-card-tasks')).toBeVisible()
    expect(screen.queryByTestId('home-empty')).not.toBeInTheDocument()
  }

  test('lists two tasks due today in the Tâches card', async () => {
    stubApi((url) => (url.includes('/routines/day/') ? json({ date: 'd', moments: [] }) : json({ dayMoments: { wake: '07:30' } })))
    renderHome()

    await expectTheTwoTasks()
  })

  test('still lists them when the routines of the day answer 404', async () => {
    stubApi((url) => (url.includes('/routines/day/') ? json({ error: 'Not Found' }, 404) : json({ dayMoments: { wake: '07:30' } })))
    renderHome()

    await expectTheTwoTasks()
  })

  test('still lists them when the day moments answer 404', async () => {
    stubApi((url) => (url.includes('/task-settings/me') ? json({ error: 'Not Found' }, 404) : json({ date: 'd', moments: [] })))
    renderHome()

    await expectTheTwoTasks()
  })

  test('still lists them when the routines fail', async () => {
    stubApi((url) => (url.includes('/routines/day/') ? json({ error: 'boom' }, 500) : json({ dayMoments: { wake: '07:30' } })))
    renderHome()

    await expectTheTwoTasks()
  })
})

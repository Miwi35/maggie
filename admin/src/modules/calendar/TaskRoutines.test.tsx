import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskRoutines } from './TaskRoutines'
import { countLabel, whenLabel } from './routinesApi'
import type { RecentDay, Routine } from './routinesApi'

const mockNotify = vi.fn()
vi.mock('react-admin', () => ({ useNotify: () => mockNotify }))

class MockEventSource {
  static last: MockEventSource | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {
    MockEventSource.last = this
  }
}

// 2026-10-15 is a Thursday; the seven days run from the 9th to it.
const days = (pattern: string): RecentDay[] =>
  [...pattern].map((c, i) => ({ date: `2026-10-${String(9 + i).padStart(2, '0')}`, due: c !== '.', checked: c === 'x' }))

const routine = (id: string, title: string, extra: Partial<Routine> = {}): Routine => ({
  id,
  title,
  anchor: 'wake',
  anchorTime: null,
  time: '07:00',
  recurrence: { kind: 'daily' },
  active: true,
  remind: false,
  createdAt: '2026-08-03T09:00:00+02:00',
  checkedToday: false,
  dueToday: true,
  monthCount: 13,
  totalCount: 40,
  recentDays: days('xxoxxxo'),
  ...extra,
})

const WATER = routine('01WATER', "Grand verre d'eau")
const READ = routine('01READ', 'Lire dix pages', { anchor: 'time', anchorTime: '22:30', time: '22:30', monthCount: 1 })
const PLANTS = routine('01PLANTS', 'Arroser les plantes', {
  anchor: 'afterDinner',
  time: '20:00',
  recurrence: { kind: 'weekly', weekday: 2 },
  dueToday: false,
  totalCount: 9,
  recentDays: days('....o..'),
})
const BILLS = routine('01BILLS', 'Relever les comptes', {
  recurrence: { kind: 'monthly', monthDay: 15 },
  totalCount: 2,
  createdAt: '2026-09-01T09:00:00+02:00',
})
const STRETCH = routine('01STRETCH', 'Étirements', { active: false, dueToday: false, recentDays: days('.......') })

let list: Routine[]

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

const mockFetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()

const respond = (url: string, init?: RequestInit): Response => {
  const method = init?.method ?? 'GET'
  if (url.endsWith('/routines') && method === 'GET') return json({ member: list })
  if (url.endsWith('/routines') && method === 'POST') {
    const body = JSON.parse(init!.body as string) as Partial<Routine>
    const created = routine('01NEW', body.title!, { ...body, monthCount: 0, totalCount: 0 })
    list = [...list, created]

    return json(created, 201)
  }
  const check = /\/routines\/(\w+)\/checks\/([\d-]+)$/.exec(url)
  if (check) {
    const checked = method === 'PUT'
    list = list.map((r) =>
      r.id === check[1] ? { ...r, checkedToday: checked, monthCount: r.monthCount + (checked ? 1 : -1) } : r,
    )

    return json(list.find((r) => r.id === check[1]))
  }
  const item = /\/routines\/(\w+)$/.exec(url)
  if (item && method === 'PATCH') {
    const patch = JSON.parse(init!.body as string) as Partial<Routine>
    list = list.map((r) => (r.id === item[1] ? { ...r, ...patch } : r))

    return json(list.find((r) => r.id === item[1]))
  }
  if (item && method === 'DELETE') {
    list = list.filter((r) => r.id !== item[1])

    return new Response(null, { status: 204 })
  }

  return json({ error: 'not found' }, 404)
}

const calls = (method: string) =>
  mockFetch.mock.calls.filter(([, init]) => (init?.method ?? 'GET') === method).map(([url, init]) => ({ url: String(url), init }))

const group = (name: string) => screen.getByRole('region', { name })

const card = (title: string) => screen.getAllByTestId('routine-card').find((li) => within(li).queryByText(title))!

const setup = () => userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

describe('TaskRoutines (MAG-511)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 15, 10, 0))
    list = [WATER, BILLS, PLANTS, READ, STRETCH]
    vi.stubGlobal('fetch', mockFetch)
    vi.stubGlobal('EventSource', MockEventSource)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    mockFetch.mockImplementation((url, init) => Promise.resolve(respond(String(url), init)))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('groups the routines: by part of the day for the daily ones, then by rhythm; an empty group is hidden', async () => {
    render(<TaskRoutines />)

    const morning = await screen.findByRole('region', { name: 'Matin' })
    expect(within(morning).getByText("Grand verre d'eau")).toBeInTheDocument()
    expect(within(card("Grand verre d'eau")).getByText('au réveil')).toBeInTheDocument()
    expect(within(card("Grand verre d'eau")).getByText('ce mois : 13 fois')).toBeInTheDocument()
    expect(within(morning).getByText('Étirements')).toBeInTheDocument()
    expect(within(group('Soir')).getByText('à 22 h 30')).toBeInTheDocument()
    expect(within(group('Soir')).getByText('ce mois : 1 fois')).toBeInTheDocument()
    expect(within(group('Chaque semaine')).getByText('le mardi, après le dîner')).toBeInTheDocument()
    expect(within(group('Chaque semaine')).getByText('depuis août : 9 fois')).toBeInTheDocument()
    expect(within(group('Chaque mois')).getByText('le 15, au réveil')).toBeInTheDocument()
    expect(within(group('Chaque mois')).getByText('depuis septembre : 2 fois')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Après-midi' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('heading').map((h) => h.textContent)).toEqual(['Matin', 'Soir', 'Chaque semaine', 'Chaque mois'])
  })

  test('the seven days: green done, grey due and not done, nothing when not due', async () => {
    render(<TaskRoutines />)

    await screen.findByText("Grand verre d'eau")
    const states = (title: string) =>
      within(card(title))
        .getAllByTestId('routine-day')
        .map((dot) => dot.dataset.state)
    expect(states("Grand verre d'eau")).toEqual(['checked', 'checked', 'missed', 'checked', 'checked', 'checked', 'missed'])
    expect(states('Arroser les plantes')).toEqual(['off', 'off', 'off', 'off', 'missed', 'off', 'off'])
    expect(within(card("Grand verre d'eau")).getByRole('img', { name: '7 derniers jours : 5 fois' })).toBeInTheDocument()
  })

  test('a tick only where the routine is due today; an inactive routine is dimmed', async () => {
    render(<TaskRoutines />)

    await screen.findByText("Grand verre d'eau")
    expect(screen.getByRole('checkbox', { name: "Grand verre d'eau : fait aujourd'hui" })).not.toBeChecked()
    expect(screen.queryByRole('checkbox', { name: "Arroser les plantes : fait aujourd'hui" })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: "Étirements : fait aujourd'hui" })).not.toBeInTheDocument()
    expect(card('Étirements')).toHaveStyle({ opacity: '0.5' })
  })

  test('ticking sends a PUT on today, unticking a DELETE, and the count follows', async () => {
    const user = setup()
    render(<TaskRoutines />)

    const box = await screen.findByRole('checkbox', { name: "Grand verre d'eau : fait aujourd'hui" })
    await user.click(box)

    await waitFor(() => expect(box).toBeChecked())
    expect(calls('PUT').map((c) => c.url)).toEqual(['http://localhost/api/routines/01WATER/checks/2026-10-15'])
    expect(within(card("Grand verre d'eau")).getByText('ce mois : 14 fois')).toBeInTheDocument()

    await user.click(box)

    await waitFor(() => expect(box).not.toBeChecked())
    expect(calls('DELETE').map((c) => c.url)).toEqual(['http://localhost/api/routines/01WATER/checks/2026-10-15'])
    expect(within(card("Grand verre d'eau")).getByText('ce mois : 13 fois')).toBeInTheDocument()
  })

  test('the tick is on the API\'s today, the last of the seven days, not the browser\'s date', async () => {
    // Past midnight in the browser, still the 15th for the user's time zone.
    vi.setSystemTime(new Date(2026, 9, 16, 0, 30))
    const user = setup()
    render(<TaskRoutines />)

    await user.click(await screen.findByRole('checkbox', { name: "Grand verre d'eau : fait aujourd'hui" }))

    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    expect(calls('PUT')[0].url).toBe('http://localhost/api/routines/01WATER/checks/2026-10-15')
  })

  test('a double click while the tick is on its way sends one PUT; the box waits for the answer', async () => {
    const user = setup()
    let answer: () => void = () => undefined
    render(<TaskRoutines />)

    const box = await screen.findByRole('checkbox', { name: "Grand verre d'eau : fait aujourd'hui" })
    mockFetch.mockImplementationOnce((url, init) => {
      const response = respond(String(url), init)

      return new Promise((resolve) => {
        answer = () => resolve(response)
      })
    })
    await user.dblClick(box)

    expect(calls('PUT')).toHaveLength(1)
    expect(box).toBeDisabled()
    answer()

    await waitFor(() => expect(box).toBeChecked())
    expect(box).toBeEnabled()
    expect(calls('PUT')).toHaveLength(1)
    expect(calls('DELETE')).toHaveLength(0)
  })

  test('the window coming back reads the list again: a page left open past midnight gets its new day', async () => {
    render(<TaskRoutines />)

    await screen.findByText("Grand verre d'eau")
    const reads = calls('GET').length
    list = [...list, routine('01MORE', 'Méditer', { time: '13:00', anchor: 'afterLunch' })]

    window.dispatchEvent(new Event('focus'))
    expect(await screen.findByRole('region', { name: 'Après-midi' })).toHaveTextContent('Méditer')

    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() => expect(calls('GET').length).toBe(reads + 2))
  })

  test('« Nouvelle routine » posts what, when, how often and the reminder', async () => {
    const user = setup()
    list = []
    render(<TaskRoutines />)

    await user.click(await screen.findByRole('button', { name: 'Nouvelle routine' }))
    const dialog = screen.getByRole('dialog', { name: 'Nouvelle routine' })
    await user.type(within(dialog).getByRole('textbox', { name: /Quoi/ }), 'Arroser les plantes')
    await user.click(within(dialog).getByRole('button', { name: 'Après le dîner' }))
    // Thursday the 15th: the weekly pill offers its own weekday, the list another.
    await user.click(within(dialog).getByRole('button', { name: 'Chaque jeudi' }))
    await user.click(within(dialog).getByRole('combobox', { name: 'Jour' }))
    await user.click(screen.getByRole('option', { name: 'mardi' }))
    await user.click(within(dialog).getByRole('switch', { name: 'Me le rappeler' }))
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(calls('POST')).toHaveLength(1))
    expect(JSON.parse(calls('POST')[0].init!.body as string)).toEqual({
      title: 'Arroser les plantes',
      anchor: 'afterDinner',
      anchorTime: null,
      recurrence: { kind: 'weekly', weekday: 2 },
      remind: true,
    })
    expect(await screen.findByRole('region', { name: 'Chaque semaine' })).toHaveTextContent('Arroser les plantes')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('a routine at an hour sends it; a blank title sends nothing', async () => {
    const user = setup()
    render(<TaskRoutines />)

    await user.click(await screen.findByRole('button', { name: 'Nouvelle routine' }))
    const dialog = screen.getByRole('dialog', { name: 'Nouvelle routine' })
    // No red on the field: « Enregistrer » simply waits for a title.
    expect(within(dialog).getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
    expect(within(dialog).getByRole('textbox', { name: /Quoi/ })).not.toHaveAttribute('aria-invalid', 'true')
    await user.type(within(dialog).getByRole('textbox', { name: /Quoi/ }), '   {Enter}')
    expect(within(dialog).getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
    expect(calls('POST')).toHaveLength(0)
    await user.clear(within(dialog).getByRole('textbox', { name: /Quoi/ }))

    await user.type(within(dialog).getByRole('textbox', { name: /Quoi/ }), 'Relever les comptes')
    await user.click(within(dialog).getByRole('button', { name: 'À une heure' }))
    const hour = within(dialog).getByLabelText('Heure')
    await user.clear(hour)
    await user.type(hour, '21:15')
    await user.click(within(dialog).getByRole('button', { name: 'Chaque mois' }))
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(calls('POST')).toHaveLength(1))
    expect(JSON.parse(calls('POST')[0].init!.body as string)).toEqual({
      title: 'Relever les comptes',
      anchor: 'time',
      anchorTime: '21:15',
      recurrence: { kind: 'monthly', monthDay: 15 },
      remind: false,
    })
  })

  test('a card opens from the keyboard; the change sends only the fields moved', async () => {
    const user = setup()
    render(<TaskRoutines />)

    await screen.findByText("Grand verre d'eau")
    const open = within(card("Grand verre d'eau")).getByRole('button')
    open.focus()
    await user.keyboard('{Enter}')

    const dialog = await screen.findByRole('dialog', { name: "Grand verre d'eau" })
    expect(within(dialog).getByRole('button', { name: 'Au réveil' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(within(dialog).getByRole('button', { name: 'Après le petit-déj' }))
    await user.click(within(dialog).getByRole('switch', { name: 'Active' }))
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(calls('PATCH')).toHaveLength(1))
    expect(calls('PATCH')[0].url).toBe('http://localhost/api/routines/01WATER')
    expect(JSON.parse(calls('PATCH')[0].init!.body as string)).toEqual({
      anchor: 'afterBreakfast',
      anchorTime: null,
      active: false,
    })
  })

  test('« Supprimer » asks first, then deletes', async () => {
    const user = setup()
    render(<TaskRoutines />)

    await screen.findByText('Lire dix pages')
    await user.click(within(card('Lire dix pages')).getByRole('button'))
    const dialog = await screen.findByRole('dialog', { name: 'Lire dix pages' })
    await user.click(within(dialog).getByRole('button', { name: 'Supprimer' }))
    expect(calls('DELETE')).toHaveLength(0)
    expect(within(dialog).getByText('Supprimer « Lire dix pages » ?')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Oui, supprimer' }))

    await waitFor(() => expect(calls('DELETE').map((c) => c.url)).toEqual(['http://localhost/api/routines/01READ']))
    await waitFor(() => expect(screen.queryByText('Lire dix pages')).not.toBeInTheDocument())
    expect(screen.queryByRole('region', { name: 'Soir' })).not.toBeInTheDocument()
  })

  test('« Annuler » the confirmation deletes nothing', async () => {
    const user = setup()
    render(<TaskRoutines />)

    await screen.findByText('Lire dix pages')
    await user.click(within(card('Lire dix pages')).getByRole('button'))
    const dialog = await screen.findByRole('dialog', { name: 'Lire dix pages' })
    await user.click(within(dialog).getByRole('button', { name: 'Supprimer' }))
    await user.click(within(dialog).getByRole('button', { name: 'Annuler' }))

    expect(within(dialog).getByRole('button', { name: 'Enregistrer' })).toBeInTheDocument()
    expect(calls('DELETE')).toHaveLength(0)
  })

  test('a list that cannot be read says so, and « Réessayer » reads it again', async () => {
    const user = setup()
    mockFetch.mockImplementationOnce(() => Promise.resolve(json({ error: 'boom' }, 500)))
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    render(<TaskRoutines />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Routines indisponibles')
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByRole('region', { name: 'Matin' })).toBeInTheDocument()
  })

  test('a refused tick says why and leaves the routine as it was', async () => {
    const user = setup()
    render(<TaskRoutines />)

    const box = await screen.findByRole('checkbox', { name: "Grand verre d'eau : fait aujourd'hui" })
    mockFetch.mockImplementationOnce(() => Promise.resolve(json({ error: 'date: in the future' }, 400)))
    await user.click(box)

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur : date: in the future', { type: 'info' }))
    expect(box).not.toBeChecked()
  })

  test('listens to the routines and their ticks, and reads the list again on an update', async () => {
    render(<TaskRoutines />)

    await screen.findByText("Grand verre d'eau")
    const url = decodeURIComponent(MockEventSource.last!.url)
    expect(url).toContain('/users/U1/api/routines/:id')
    expect(url).toContain('/users/U1/api/routine_checks/:id')

    list = [...list, routine('01MORE', 'Méditer', { time: '13:00', anchor: 'afterLunch' })]
    MockEventSource.last!.onmessage!(new MessageEvent('message', { data: '{}' }))

    expect(await screen.findByRole('region', { name: 'Après-midi' })).toHaveTextContent('Méditer')
  })
})

describe('routine wording', () => {
  test('when: a moment, an hour, the weekday, the date', () => {
    expect(whenLabel({ anchor: 'afterLunch', anchorTime: null, time: '13:00', recurrence: { kind: 'daily' } })).toBe('après le déjeuner')
    expect(whenLabel({ anchor: 'time', anchorTime: '07:00', time: '07:00', recurrence: { kind: 'daily' } })).toBe('à 7 h')
    expect(whenLabel({ anchor: 'wake', anchorTime: null, time: '07:00', recurrence: { kind: 'weekdays' } })).toBe('en semaine, au réveil')
    expect(whenLabel({ anchor: 'wake', anchorTime: null, time: '07:00', recurrence: { kind: 'monthly', monthDay: 1 } })).toBe('le 1er, au réveil')
  })

  test('the count: the month for a daily rhythm, since the month created for the others', () => {
    expect(countLabel({ recurrence: { kind: 'weekdays' }, monthCount: 0, totalCount: 3, createdAt: '2026-08-03T09:00:00+02:00' })).toBe('ce mois : 0 fois')
    expect(countLabel({ recurrence: { kind: 'monthly', monthDay: 1 }, monthCount: 1, totalCount: 1, createdAt: '2026-01-03T09:00:00+01:00' })).toBe('depuis janvier : 1 fois')
    // The user's own offset: the month as written, never turned into UTC (still August there).
    expect(countLabel({ recurrence: { kind: 'weekly', weekday: 1 }, monthCount: 0, totalCount: 4, createdAt: '2026-09-01T00:30:00+02:00' })).toBe('depuis septembre : 4 fois')
  })
})

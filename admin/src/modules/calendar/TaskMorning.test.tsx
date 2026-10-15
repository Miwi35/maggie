import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskMorning } from './TaskMorning'
import type { DayProposal, ProposalEntry } from './dayPlan'

const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useNotify: () => mockNotify,
}))

const entry = (id: string, title: string, minutes: number, reason: ProposalEntry['reason']): ProposalEntry => ({
  reason,
  minutes,
  task: {
    id,
    title,
    estimatedMinutes: minutes,
    firstStep: null,
    completedAt: null,
    dueDate: null,
    postponedSince: null,
    urgency: 'normal',
    criticality: 'low',
  },
})

const PROPOSAL: DayProposal = {
  date: '2026-10-16',
  slots: [
    { ...entry('A', 'Appeler la banque', 10, 'postponed'), position: 1, type: 'task' },
    { ...entry('B', 'Payer la cantine', 20, 'dueToday'), position: 2, type: 'task' },
    { ...entry('C', 'Trier les photos', 25, 'quick'), position: 3, type: 'task' },
  ],
  reserveRanked: [entry('R1', 'Arroser', 15, 'whenever'), entry('R2', 'Rendre le livre', 15, 'dueTomorrow')],
  totalMinutes: 55,
  freeMinutes: 150,
}

const mockFetch = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

const putBodies = () =>
  mockFetch.mock.calls
    .filter(([, init]) => init?.method === 'PUT')
    .map(([, init]) => (JSON.parse(init!.body as string) as { slots: { taskId: string }[] }).slots.map((s) => s.taskId))

const titles = () =>
  within(screen.getByRole('list', { name: 'Ta journée' }))
    .getAllByRole('listitem')
    .map((li) => li.textContent)

describe('TaskMorning — « Ta journée » (MAG-498)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('fetch', mockFetch)
    localStorage.setItem('user', JSON.stringify({ id: 'U1' }))
    mockFetch.mockImplementation((url, init) => {
      if (init?.method === 'PUT') return Promise.resolve(json({ id: '01DAY', confirmedAt: '2026-10-16T08:40:00+00:00', slots: [] }))

      return Promise.resolve(json(PROPOSAL))
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('draws the numbered list with why and minutes, and the total against the free time', async () => {
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={vi.fn()} />)

    expect(await screen.findByText('≈ 55 min · 2 h 30 libres')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Temps proposé sur le temps libre' })).toHaveAttribute('aria-valuenow', '37')
    expect(titles()[0]).toContain('Appeler la banque')
    expect(titles()[0]).toContain('reportée')
    expect(titles()[0]).toContain('10 min')
    expect(titles()[1]).toContain('à faire aujourd’hui')
    expect(titles()[2]).toContain('rapide')
    expect(screen.getByRole('list', { name: 'Ensuite' })).toHaveTextContent('Arroser')
    expect(mockFetch.mock.calls[0][0]).toBe('http://localhost/api/day-plans/2026-10-16/proposal')
  })

  test('reading the proposal writes nothing', async () => {
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={vi.fn()} />)
    await screen.findByText('Ta journée')

    expect(putBodies()).toEqual([])
  })

  test('swapping the 2nd takes the 1st of the reserve and sends the old one back, in one gesture', async () => {
    const user = userEvent.setup()
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Échanger Payer la cantine' }))

    expect(titles()[1]).toContain('Arroser')
    expect(screen.getByRole('list', { name: 'Ensuite' })).toHaveTextContent('Payer la cantine')
    expect(screen.getByText('≈ 50 min · 2 h 30 libres')).toBeInTheDocument()
    expect(putBodies()).toEqual([])
  })

  test('« C\'est parti » writes the five kept, in order, and hands the day back', async () => {
    const user = userEvent.setup()
    const onConfirmed = vi.fn()
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={onConfirmed} />)

    await user.click(await screen.findByRole('button', { name: 'Échanger Payer la cantine' }))
    await user.click(screen.getByRole('button', { name: "C'est parti" }))

    await waitFor(() => expect(onConfirmed).toHaveBeenCalledTimes(1))
    expect(putBodies()).toEqual([['A', 'R1', 'C']])
  })

  test('a refused write shows the error and keeps the proposal', async () => {
    const user = userEvent.setup()
    const onConfirmed = vi.fn()
    mockFetch.mockImplementation((url, init) =>
      Promise.resolve(init?.method === 'PUT' ? json({ error: 'Trop de tâches' }, 400) : json(PROPOSAL)),
    )
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={onConfirmed} />)

    await user.click(await screen.findByRole('button', { name: "C'est parti" }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Trop de tâches', { type: 'error' }))
    expect(onConfirmed).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: "C'est parti" })).toBeEnabled()
  })

  test('« Plus tard » leaves without writing', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<TaskMorning date="2026-10-16" onClose={onClose} onConfirmed={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Plus tard' }))

    expect(onClose).toHaveBeenCalled()
    expect(putBodies()).toEqual([])
  })

  test('without a reserve there is no swap', async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json({ ...PROPOSAL, reserveRanked: [] })))
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={vi.fn()} />)
    await screen.findByText('Ta journée')

    expect(screen.queryByRole('button', { name: /Échanger/ })).not.toBeInTheDocument()
  })

  test('an empty proposal says so and cannot be confirmed', async () => {
    mockFetch.mockImplementation(() =>
      Promise.resolve(json({ ...PROPOSAL, slots: [], reserveRanked: [], totalMinutes: 0 })),
    )
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={vi.fn()} />)

    expect(await screen.findByText("Rien à proposer aujourd'hui")).toBeInTheDocument()
    expect(screen.getByRole('button', { name: "C'est parti" })).toBeDisabled()
  })

  test('a failed read shows an error', async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json({ error: 'Date invalide' }, 400)))
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    render(<TaskMorning date="2026-10-16" onClose={vi.fn()} onConfirmed={vi.fn()} />)

    expect(await screen.findByText('Proposition indisponible')).toBeInTheDocument()
  })
})

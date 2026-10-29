import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProactionJournal, type JournalEntry } from './ProactionJournal'

/**
 * The journal of the plannings and proaction runs (MAG-688).
 *
 * A planning unfolds into the instructions it read and the proactions it scheduled; a failed
 * proaction shows the faulty tool and its error; « Relancer la planification maintenant » runs
 * the planning and puts its run on top, unfolded.
 */

const notify = vi.fn()

vi.mock('react-admin', () => ({
  useNotify: () => notify,
}))

const PLANNING: JournalEntry = {
  id: 'run-1',
  kind: 'planning',
  status: 'completed',
  day: '2026-10-29',
  startedAt: '2026-10-29T04:00:00+00:00',
  durationMs: 2400,
  proactionId: null,
  prompt: null,
  summary: null,
  instructions: [{ id: 'ins-1', content: 'Tous les matins à 7 h, fais-moi le digest de ma journée.' }],
  scheduled: [{ id: 'p-1', prompt: 'Fais le digest.', summary: 'Digest du matin', scheduledAt: '2026-10-29T06:00:00+00:00' }],
  toolCalls: [{ name: 'list_instructions', input: { kind: 'planning' }, result: '[…]', error: null }],
  response: 'Proaction posée : le digest à 7 h.',
  error: null,
  inputTokens: 1200,
  outputTokens: 80,
}

const FAILED_PROACTION: JournalEntry = {
  ...PLANNING,
  id: 'run-2',
  kind: 'proaction',
  status: 'failed',
  startedAt: '2026-10-29T06:00:00+00:00',
  proactionId: 'p-1',
  prompt: 'Fais le digest.',
  summary: 'Digest du matin',
  instructions: [],
  scheduled: [],
  toolCalls: [{ name: 'get_events_by_date', input: { date: '2026-10-29' }, result: '{"error": "Agenda unavailable"}', error: 'Agenda unavailable' }],
  response: null,
  error: 'LLM timeout',
  origin: { planningId: 'run-1', day: '2026-10-29', instructions: PLANNING.instructions },
}

interface Call {
  method: string
  url: string
}

let calls: Call[]

function stubApi({ journal = [FAILED_PROACTION, PLANNING], relaunch = { status: 200, body: { ...PLANNING, id: 'run-3' } as unknown } } = {}) {
  calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({ method, url })
      if (method === 'POST') return new Response(JSON.stringify(relaunch.body), { status: relaunch.status })
      return new Response(JSON.stringify(journal))
    }),
  )
}

describe('ProactionJournal', () => {
  beforeEach(() => notify.mockClear())
  afterEach(() => vi.unstubAllGlobals())

  test('lists the runs under their day, newest first', async () => {
    stubApi()
    render(<ProactionJournal />)

    const day = await screen.findByRole('region', { name: /29 octobre 2026/ })
    const titles = within(day).getAllByText(/Digest du matin|Planification du jour/).map((el) => el.textContent)
    expect(titles).toEqual(['Digest du matin', 'Planification du jour'])
    expect(calls[0]).toEqual({ method: 'GET', url: '/agent/journal?days=30' })
  })

  test('a planning unfolds into the instructions it read and the proactions it scheduled', async () => {
    stubApi()
    render(<ProactionJournal />)

    await userEvent.click(await screen.findByText('Planification du jour'))

    expect(await screen.findByText('Tous les matins à 7 h, fais-moi le digest de ma journée.')).toBeVisible()
    expect(screen.getByText(/07:00 · Digest du matin/)).toBeVisible()
    expect(screen.getByText('Proaction posée : le digest à 7 h.')).toBeVisible()
  })

  test('a failed proaction shows the faulty tool, its error and the planning it came from', async () => {
    stubApi()
    render(<ProactionJournal />)

    await userEvent.click(await screen.findByText('Digest du matin'))

    const failing = (await screen.findAllByTestId('journal-tool-call'))[0]
    expect(failing).toHaveTextContent('get_events_by_date')
    expect(failing).toHaveTextContent('Erreur : Agenda unavailable')
    expect(screen.getByText('LLM timeout')).toBeVisible()
    expect(screen.getByText(/Programmée par la planification du 2026-10-29/)).toBeVisible()
    expect(screen.getByText('Échec')).toBeInTheDocument()
  })

  test('« Relancer la planification maintenant » runs it and puts the run on top, unfolded', async () => {
    stubApi()
    render(<ProactionJournal />)
    await screen.findByText('Planification du jour')

    await userEvent.click(screen.getByRole('button', { name: 'Relancer la planification maintenant' }))

    await waitFor(() => expect(calls).toContainEqual({ method: 'POST', url: '/agent/journal/planning' }))
    expect(await screen.findAllByText('Planification du jour')).toHaveLength(2)
    expect(await screen.findByText('Tous les matins à 7 h, fais-moi le digest de ma journée.')).toBeVisible()
    expect(notify).toHaveBeenCalledWith('Planification relancée : 1 proaction(s) programmée(s)', { type: 'success' })
  })

  test('a relaunch that fails says so and reloads the journal, where its error is', async () => {
    stubApi({ relaunch: { status: 502, body: { detail: 'La planification a échoué' } } })
    render(<ProactionJournal />)
    await screen.findByText('Planification du jour')

    await userEvent.click(screen.getByRole('button', { name: 'Relancer la planification maintenant' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('La planification a échoué : son erreur est au journal', { type: 'error' }))
    expect(calls.filter((c) => c.method === 'GET')).toHaveLength(2)
  })

  test('an empty journal says so', async () => {
    stubApi({ journal: [] })
    render(<ProactionJournal />)

    expect(await screen.findByText('Rien au journal pour le moment.')).toBeInTheDocument()
  })

  test('a journal that cannot be read says so', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })))
    render(<ProactionJournal />)

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Le journal n’a pas pu être chargé', { type: 'error' }))
  })
})

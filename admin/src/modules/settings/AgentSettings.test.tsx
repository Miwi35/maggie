import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AgentSettings } from './AgentSettings'

const notify = vi.fn()

vi.mock('react-admin', () => ({
  useNotify: () => notify,
}))

class SilentEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
}

vi.stubGlobal('EventSource', SilentEventSource)

/**
 * The Instructions tab, and what a directive's `kind` means there (MAG-22).
 *
 * A planning rule says *when* Maggie acts on her own; a behaviour preference
 * says *how* she answers, and is injected into the system prompt of every
 * message. The tab is the one place the owner sees both, so the kind has to be
 * on the screen and on the request — a form that always sent `planning` could
 * only ever create half of them.
 */

const PLANNING: Record<string, unknown> = {
  id: 'i1',
  content: 'Résume-moi la journée à 9h',
  kind: 'planning',
  createdAt: '2026-10-01T09:00:00+00:00',
  updatedAt: null,
}

const BEHAVIOR: Record<string, unknown> = {
  id: 'i2',
  content: 'Tutoie-moi',
  kind: 'behavior',
  createdAt: '2026-10-01T10:00:00+00:00',
  updatedAt: null,
}

const PERSONALITY = { name: 'Maggie', language: 'fr', backstory: 'Assistante.' }

/** The four parallel GETs `loadData` fires, answered by path. */
function stubAgentApi(
  instructions: unknown[],
  overrides: Record<string, Response> = {},
  personality: typeof PERSONALITY = PERSONALITY,
) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const key = `${init?.method ?? 'GET'} ${url}`
    if (overrides[key]) return overrides[key]
    if (init?.method === 'POST' && url === '/agent/instructions') {
      const body = JSON.parse(String(init.body)) as { content: string; kind: string }
      return new Response(JSON.stringify({ id: 'new', ...body, createdAt: null, updatedAt: null }), {
        status: 201,
      })
    }
    if (url === '/agent/personality') return new Response(JSON.stringify(personality))
    if (url === '/agent/instructions') return new Response(JSON.stringify(instructions))
    return new Response(JSON.stringify([]))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function openInstructionsTab() {
  render(<AgentSettings />)
  await waitFor(() => expect(screen.getByRole('tab', { name: 'Instructions' })).toBeInTheDocument())
  await userEvent.click(screen.getByRole('tab', { name: 'Instructions' }))
}

describe('AgentSettings — instruction kinds', () => {
  beforeEach(() => {
    notify.mockClear()
    localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('tells a planning rule apart from a behaviour preference', async () => {
    stubAgentApi([BEHAVIOR, PLANNING])

    await openInstructionsTab()

    const behaviour = (await screen.findByText('Tutoie-moi')).closest('tr')
    const planning = (await screen.findByText('Résume-moi la journée à 9h')).closest('tr')
    expect(within(behaviour as HTMLElement).getByText('Comportement')).toBeInTheDocument()
    expect(within(planning as HTMLElement).getByText('Planification')).toBeInTheDocument()
  })

  test('sends the kind the owner picked', async () => {
    const fetchMock = stubAgentApi([])

    await openInstructionsTab()

    // The combobox, then the option: a behaviour preference is the one the form
    // could not create before MAG-22.
    await userEvent.click(screen.getByRole('combobox', { name: 'Type' }))
    await userEvent.click(screen.getByRole('option', { name: 'Comportement' }))
    await userEvent.type(screen.getByLabelText('Nouvelle instruction'), 'Tutoie-moi')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Instruction ajoutée', { type: 'success' }),
    )
    const posted = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === '/agent/instructions' && init?.method === 'POST',
    )
    expect(JSON.parse(String(posted?.[1]?.body))).toEqual({
      content: 'Tutoie-moi',
      kind: 'behavior',
    })
  })

  test('defaults to a planning rule, which is what every stored directive used to be', async () => {
    const fetchMock = stubAgentApi([])

    await openInstructionsTab()

    await userEvent.type(screen.getByLabelText('Nouvelle instruction'), 'Résume-moi la journée')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Instruction ajoutée', { type: 'success' }),
    )
    const posted = fetchMock.mock.calls.find(
      ([url, init]) => String(url) === '/agent/instructions' && init?.method === 'POST',
    )
    expect(JSON.parse(String(posted?.[1]?.body)).kind).toBe('planning')
  })

  test('says so when the agent refuses the directive', async () => {
    stubAgentApi([], {
      'POST /agent/instructions': new Response('{"detail":"nope"}', { status: 422 }),
    })

    await openInstructionsTab()

    await userEvent.type(screen.getByLabelText('Nouvelle instruction'), 'Tutoie-moi')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith("Erreur lors de l'ajout", { type: 'error' }),
    )
  })
})

describe('AgentSettings — title', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('is the name of the agent: Maggie by default', async () => {
    stubAgentApi([])
    render(<AgentSettings />)

    expect(await screen.findByRole('heading', { name: 'Maggie' })).toBeInTheDocument()
  })

  test('follows the name the user gave her', async () => {
    stubAgentApi([], {}, { ...PERSONALITY, name: 'Alfred' })
    render(<AgentSettings />)

    expect(await screen.findByRole('heading', { name: 'Alfred' })).toBeInTheDocument()
  })
})

/**
 * The Proactions tab reads `/api/proactions` and follows its Mercure topic (MAG-328): the
 * same rows as the agenda, no longer the agent's own list.
 */
const PROACTIONS_URL = '/api/proactions?order[startAt]=asc&pagination=false'

const proactionRow = (id: string, prompt: string, executionStatus: string, extra: Record<string, unknown> = {}) => ({
  id: `/api/proactions/${id}`,
  prompt,
  summary: prompt,
  executionStatus,
  startAt: '2026-10-15T08:30:00+02:00',
  completedAt: null,
  ...extra,
})

const jsonLd = (member: unknown[]) => new Response(JSON.stringify({ member }), { status: 200 })

async function openProactionsTab() {
  render(<AgentSettings />)
  await waitFor(() => expect(screen.getByRole('tab', { name: 'Proactions' })).toBeInTheDocument())
  await userEvent.click(screen.getByRole('tab', { name: 'Proactions' }))
}

describe('AgentSettings — proactions', () => {
  const sources: { url: string; onmessage: ((event: MessageEvent) => void) | null }[] = []

  beforeEach(() => {
    notify.mockClear()
    sources.length = 0
    localStorage.clear()
    localStorage.setItem('user', JSON.stringify({ id: 'u1' }))
    vi.stubGlobal(
      'EventSource',
      class {
        onmessage: ((event: MessageEvent) => void) | null = null
        close = vi.fn()
        constructor(public url: string) {
          sources.push(this)
        }
      },
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.stubGlobal('EventSource', SilentEventSource)
  })

  test('lists the runs of the API with their status in French', async () => {
    stubAgentApi([], {
      [`GET ${PROACTIONS_URL}`]: jsonLd([
        proactionRow('01A', 'Résumer la journée', 'completed', { completedAt: '2026-10-15T08:31:00+02:00' }),
        proactionRow('01B', 'Relancer le plombier', 'failed'),
        proactionRow('01C', 'Préparer le menu', 'pending'),
      ]),
    })

    await openProactionsTab()

    const done = (await screen.findByText('Résumer la journée')).closest('tr') as HTMLElement
    expect(within(done).getByText('Faite')).toBeInTheDocument()
    const failed = screen.getByText('Relancer le plombier').closest('tr') as HTMLElement
    expect(within(failed).getByText('Échouée')).toBeInTheDocument()
    const pending = screen.getByText('Préparer le menu').closest('tr') as HTMLElement
    expect(within(pending).getByText('En attente')).toBeInTheDocument()
  })

  test('does not ask the agent for the list any more', async () => {
    const fetchMock = stubAgentApi([], { [`GET ${PROACTIONS_URL}`]: jsonLd([]) })

    await openProactionsTab()

    expect(await screen.findByText('Aucune proaction pour le moment.')).toBeInTheDocument()
    expect(fetchMock.mock.calls.map(([url]) => String(url))).not.toContain('/agent/proactions')
  })

  test('says so when the list cannot be read', async () => {
    stubAgentApi([], { [`GET ${PROACTIONS_URL}`]: new Response('{}', { status: 500 }) })

    await openProactionsTab()

    await waitFor(() =>
      expect(notify).toHaveBeenCalledWith('Erreur lors du chargement des proactions', { type: 'error' }),
    )
  })

  test('shows a proaction scheduled meanwhile once the API publishes it', async () => {
    let rows = [proactionRow('01A', 'Résumer la journée', 'pending')]
    const fetchMock = stubAgentApi([], {})
    const base = fetchMock.getMockImplementation() as (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>
    fetchMock.mockImplementation(async (input, init) =>
      String(input) === PROACTIONS_URL ? jsonLd(rows) : base(input, init),
    )

    await openProactionsTab()
    await screen.findByText('Résumer la journée')
    await waitFor(() =>
      expect(sources.some((source) => decodeURIComponent(source.url).includes('/users/u1/api/proactions/:id'))).toBe(true),
    )

    rows = [...rows, proactionRow('01D', 'Appeler la crèche', 'pending')]
    sources.find((source) => decodeURIComponent(source.url).includes('/api/proactions/:id'))?.onmessage?.({ data: '{}' } as MessageEvent)

    expect(await screen.findByText('Appeler la crèche')).toBeInTheDocument()
  })
})

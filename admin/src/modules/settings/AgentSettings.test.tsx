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
function stubAgentApi(instructions: unknown[], overrides: Record<string, Response> = {}) {
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
    if (url === '/agent/personality') return new Response(JSON.stringify(PERSONALITY))
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

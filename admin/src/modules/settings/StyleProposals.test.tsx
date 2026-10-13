import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StyleProposals } from './StyleProposals'

/**
 * The style directives drawn from the owner's thumbs, waiting for their word (MAG-23).
 *
 * Nothing is applied before « Accepter »; the owner may reword first; « Refuser » writes no
 * directive; « Analyser mes retours » asks for a pass now.
 */

const notify = vi.fn()

vi.mock('react-admin', () => ({
  useNotify: () => notify,
}))

const PROPOSAL = {
  id: 'p1',
  content: 'Réponds en deux phrases au plus',
  reason: 'Deux pouces bas sur des réponses longues',
  status: 'pending',
  createdAt: '2026-10-13T05:00:00+00:00',
}

interface Call {
  method: string
  url: string
  body?: unknown
}

let calls: Call[]

function stubApi(
  { proposals = [PROPOSAL], status = 200, generated = [] as unknown[] }: { proposals?: unknown[]; status?: number; generated?: unknown[] } = {},
) {
  calls = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      calls.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (method === 'GET') return new Response(JSON.stringify(proposals))
      if (url.endsWith('/generate')) return new Response(JSON.stringify(generated), { status })
      if (url.endsWith('/accept')) {
        const content = (init?.body && JSON.parse(String(init.body)).content) || PROPOSAL.content
        return new Response(
          JSON.stringify({
            proposal: { ...PROPOSAL, status: 'accepted' },
            instruction: { id: 'i9', content, kind: 'behavior', createdAt: null, updatedAt: null },
          }),
          { status },
        )
      }
      return new Response(JSON.stringify({ ...PROPOSAL, status: 'rejected' }), { status })
    }),
  )
}

const posts = () => calls.filter((c) => c.method === 'POST')

describe('StyleProposals', () => {
  beforeEach(() => notify.mockClear())
  afterEach(() => vi.unstubAllGlobals())

  test('lists the pending proposals with why they are made', async () => {
    stubApi()
    render(<StyleProposals onAccepted={vi.fn()} />)

    expect(await screen.findByDisplayValue('Réponds en deux phrases au plus')).toBeInTheDocument()
    expect(screen.getByText('Deux pouces bas sur des réponses longues')).toBeInTheDocument()
    expect(calls[0]).toEqual({ method: 'GET', url: '/agent/style-proposals', body: undefined })
  })

  test('says so when nothing is pending', async () => {
    stubApi({ proposals: [] })
    render(<StyleProposals onAccepted={vi.fn()} />)

    expect(await screen.findByText(/Aucune proposition en attente/)).toBeInTheDocument()
  })

  test('accepting hands the new directive over and removes the proposal', async () => {
    stubApi()
    const onAccepted = vi.fn()
    const user = userEvent.setup()
    render(<StyleProposals onAccepted={onAccepted} />)

    await user.click(await screen.findByRole('button', { name: 'Accepter' }))

    await waitFor(() => expect(onAccepted).toHaveBeenCalledWith(expect.objectContaining({ id: 'i9', kind: 'behavior' })))
    expect(posts()).toEqual([{ method: 'POST', url: '/agent/style-proposals/p1/accept', body: undefined }])
    expect(screen.queryByTestId('style-proposal')).not.toBeInTheDocument()
  })

  test('a reworded proposal is accepted as reworded', async () => {
    stubApi()
    const user = userEvent.setup()
    render(<StyleProposals onAccepted={vi.fn()} />)

    const field = await screen.findByRole('textbox', { name: 'Directive proposée' })
    await user.clear(field)
    await user.type(field, 'Sois brève')
    await user.click(screen.getByRole('button', { name: 'Accepter' }))

    await waitFor(() => expect(posts()[0]?.body).toEqual({ content: 'Sois brève' }))
  })

  test('refusing writes no directive', async () => {
    stubApi()
    const onAccepted = vi.fn()
    const user = userEvent.setup()
    render(<StyleProposals onAccepted={onAccepted} />)

    await user.click(await screen.findByRole('button', { name: 'Refuser' }))

    await waitFor(() => expect(screen.queryByTestId('style-proposal')).not.toBeInTheDocument())
    expect(posts()).toEqual([{ method: 'POST', url: '/agent/style-proposals/p1/reject', body: undefined }])
    expect(onAccepted).not.toHaveBeenCalled()
  })

  test('a failed decision keeps the proposal and says so', async () => {
    stubApi({ status: 500 })
    const user = userEvent.setup()
    render(<StyleProposals onAccepted={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Accepter' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith("La proposition n'a pas pu être enregistrée", { type: 'error' }))
    expect(screen.getByTestId('style-proposal')).toBeInTheDocument()
  })

  test('« Analyser mes retours » asks for a pass and reloads', async () => {
    stubApi({ proposals: [], generated: [] })
    const user = userEvent.setup()
    render(<StyleProposals onAccepted={vi.fn()} />)

    await user.click(await screen.findByRole('button', { name: 'Analyser mes retours' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Rien de nouveau à proposer', { type: 'info' }))
    expect(posts()).toEqual([{ method: 'POST', url: '/agent/style-proposals/generate', body: undefined }])
    expect(calls.filter((c) => c.method === 'GET')).toHaveLength(2)
  })
})

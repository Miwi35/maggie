import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ContactPicker } from './ContactPicker'

const CONTACTS = [
  { id: '01TOM', name: 'Tom', notes: 'Coloc' },
  { id: '01ANNA', name: 'Anna', notes: null },
  { id: '01TOMMY', name: 'Tommy', notes: null },
]

const answer = (ok: boolean, body: unknown, status = ok ? 200 : 400) =>
  Promise.resolve({ ok, status, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()

const renderPicker = (onPick = vi.fn()) => {
  render(<ContactPicker open onClose={vi.fn()} onPick={onPick} />)

  return onPick
}

const options = () => within(screen.getByRole('list', { name: 'Contacts' })).getAllByRole('button')

describe('ContactPicker', () => {
  beforeEach(() => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) =>
      init?.method === 'POST'
        ? answer(true, { '@id': '/api/contacts/01JADE', id: '01JADE', name: 'Jade', notes: null }, 201)
        : url.startsWith('/api/contacts')
          ? answer(true, { member: CONTACTS })
          : answer(false, {}, 404),
    )
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('lists the contacts and narrows them as one types, case and accents aside', async () => {
    const user = userEvent.setup()
    renderPicker()

    expect(await screen.findByRole('button', { name: /Anna/ })).toBeInTheDocument()
    expect(options()).toHaveLength(3)

    await user.type(screen.getByLabelText('Rechercher un contact'), 'tóm')

    expect(options().map((o) => o.textContent)).toEqual(['TomColoc', 'Tommy'])
  })

  test('picking a contact hands it over, nothing created', async () => {
    const user = userEvent.setup()
    const onPick = renderPicker()

    await user.click(await screen.findByRole('button', { name: /Anna/ }))

    expect(onPick).toHaveBeenCalledWith({ id: '01ANNA', name: 'Anna', notes: null })
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  })

  test('an exact match offers no creation', async () => {
    const user = userEvent.setup()
    renderPicker()

    await user.type(await screen.findByLabelText('Rechercher un contact'), 'tom')

    expect(screen.queryByRole('button', { name: /Créer/ })).not.toBeInTheDocument()
  })

  test('« + Créer « Jade » » creates the contact, then hands it over', async () => {
    const user = userEvent.setup()
    const onPick = renderPicker()

    await screen.findByRole('button', { name: /Anna/ })
    await user.type(screen.getByLabelText('Rechercher un contact'), 'Jade ')
    await user.click(screen.getByRole('button', { name: '+ Créer « Jade »' }))

    await waitFor(() => expect(onPick).toHaveBeenCalledWith({ id: '01JADE', name: 'Jade', notes: null }))
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post?.[0]).toBe('/api/contacts')
    expect(JSON.parse(post?.[1].body as string)).toEqual({ name: 'Jade' })
  })

  test('a refused creation says why and hands nothing over', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'POST' ? answer(false, { detail: 'name: This value should not be blank.' }, 422) : answer(true, { member: [] }),
    )
    const user = userEvent.setup()
    const onPick = renderPicker()

    expect(await screen.findByText('Aucun contact')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Rechercher un contact'), 'Jade')
    await user.click(screen.getByRole('button', { name: '+ Créer « Jade »' }))

    expect(await screen.findByText('name: This value should not be blank.')).toBeInTheDocument()
    expect(onPick).not.toHaveBeenCalled()
  })
})

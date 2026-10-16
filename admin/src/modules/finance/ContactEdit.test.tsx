import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { ContactCreate } from './ContactCreate'
import { ContactEdit } from './ContactEdit'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const TOM = { id: '/api/contacts/01TOM', name: 'Tom', notes: 'Coloc' }

const VIR = {
  '@id': '/api/contact_counterparties/01VIR',
  id: '01VIR',
  contact: '/api/contacts/01TOM',
  label: 'VIR. DE TOM MARTIN',
  matchKey: 'vir de tom martin',
  transactionCount: 1,
  lastSeenOn: '2026-03-17',
}
const LYDIA = {
  '@id': '/api/contact_counterparties/01LYD',
  id: '01LYD',
  contact: '/api/contacts/01TOM',
  label: 'LYDIA TOM MARTIN',
  matchKey: 'lydia tom martin',
  transactionCount: 3,
  lastSeenOn: '2026-04-02',
}
const PAYPAL_SUGGESTION = { label: 'PAYPAL *TOMMARTIN', transactionCount: 2, lastSeenOn: '2026-02-10' }

const answer = (ok: boolean, body: unknown, status = ok ? 200 : 400) =>
  Promise.resolve({ ok, status, json: async () => body }) as Promise<Response>

const fetchMock = vi.fn()
let linked: unknown[] = []
let suggested: unknown[] = []
let refusal: string | null = null

/** A tiny API: the two reads, and the writes that move a label between them. */
const api = (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (url.startsWith('/api/contact_counterparties?')) return answer(true, { member: linked })
  if (url === '/api/finance/contacts/01TOM/counterparty-suggestions') return answer(true, suggested)
  if (method === 'POST' && url === '/api/contact_counterparties') {
    if (refusal) return answer(false, { detail: refusal })
    const { label } = JSON.parse(init?.body as string) as { label: string }
    linked = [...linked, { ...VIR, id: '01NEW', label, transactionCount: 2, lastSeenOn: '2026-02-10' }]
    suggested = suggested.filter((s) => (s as { label: string }).label !== label)

    return answer(true, {}, 201)
  }
  if (method === 'DELETE') {
    linked = linked.filter((c) => !url.endsWith(`/${(c as { id: string }).id}`))

    return answer(true, null, 204)
  }

  return answer(false, {}, 404)
}

const calls = (method: string) =>
  fetchMock.mock.calls
    .filter(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET') === method)
    .map(([url, init]) => ({
      url: url as string,
      body: (init as RequestInit).body ? JSON.parse((init as RequestInit).body as string) : undefined,
    }))

const renderEdit = () =>
  render(
    <MemoryRouter initialEntries={[`/contacts/${encodeURIComponent(TOM.id)}`]}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: (() => Promise.resolve({ data: TOM })) as unknown as DataProvider['getOne'],
        })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="contacts">
          <Routes>
            <Route path="/contacts/:id" element={<ContactEdit />} />
          </Routes>
        </ResourceContextProvider>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

const section = () => screen.getByRole('region', { name: 'Ses comptes' })

describe('ContactEdit — the « fiche contact »', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    linked = [VIR, LYDIA]
    suggested = [PAYPAL_SUGGESTION]
    refusal = null
    fetchMock.mockImplementation(api)
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('shows the name and notes, then the labels with their transfers and the suggestions', async () => {
    renderEdit()

    expect(await screen.findByDisplayValue('Tom')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Coloc')).toBeInTheDocument()

    const labels = await within(section()).findByRole('list', { name: 'Libellés liés' })
    expect(within(labels).getByText('VIR. DE TOM MARTIN')).toBeInTheDocument()
    expect(within(labels).getByText('1 virement · dernier le 17/03/2026')).toBeInTheDocument()
    expect(within(labels).getByText('3 virements · dernier le 02/04/2026')).toBeInTheDocument()

    expect(screen.getByText('Peut-être aussi Tom :')).toBeInTheDocument()
    const suggestions = screen.getByRole('list', { name: 'Suggestions' })
    expect(within(suggestions).getByText('PAYPAL *TOMMARTIN')).toBeInTheDocument()
    expect(within(suggestions).getByText('2 virements · dernier le 10/02/2026')).toBeInTheDocument()

    expect(fetchMock.mock.calls[0][0]).toBe(
      `/api/contact_counterparties?contact=${encodeURIComponent('/api/contacts/01TOM')}&itemsPerPage=100`,
    )
  })

  test('« Retirer » unlinks the label and reads the list again', async () => {
    const user = userEvent.setup()
    renderEdit()

    await user.click(await screen.findByRole('button', { name: 'Retirer VIR. DE TOM MARTIN' }))

    await waitFor(() => expect(calls('DELETE')).toEqual([{ url: '/api/contact_counterparties/01VIR', body: undefined }]))
    await waitFor(() => expect(within(section()).queryByText('VIR. DE TOM MARTIN')).not.toBeInTheDocument())
    expect(within(section()).getByText('LYDIA TOM MARTIN')).toBeInTheDocument()
  })

  test('a label typed by hand is linked to the contact', async () => {
    const user = userEvent.setup()
    renderEdit()

    await user.type(await screen.findByLabelText('Ajouter un libellé'), 'WERO TOM M')
    await user.click(within(section()).getByRole('button', { name: 'Ajouter' }))

    await waitFor(() =>
      expect(calls('POST')).toEqual([
        { url: '/api/contact_counterparties', body: { contact: '/api/contacts/01TOM', label: 'WERO TOM M' } },
      ]),
    )
    expect(await within(section()).findByText('WERO TOM M')).toBeInTheDocument()
    expect(screen.getByLabelText('Ajouter un libellé')).toHaveValue('')
  })

  test('« Ajouter » on a suggestion links it, and it leaves the suggestions for the labels', async () => {
    const user = userEvent.setup()
    renderEdit()

    await user.click(await screen.findByRole('button', { name: 'Ajouter PAYPAL *TOMMARTIN' }))

    await waitFor(() =>
      expect(calls('POST')).toEqual([
        { url: '/api/contact_counterparties', body: { contact: '/api/contacts/01TOM', label: 'PAYPAL *TOMMARTIN' } },
      ]),
    )
    const labels = within(section()).getByRole('list', { name: 'Libellés liés' })
    expect(await within(labels).findByText('PAYPAL *TOMMARTIN')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Peut-être aussi Tom :')).not.toBeInTheDocument())
  })

  test('a label already someone else\'s is refused with the API\'s message, and the field keeps it', async () => {
    refusal = '« VIR. DE ANNA » is already linked to Anna.'
    const user = userEvent.setup()
    renderEdit()

    await user.type(await screen.findByLabelText('Ajouter un libellé'), 'VIR. DE ANNA')
    await user.click(within(section()).getByRole('button', { name: 'Ajouter' }))

    expect(await screen.findByText('« VIR. DE ANNA » is already linked to Anna.')).toBeInTheDocument()
    expect(screen.getByLabelText('Ajouter un libellé')).toHaveValue('VIR. DE ANNA')
  })
})

describe('ContactCreate', () => {
  test('creates a contact from its name and notes', async () => {
    const user = userEvent.setup()
    const create = vi.fn().mockResolvedValue({ data: { ...TOM, id: '/api/contacts/01NEW' } })
    render(
      <MemoryRouter initialEntries={['/contacts/create']}>
        <AdminContext
          dataProvider={testDataProvider({ create: create as unknown as DataProvider['create'] })}
          i18nProvider={i18nProvider}
        >
          <ResourceContextProvider value="contacts">
            <Routes>
              <Route path="/contacts/create" element={<ContactCreate />} />
              <Route path="*" element={<p>ailleurs</p>} />
            </Routes>
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    await user.type(await screen.findByLabelText(/Nom/), 'Tom')
    await user.type(screen.getByLabelText('Notes'), 'Coloc')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][0]).toBe('contacts')
    expect(create.mock.calls[0][1].data).toEqual({ name: 'Tom', notes: 'Coloc' })
  })
})

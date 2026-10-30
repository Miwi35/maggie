import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { recordedResponse } from '../../test/recordedResponse'
import { AccountList } from './AccountList'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

const ACCOUNTS = [
  { id: '/api/accounts/01A', name: 'Compte courant', bank: 'Boursorama', type: 'checking', currency: 'EUR', balanceCents: 120050, isCushion: false },
  { id: '/api/accounts/01B', name: 'Livret A', bank: 'Crédit Agricole', type: 'savings', currency: 'EUR', balanceCents: 500000, isCushion: true },
]

const remove = vi.fn()

const renderList = (initial = ACCOUNTS) => {
  let rows = [...initial]
  const dataProvider = testDataProvider({
    getList: (() => Promise.resolve({ data: rows, total: rows.length })) as unknown as DataProvider['getList'],
    delete: ((resource: string, params: { id: string }) => {
      rows = rows.filter((row) => row.id !== params.id)

      return remove(resource, params)
    }) as unknown as DataProvider['delete'],
  })

  return render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider}>
        <ResourceContextProvider value="accounts">
          <AccountList />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
}

const stubBankConnections = (lastSyncedAt: string | null) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify({ connections: lastSyncedAt ? [{ lastSyncedAt }] : [] }))),
  )

const cards = async () => screen.findAllByTestId('account-card')

describe('AccountList, as the mock-up draws Comptes (MAG-632)', () => {
  beforeEach(() => {
    remove.mockReset()
    remove.mockImplementation((_: string, { id }: { id: string }) => Promise.resolve({ data: { id } }))
    stubBankConnections(null)
  })
  afterEach(() => vi.unstubAllGlobals())

  test('draws one card per account: name, bank · type, balance, and the cushion pastille only on a cushion account', async () => {
    renderList()

    const [courant, livret] = await cards()
    expect(within(courant).getByText('Compte courant')).toBeInTheDocument()
    expect(within(courant).getByText('Boursorama · Compte courant')).toBeInTheDocument()
    expect(courant).toHaveTextContent(/1\s200,50\s€/)
    expect(within(courant).queryByText('Matelas')).toBeNull()

    expect(within(livret).getByText('Livret A')).toBeInTheDocument()
    expect(within(livret).getByText('Crédit Agricole · Épargne')).toBeInTheDocument()
    expect(livret).toHaveTextContent(/5\s000,00\s€/)
    expect(within(livret).getByText('Matelas')).toBeInTheDocument()
  })

  test('the cushion pastille shows on the account as GET /api/accounts records it, with no Mercure event yet', async () => {
    const recorded = recordedResponse<{ member: Record<string, unknown>[] }>('accounts.collection.json')
    expect(recorded.member.some((account) => account.cushion === true)).toBe(true)

    renderList(recorded.member as typeof ACCOUNTS)

    const [card] = await cards()
    expect(within(card).getByText('Matelas')).toBeInTheDocument()
  })

  test('a card opens the transactions of its account', async () => {
    renderList()

    const [courant] = await cards()

    expect(within(courant).getByRole('link', { name: /Compte courant/ })).toHaveAttribute('href', '/accounts/01A/transactions')
  })

  test('is a page of cards: no table, no edit or delete column, no tabs', async () => {
    renderList()
    await cards()

    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Éditer' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Supprimer' })).toBeNull()
  })

  test('« Ajouter un compte » opens the creation form', async () => {
    renderList()
    await cards()

    expect(screen.getByRole('link', { name: 'Ajouter un compte' })).toHaveAttribute('href', '/accounts/create')
  })

  test('shows the last import beside the accounts, with the age of the last bank synchronisation', async () => {
    stubBankConnections(new Date(Date.now() - 12 * 60000).toISOString())
    renderList()

    const last = await screen.findByTestId('last-import-card')
    expect(last).toHaveTextContent('Dernier import')
    await waitFor(() => expect(last).toHaveTextContent('Synchronisé il y a 12 min'))
  })

  test('says so when no import ever ran', async () => {
    renderList()

    const last = await screen.findByTestId('last-import-card')
    expect(last).toHaveTextContent('Dernier import')
    expect(last).toHaveTextContent('Jamais')
  })

  test('the edit and the delete of an account sit in the ⋮ menu of its card', async () => {
    const user = userEvent.setup()
    renderList()
    const [courant] = await cards()

    await user.click(within(courant).getByRole('button', { name: 'Actions de Compte courant' }))

    expect(screen.getByRole('menuitem', { name: 'Modifier' })).toHaveAttribute('href', '/accounts/%2Fapi%2Faccounts%2F01A')
    expect(screen.getByRole('menuitem', { name: 'Supprimer' })).toBeInTheDocument()
  })

  test('deleting an account asks first, and keeps it when the confirmation is dismissed', async () => {
    const user = userEvent.setup()
    renderList()
    const [courant] = await cards()

    await user.click(within(courant).getByRole('button', { name: 'Actions de Compte courant' }))
    await user.click(screen.getByRole('menuitem', { name: 'Supprimer' }))
    expect(await screen.findByText(/Supprimer le compte « Compte courant » ?/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Annuler' }))

    expect(remove).not.toHaveBeenCalled()
  })

  test('deleting an account, once confirmed, deletes it and takes its card away', async () => {
    const user = userEvent.setup()
    renderList()
    const [courant] = await cards()

    await user.click(within(courant).getByRole('button', { name: 'Actions de Compte courant' }))
    await user.click(screen.getByRole('menuitem', { name: 'Supprimer' }))
    await user.click(await screen.findByRole('button', { name: 'Confirmer' }))

    await waitFor(() => expect(remove).toHaveBeenCalled())
    expect(remove.mock.calls[0][1]).toMatchObject({ id: '/api/accounts/01A' })
    await waitFor(() => expect(screen.queryByText('Compte courant')).toBeNull())
  })

  test('the import of statements and the contacts are in the ⋮ menu of the page', async () => {
    const user = userEvent.setup()
    renderList()
    await cards()

    await user.click(screen.getByRole('button', { name: 'Plus d\'actions' }))

    expect(screen.getByRole('menuitem', { name: 'Import de relevés' })).toHaveAttribute('href', '/finance/import')
    expect(screen.getByRole('menuitem', { name: 'Contacts' })).toHaveAttribute('href', '/contacts')
  })

  test('without account, says so and still offers to add one, with no explanation', async () => {
    renderList([])

    expect(await screen.findByText("Aucun compte pour l'instant")).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ajouter un compte' })).toHaveAttribute('href', '/accounts/create')
    expect(screen.getByRole('button', { name: "Plus d'actions" })).toBeInTheDocument()
    expect(screen.queryByText(/Ajoutez vos comptes courants/)).toBeNull()
  })
})

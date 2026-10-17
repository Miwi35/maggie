import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AccountsTile } from './AccountsTile'
import { dashboardFixture } from './dashboardFixture'
import type { DashboardTodo } from './useFinanceDashboard'

const renderTile = (tile = dashboardFixture().tiles.accounts, todo: DashboardTodo = dashboardFixture().todo) =>
  render(
    <MemoryRouter>
      <AccountsTile tile={tile} todo={todo} />
    </MemoryRouter>,
  )

describe('AccountsTile', () => {
  test('shows the total, the matelas set aside and what is available', () => {
    renderTile()

    const tile = screen.getByTestId('tile-accounts')
    expect(tile).toHaveTextContent(/6\s045,50\s€/)
    expect(tile).toHaveTextContent(/dont 4\s200,00\s€ de matelas/)
    expect(tile).toHaveTextContent(/1\s845,50\s€ disponibles/)
  })

  test('lists each account as bank · currency · name, and marks the matelas', () => {
    const tile = dashboardFixture().tiles.accounts
    tile.accounts.push({ id: 'a3', name: 'Compte courant', bank: 'Crédit Agricole', balanceCents: 2000, currency: 'EUR', isCushion: false, rejectionsToSettleCount: 0 })
    renderTile(tile)

    expect(screen.getByText('Boursorama · EUR · Compte courant')).toBeInTheDocument()
    expect(screen.getByText('Crédit Agricole · EUR · Compte courant')).toBeInTheDocument()
    expect(screen.getByText('EUR · Livret A · matelas')).toBeInTheDocument()
  })

  test('links to the accounts list', () => {
    renderTile()

    expect(screen.getByRole('link', { name: 'Comptes' })).toHaveAttribute('href', '/accounts')
  })

  test('says so when there is no account', () => {
    renderTile({ totalCents: 0, cushionCents: 0, availableCents: 0, accounts: [] })

    expect(screen.getByText("Aucun compte pour l'instant.")).toBeInTheDocument()
  })

  test('shows « N à trier » and « N rejet » as pills that open their screen', () => {
    renderTile()

    expect(screen.getByRole('link', { name: '3 à trier' })).toHaveAttribute('href', '/finance/to-sort')
    expect(screen.getByRole('link', { name: '1 rejet' })).toHaveAttribute('href', '/accounts/a1/transactions?tab=incidents')
  })

  test('opens the incidents of the account that has the most rejections', () => {
    const tile = dashboardFixture().tiles.accounts
    tile.accounts[0].rejectionsToSettleCount = 1
    tile.accounts[1].rejectionsToSettleCount = 2

    renderTile(tile, { ...dashboardFixture().todo, rejectionsToSettleCount: 3 })

    expect(screen.getByRole('link', { name: '3 rejets' })).toHaveAttribute('href', '/accounts/a2/transactions?tab=incidents')
  })

  test('shows no pill when there is nothing to sort or settle', () => {
    renderTile(undefined, { ...dashboardFixture().todo, toSortCount: 0, rejectionsToSettleCount: 0 })

    expect(screen.queryByTestId('pill-to-sort')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pill-rejections')).not.toBeInTheDocument()
  })

  test('shows « On te doit N € » as a pill that opens Créances et dettes', () => {
    renderTile(undefined, { ...dashboardFixture().todo, owedToMeCents: 53500, iOweCents: 0 })

    const pill = screen.getByRole('link', { name: /On te doit 535,00\s€/ })
    expect(pill).toHaveAttribute('href', '/finance/peer-debts')
  })

  test('shows « Tu dois N € » when the owner owes more than they are owed', () => {
    renderTile(undefined, { ...dashboardFixture().todo, owedToMeCents: 20000, iOweCents: 28000 })

    const pill = screen.getByTestId('pill-owed')
    expect(pill).toHaveTextContent(/^Tu dois 80,00\s€$/)
    expect(pill).toHaveAttribute('href', '/finance/peer-debts')
  })

  test('shows no pill when nobody owes anything', () => {
    renderTile()

    expect(screen.queryByTestId('pill-owed')).not.toBeInTheDocument()
    expect(screen.getByTestId('tile-accounts')).not.toHaveTextContent(/On te doit|Tu dois/)
  })

  test('shows no pill when what is owed and what is due even out', () => {
    renderTile(undefined, { ...dashboardFixture().todo, owedToMeCents: 5000, iOweCents: 5000 })

    expect(screen.queryByTestId('pill-owed')).not.toBeInTheDocument()
  })
})

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

  test('shows the receivables example as a static pill, not a link and without « bientôt »', () => {
    renderTile()

    const pill = screen.getByTestId('pill-owed-example')
    expect(pill).toHaveTextContent('On te doit 535 €')
    expect(pill.closest('a')).toBeNull()
    expect(screen.getByTestId('tile-accounts')).not.toHaveTextContent(/bientôt/i)
  })
})

import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AccountsTile } from './AccountsTile'
import { dashboardFixture } from './dashboardFixture'

const renderTile = (tile = dashboardFixture().tiles.accounts) =>
  render(
    <MemoryRouter>
      <AccountsTile tile={tile} />
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
    tile.accounts.push({ id: 'a3', name: 'Compte courant', bank: 'Crédit Agricole', balanceCents: 2000, currency: 'EUR', isCushion: false })
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
})

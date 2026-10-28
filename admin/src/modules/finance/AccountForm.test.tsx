import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, Create, ResourceContextProvider, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { AccountForm } from './AccountForm'

describe('AccountForm', () => {
  test('carries only titles and labels, no explanatory text', async () => {
    render(
      <MemoryRouter>
        <AdminContext dataProvider={testDataProvider()}>
          <ResourceContextProvider value="accounts">
            <Create>
              <AccountForm withDefaults />
            </Create>
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Solde')).toBeInTheDocument()
    expect(screen.getByLabelText(/Solde actuel \(€\)/)).toBeInTheDocument()
    expect(screen.queryByText(/Code ISO 4217/)).toBeNull()
    expect(screen.queryByText(/Négatif pour un découvert/)).toBeNull()
    expect(screen.queryByText(/filet de sécurité/)).toBeNull()
  })
})

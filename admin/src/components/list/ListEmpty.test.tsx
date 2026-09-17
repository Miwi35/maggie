import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, ListContextProvider, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { ListControllerResult } from 'react-admin'
import { ListEmpty } from './ListEmpty'

const listContext = (filterValues: Record<string, unknown>) =>
  ({ filterValues, resource: 'accounts' }) as unknown as ListControllerResult

const renderEmpty = (filterValues: Record<string, unknown> = {}) =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <ResourceContextProvider value="accounts">
        <ListContextProvider value={listContext(filterValues)}>
          <ListEmpty
            title="Aucun compte pour l'instant"
            description="Ajoutez vos comptes courants et vos livrets."
            action="Ajouter un compte"
          />
        </ListContextProvider>
      </ResourceContextProvider>
    </AdminContext>,
  )

describe('ListEmpty', () => {
  test('says what the screen is for and offers the next step', () => {
    renderEmpty()

    expect(screen.getByText("Aucun compte pour l'instant")).toBeInTheDocument()
    expect(screen.getByText('Ajoutez vos comptes courants et vos livrets.')).toBeInTheDocument()
    expect(screen.getByText('Ajouter un compte')).toBeInTheDocument()
  })

  test('an empty filter result is a different problem, and says so', () => {
    renderEmpty({ category: 'food' })

    expect(screen.getByText('Aucun résultat pour ce filtre')).toBeInTheDocument()
    expect(screen.getByText(/Élargissez ou retirez le filtre/)).toBeInTheDocument()
    // Creating a record would not answer a filter that matches nothing.
    expect(screen.queryByText('Ajouter un compte')).not.toBeInTheDocument()
  })
})

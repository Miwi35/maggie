import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { CategoryForm } from './CategoryForm'

const CATEGORIES = [{ id: '/api/categories/1', name: 'Loisirs' }]

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({
      data: CATEGORIES,
      total: CATEGORIES.length,
    })) as unknown as DataProvider['getList'],
  getOne: (() => Promise.resolve({ data: CATEGORIES[0] })) as unknown as DataProvider['getOne'],
  getMany: (() => Promise.resolve({ data: CATEGORIES })) as unknown as DataProvider['getMany'],
})

const renderForm = () =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <ResourceContextProvider value="categories">
        <CategoryForm withDefaults />
      </ResourceContextProvider>
    </AdminContext>,
  )

const chooseObligation = async (label: string) => {
  const user = userEvent.setup()
  await user.click(await screen.findByLabelText(/Obligation/))
  await user.click(screen.getByRole('option', { name: label }))
}

describe('CategoryForm', () => {
  /**
   * A rente is money coming in; the API refuses the flag anywhere else, so a
   * box offered on a dépense would be a trap.
   */
  test('offers the rente box only once the category is a recette', async () => {
    renderForm()

    expect(await screen.findByLabelText(/Obligation/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Rente')).not.toBeInTheDocument()

    await chooseObligation('Recette')

    expect(await screen.findByLabelText('Rente')).toBeInTheDocument()
    expect(
      screen.getByText(/compteur d'indépendance compare à votre train de vie/),
    ).toBeInTheDocument()
  })

  test('takes the box away again when the category stops being a recette', async () => {
    const user = userEvent.setup()
    renderForm()

    await chooseObligation('Recette')
    await user.click(await screen.findByLabelText('Rente'))

    await chooseObligation('Non-obligatoire')

    expect(screen.queryByLabelText('Rente')).not.toBeInTheDocument()
  })
})

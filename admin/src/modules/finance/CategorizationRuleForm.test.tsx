import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { CategorizationRuleForm } from './CategorizationRuleForm'

const CATEGORIES = [{ id: '/api/categories/1', name: 'Alimentation' }]

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
  getOne: (() => Promise.resolve({ data: CATEGORIES[0] })) as unknown as DataProvider['getOne'],
  getMany: (() => Promise.resolve({ data: CATEGORIES })) as unknown as DataProvider['getMany'],
})

const renderForm = () =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <ResourceContextProvider value="categorization_rules">
        <CategorizationRuleForm withDefaults />
      </ResourceContextProvider>
    </AdminContext>,
  )

describe('CategorizationRuleForm', () => {
  test('walks through what the rule recognizes, where it files, and how it arbitrates', async () => {
    renderForm()

    expect(await screen.findByText('Ce que la règle reconnaît')).toBeInTheDocument()
    expect(screen.getByText('Où la classer')).toBeInTheDocument()
    expect(screen.getByText('Restreindre (facultatif)')).toBeInTheDocument()
    expect(screen.getByText('Arbitrage')).toBeInTheDocument()
  })

  test('spells out the two rules a person cannot guess', async () => {
    renderForm()

    // Amounts are stored unsigned, and a manual category wins over the engine.
    expect(
      await screen.findByText(/une dépense de 15,99 € vaut 15,99/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/ne sera jamais écrasée par cette règle/i),
    ).toBeInTheDocument()
    expect(screen.getByText(/Plus le nombre est grand/i)).toBeInTheDocument()
  })

  test('prompts for the missing pieces before anything is filled in', async () => {
    renderForm()

    expect(
      await screen.findByText(/Renseignez un texte à reconnaître et une catégorie/i),
    ).toBeInTheDocument()
  })

  test('restates the rule as a sentence once a pattern is typed', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(await screen.findByLabelText(/…ce texte/i), 'CARREFOUR')

    expect(await screen.findByText(/CARREFOUR/)).toBeInTheDocument()
  })
})

import { describe, test, expect } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { EnvelopeForm } from './EnvelopeForm'

const CATEGORIES = [
  { id: '/api/categories/1', name: 'Alimentation' },
  { id: '/api/categories/2', name: 'Voyages' },
]

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
  getOne: ((_resource: string, { id }: { id: string }) =>
    Promise.resolve({
      data: CATEGORIES.find((c) => c.id === id) ?? CATEGORIES[0],
    })) as unknown as DataProvider['getOne'],
  getMany: (() => Promise.resolve({ data: CATEGORIES })) as unknown as DataProvider['getMany'],
})

const renderForm = () =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <ResourceContextProvider value="envelopes">
        <EnvelopeForm withDefaults />
      </ResourceContextProvider>
    </AdminContext>,
  )

describe('EnvelopeForm', () => {
  test('groups the fields under explained sections', async () => {
    renderForm()

    expect(await screen.findByText('Ce que vous budgétez')).toBeInTheDocument()
    expect(screen.getByText('Période couverte')).toBeInTheDocument()
    expect(
      screen.getByText(/c'est elle qui détermine quand le compteur repart de zéro/i),
    ).toBeInTheDocument()
  })

  test('explains what the period fields are for', async () => {
    renderForm()

    expect(await screen.findByText(/L'année à laquelle cette enveloppe appartient/i)).toBeInTheDocument()
    expect(screen.getByText(/Le mois que ce montant couvre/i)).toBeInTheDocument()
    expect(screen.getByText(/se renouvelle chaque mois/i)).toBeInTheDocument()
  })

  test('defaults the period to the current month', async () => {
    renderForm()

    const now = new Date()
    expect(await screen.findByDisplayValue(String(now.getFullYear()))).toBeInTheDocument()
  })

  test('prompts for the missing fields before anything is filled in', async () => {
    renderForm()

    expect(
      await screen.findByText(/Choisissez une catégorie, un montant et une période/i),
    ).toBeInTheDocument()
  })

  test('hides the month field for an annual envelope', async () => {
    const user = userEvent.setup()
    renderForm()

    expect(await screen.findByLabelText(/Mois budgété/i)).toBeInTheDocument()

    await user.click(screen.getByLabelText(/Rythme du budget/i))
    await user.click(await screen.findByRole('option', { name: 'Annuel' }))

    await waitFor(() => {
      expect(screen.queryByLabelText(/Mois budgété/i)).not.toBeInTheDocument()
    })
  })
})

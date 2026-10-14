import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AdminContext, Edit, ResourceContextProvider, testDataProvider } from 'react-admin'
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

const envelope = {
  id: '/api/envelopes/e1',
  category: '/api/categories/1',
  mode: 'monthly',
  amountCents: 30000,
  currency: 'EUR',
  year: 2026,
  month: 10,
  alertThresholdPercent: null,
}

const renderEdit = () => {
  const update = vi.fn().mockResolvedValue({ data: envelope })
  render(
    <MemoryRouter initialEntries={['/envelopes/' + encodeURIComponent(envelope.id)]}>
      <AdminContext
        dataProvider={testDataProvider({
          ...dataProvider,
          getOne: vi.fn().mockResolvedValue({ data: envelope }),
          update,
        })}
      >
        <ResourceContextProvider value="envelopes">
          <Routes>
            <Route
              path="/envelopes/:id"
              element={
                <Edit mutationMode="pessimistic">
                  <EnvelopeForm />
                </Edit>
              }
            />
            <Route path="/envelopes" element={null} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
  return update
}

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

    await user.click(screen.getByLabelText(/Type d'enveloppe/i))
    await user.click(await screen.findByRole('option', { name: 'Annuel' }))

    await waitFor(() => {
      expect(screen.queryByLabelText(/Mois budgété/i)).not.toBeInTheDocument()
    })
  })

  test('a one-off envelope asks for a name and no category nor period', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.click(await screen.findByLabelText(/Type d'enveloppe/i))
    await user.click(await screen.findByRole('option', { name: 'Ponctuel' }))

    expect(await screen.findByLabelText(/Nom de l'événement/i)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Catégorie/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/Mois budgété/i)).not.toBeInTheDocument()
    expect(screen.queryByText('Période couverte')).not.toBeInTheDocument()
  })

  test('restates a one-off envelope once named', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.click(await screen.findByLabelText(/Type d'enveloppe/i))
    await user.click(await screen.findByRole('option', { name: 'Ponctuel' }))
    expect(await screen.findByText(/Donnez un nom et un montant/i)).toBeInTheDocument()

    await user.type(screen.getByLabelText(/Nom de l'événement/i), 'Hellfest 2026')
    await user.type(screen.getByLabelText(/Montant de l'enveloppe/i), '400')

    expect(await screen.findByText(/hors budget mensuel/i)).toBeInTheDocument()
    expect(screen.getByText('Hellfest 2026')).toBeInTheDocument()
  })

  test('refuses to save a one-off envelope without a name', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.click(await screen.findByLabelText(/Type d'enveloppe/i))
    await user.click(await screen.findByRole('option', { name: 'Ponctuel' }))
    await user.type(await screen.findByLabelText(/Montant de l'enveloppe/i), '400')
    await user.click(screen.getByRole('button', { name: /Enregistrer|Save|ra\.action\.save/ }))

    expect((await screen.findAllByText(/requis|required/i)).length).toBeGreaterThan(0)
  })

  test('offers the alert threshold, 80 % when left empty', async () => {
    renderForm()

    const threshold = await screen.findByLabelText(/Seuil d'alerte/i)
    expect(threshold).toHaveValue(null)
    expect(screen.getByText(/Vide : 80 %/)).toBeInTheDocument()
    expect(screen.getByText(/une seconde fois à 100 %/)).toBeInTheDocument()
  })

  test('saves the threshold set on an envelope', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    const threshold = await screen.findByLabelText(/Seuil d'alerte/i)
    await waitFor(() => expect(screen.getByDisplayValue('300')).toBeInTheDocument())
    await user.type(threshold, '90')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.alertThresholdPercent).toBe(90)
  })

  test('refuses a threshold at or past the limit', async () => {
    const user = userEvent.setup()
    const update = renderEdit()

    const threshold = await screen.findByLabelText(/Seuil d'alerte/i)
    await waitFor(() => expect(screen.getByDisplayValue('300')).toBeInTheDocument())
    await user.type(threshold, '100')
    await user.click(screen.getByRole('button', { name: /enregistrer|save/i }))

    await waitFor(() => expect(threshold).toBeInvalid())
    expect(update).not.toHaveBeenCalled()
  })
})

import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import {
  AdminContext,
  Edit,
  RecordContextProvider,
  ResourceContextProvider,
  testDataProvider,
} from 'react-admin'
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

const WINTER = { fromMonth: 11, toMonth: 2, amountCents: 4500 }

/** The edit form of Cinéma, 20 € a month in October 2026. */
const renderSeasonEdit = (seasonalAmounts: Array<typeof WINTER>) =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <ResourceContextProvider value="envelopes">
        <RecordContextProvider
          value={{
            id: '/api/envelopes/1',
            category: '/api/categories/1',
            mode: 'monthly',
            amountCents: 2000,
            currency: 'EUR',
            year: 2026,
            month: 10,
            seasonalAmounts,
          }}
        >
          <EnvelopeForm />
        </RecordContextProvider>
      </ResourceContextProvider>
    </AdminContext>,
  )

describe('EnvelopeForm', () => {
  test('groups the fields under titled sections, without explanatory text', async () => {
    renderForm()

    expect(await screen.findByText('Ce que vous budgétez')).toBeInTheDocument()
    expect(screen.getByText('Période couverte')).toBeInTheDocument()
    expect(screen.queryByText(/compteur repart de zéro/i)).toBeNull()
    expect(screen.queryByText(/L'année à laquelle cette enveloppe appartient/i)).toBeNull()
    expect(screen.queryByText(/se renouvelle chaque mois/i)).toBeNull()
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
    expect(threshold).toHaveAttribute('placeholder', '80')
    expect(screen.queryByText(/Vide : 80 %/)).toBeNull()
    expect(screen.queryByText(/une seconde fois à 100 %/)).toBeNull()
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

  test('shows the seasons of a monthly envelope', async () => {
    renderSeasonEdit([WINTER])

    expect(await screen.findByText('Saisons')).toBeInTheDocument()
    expect(screen.getByDisplayValue('45')).toBeInTheDocument()
    expect(screen.getByText('Novembre')).toBeInTheDocument()
    expect(screen.getByText('Février')).toBeInTheDocument()
  })

  test('adds a season and removes it', async () => {
    const user = userEvent.setup()
    renderSeasonEdit([])

    await user.click(await screen.findByRole('button', { name: 'Ajouter une saison' }))
    expect(await screen.findByLabelText(/^De(?![a-z])/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^À(?![a-z])/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Montant \(€\)/)).toBeInTheDocument()

    // React-admin shows a row's remove button on hover only.
    await user.click(screen.getByLabelText('Retirer la saison'))
    await waitFor(() => {
      expect(screen.queryByLabelText(/^De(?![a-z])/)).not.toBeInTheDocument()
    })
  })

  test('refuses two seasons sharing a month before sending them', async () => {
    const user = userEvent.setup()
    renderSeasonEdit([WINTER, { fromMonth: 2, toMonth: 3, amountCents: 3000 }])

    // An untouched edit form cannot be saved: change the base amount first.
    await user.clear(await screen.findByLabelText(/Montant de l'enveloppe/i))
    await user.type(screen.getByLabelText(/Montant de l'enveloppe/i), '25')
    await user.click(await screen.findByRole('button', { name: /Enregistrer|Save|ra\.action\.save/ }))

    expect(await screen.findByText(/Deux saisons couvrent février/i)).toBeInTheDocument()
  })

  test('an annual envelope has no seasons', async () => {
    const user = userEvent.setup()
    renderForm()

    expect(await screen.findByText('Saisons')).toBeInTheDocument()
    await user.click(screen.getByLabelText(/Type d'enveloppe/i))
    await user.click(await screen.findByRole('option', { name: 'Annuel' }))

    await waitFor(() => {
      expect(screen.queryByText('Saisons')).not.toBeInTheDocument()
    })
  })
})

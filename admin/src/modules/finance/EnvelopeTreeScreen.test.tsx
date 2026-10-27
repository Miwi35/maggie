import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { EnvelopeTreeScreen } from './EnvelopeTreeScreen'
import { CATEGORIES, STATUS, STORED } from './envelopeTreeFixtures'
import type { BudgetStatus } from './useBudgetStatus'

const refresh = vi.fn()
const create = vi.fn()
const update = vi.fn()
const deleteOne = vi.fn()

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
  getMany: (() => Promise.resolve({ data: CATEGORIES })) as unknown as DataProvider['getMany'],
  getOne: ((_: string, { id }: { id: string }) =>
    Promise.resolve({ data: STORED[id] ?? CATEGORIES.find((c) => c.id === id) })) as unknown as DataProvider['getOne'],
  create: create as unknown as DataProvider['create'],
  update: update as unknown as DataProvider['update'],
  delete: deleteOne as unknown as DataProvider['delete'],
})

const renderScreen = (status: BudgetStatus | null = STATUS, view: 'month' | 'outside' = 'month') =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <ResourceContextProvider value="envelopes">
        <EnvelopeTreeScreen year={2026} month={10} view={view} status={status} loading={false} refresh={refresh} />
      </ResourceContextProvider>
    </AdminContext>,
  )

const node = async (name: string) =>
  (await screen.findAllByTestId('envelope-node')).find((n) => n.getAttribute('data-category') === name) as HTMLElement

const select = async (name: string) => {
  await userEvent.click(await node(name))
}

const editor = () => screen.getByTestId('envelope-editor')
const form = async () => within(await screen.findByTestId('envelope-form'))

describe('EnvelopeTreeScreen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    create.mockImplementation((_: string, { data }: { data: Record<string, unknown> }) =>
      Promise.resolve({ data: { id: '/api/envelopes/new', ...data } }),
    )
    update.mockImplementation((_: string, { data }: { data: Record<string, unknown> }) => Promise.resolve({ data }))
    deleteOne.mockImplementation((_: string, { id }: { id: string }) => Promise.resolve({ data: { id } }))
  })

  test('draws the categories as a tree with their envelope of the period, or none', async () => {
    renderScreen()

    await node('Obsydian')
    const nodes = screen.getAllByTestId('envelope-node')
    expect(nodes.map((n) => [n.dataset.category, n.dataset.depth, n.dataset.hasEnvelope])).toEqual([
      ['Courses', '1', 'true'],
      ['Loisirs', '1', 'true'],
      ['Bars', '2', 'true'],
      ['Obsydian', '3', 'false'],
      ['Cinéma', '2', 'true'],
      ['Voyages', '1', 'false'],
    ])
    // The mother's consumption is the cumulative one the API gives.
    expect(within(nodes[1]).getByTestId('envelope-node-amount')).toHaveTextContent(/40,00\s€\s\/\s300,00\s€/)
    expect(within(nodes[2]).getByTestId('envelope-node-amount')).toHaveTextContent(/40,00\s€\s\/\s100,00\s€/)
    expect(nodes[3]).toHaveTextContent('Sans enveloppe')
    expect(within(nodes[3]).queryByTestId('envelope-node-amount')).toBeNull()
  })

  test('says overspent with a word and an icon, not only a colour', async () => {
    renderScreen()

    const courses = await node('Courses')
    const state = within(courses).getByTestId('envelope-node-state')
    expect(state).toHaveAttribute('data-state', 'overspent')
    expect(state).toHaveTextContent('Dépassement')
    expect(state.parentElement?.querySelector('svg')).not.toBeNull()
    expect(within(await node('Bars')).queryByTestId('envelope-node-state')).toBeNull()
  })

  test('Mois: the tree only, with no title, no caption and nothing of the envelopes outside the month', async () => {
    renderScreen()
    await node('Obsydian')

    expect(screen.queryByTestId('envelope-outside')).toBeNull()
    expect(screen.queryByTestId('envelope-outside-node')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Nouvelle ponctuelle' })).toBeNull()
    expect(screen.queryByText('Hors budget mensuel')).toBeNull()
    expect(screen.queryByText('Enveloppes')).toBeNull()
    expect(screen.queryByText(/catégorie par catégorie/)).toBeNull()
  })

  test('Mois: no pane, and no « Choisissez une catégorie », until a node is selected', async () => {
    renderScreen()
    await node('Obsydian')

    expect(screen.queryByTestId('envelope-editor')).toBeNull()
    expect(screen.queryByText('Choisissez une catégorie')).toBeNull()

    await select('Cinéma')

    expect(screen.getByTestId('envelope-editor')).toBeInTheDocument()
  })

  test('Hors budget: the annual and one-off envelopes, and no category tree', async () => {
    renderScreen(STATUS, 'outside')

    const outside = await screen.findByTestId('envelope-outside')
    const entries = within(outside).getAllByTestId('envelope-outside-node')
    expect(entries.map((e) => [e.dataset.category, e.dataset.kind])).toEqual([
      ['Voyages', 'annual'],
      ['Hellfest 2026', 'one_off'],
    ])
    expect(entries[1]).toHaveTextContent(/89,00\s€\s\/\s400,00\s€/)
    expect(within(outside).getByRole('button', { name: 'Nouvelle ponctuelle' })).toBeInTheDocument()
    expect(screen.queryByTestId('envelope-node')).toBeNull()
    expect(screen.queryByText('Hors budget mensuel')).toBeNull()
    expect(screen.queryByText(/Les annuelles de/)).toBeNull()
    expect(screen.queryByText('Choisissez une catégorie')).toBeNull()
  })

  test('says so, in one line, when the month has no envelope at all', async () => {
    renderScreen({ ...STATUS, budgets: [], outsideMonthlyBudget: { annual: [], oneOff: [] } })

    expect(await screen.findByText("Aucune enveloppe pour l'instant")).toBeInTheDocument()
    expect(screen.queryByText(/Une enveloppe fixe ce que vous vous autorisez/)).toBeNull()
  })

  test('highlights the selected node', async () => {
    renderScreen()

    const obsydian = await node('Obsydian')
    expect(obsydian).toHaveAttribute('aria-selected', 'false')
    await userEvent.click(obsydian)

    expect(obsydian).toHaveAttribute('aria-selected', 'true')
    expect(await node('Bars')).toHaveAttribute('aria-selected', 'false')
  })

  test('selects a node from the keyboard', async () => {
    renderScreen()

    const bars = await node('Bars')
    bars.focus()
    await userEvent.keyboard('{Enter}')

    expect(bars).toHaveAttribute('aria-selected', 'true')
  })

  test('the pane explains nothing: no help text under the fields, none above the forms', async () => {
    renderScreen()
    await select('Obsydian')
    await form()

    expect(editor()).not.toHaveTextContent(/plafonne les dépenses/)
    expect(screen.queryByText(/Ce que vous vous autorisez à dépenser/)).toBeNull()
  })

  test('creates the monthly envelope of a category without one', async () => {
    renderScreen()
    await select('Obsydian')

    expect(await screen.findByText('Créer une enveloppe')).toBeInTheDocument()
    expect(editor()).toHaveTextContent(/Sans enveloppe en Octobre 2026/)
    const f = await form()
    await userEvent.type(f.getByLabelText(/Montant de l'enveloppe/), '100')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toBe('envelopes')
    expect(create.mock.calls[0][1].data).toMatchObject({
      category: '/api/categories/obsydian',
      mode: 'monthly',
      amountCents: 10000,
      year: 2026,
      month: 10,
    })
    await waitFor(() => expect(refresh).toHaveBeenCalled())
  })

  test('creates an annual envelope with no month nor seasons, and a threshold', async () => {
    renderScreen()
    await select('Obsydian')

    const f = await form()
    await userEvent.click(f.getByLabelText(/Type d'enveloppe/))
    await userEvent.click(await screen.findByRole('option', { name: 'Annuelle' }))
    await waitFor(() => expect(f.queryByText('Saisons')).toBeNull())
    await userEvent.type(f.getByLabelText(/Montant de l'enveloppe/), '500')
    await userEvent.type(f.getByLabelText(/Seuil d'alerte/), '70')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({
      mode: 'annual',
      amountCents: 50000,
      alertThresholdPercent: 70,
      year: 2026,
      month: null,
    })
    // The saved annual envelope is now listed apart: the editor follows it.
    await waitFor(() => expect(refresh).toHaveBeenCalled())
  })

  test('refuses a threshold out of range before sending anything', async () => {
    renderScreen()
    await select('Obsydian')

    const f = await form()
    await userEvent.type(f.getByLabelText(/Montant de l'enveloppe/), '100')
    await userEvent.type(f.getByLabelText(/Seuil d'alerte/), '100')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(f.getByLabelText(/Seuil d'alerte/)).toBeInvalid())
    expect(create).not.toHaveBeenCalled()
  })

  test('shows what the API refuses and keeps the form open to correct it', async () => {
    create.mockRejectedValue(new Error('amountCents: This value should be positive.'))
    renderScreen()
    await select('Obsydian')

    const f = await form()
    await userEvent.type(f.getByLabelText(/Montant de l'enveloppe/), '100')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('amountCents: This value should be positive.')
    expect(f.getByLabelText(/Montant de l'enveloppe/)).toHaveValue(100)
    expect(f.getByRole('button', { name: 'Enregistrer' })).toBeEnabled()
    expect(refresh).not.toHaveBeenCalled()
  })

  test('saves the alert threshold set on an envelope', async () => {
    renderScreen()
    await select('Loisirs')

    const f = await form()
    await waitFor(() => expect(f.getByDisplayValue('300')).toBeInTheDocument())
    await userEvent.type(f.getByLabelText(/Seuil d'alerte/), '90')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data).toMatchObject({
      category: '/api/categories/loisirs',
      mode: 'monthly',
      alertThresholdPercent: 90,
      amountCents: 30000,
    })
    expect(update.mock.calls[0][1].id).toBe('/api/envelopes/e-loisirs')
  })

  test('sets a season on a monthly envelope', async () => {
    renderScreen()
    await select('Cinéma')

    const f = await form()
    await waitFor(() => expect(f.getByDisplayValue('50')).toBeInTheDocument())
    await userEvent.click(await f.findByRole('button', { name: 'Ajouter une saison' }))
    await userEvent.click(await f.findByLabelText(/^De(?![a-z])/))
    await userEvent.click(await screen.findByRole('option', { name: 'Novembre' }))
    await userEvent.click(f.getByLabelText(/^À(?![a-z])/))
    await userEvent.click(await screen.findByRole('option', { name: 'Février' }))
    await userEvent.type(f.getByLabelText(/Montant \(€\)/), '45')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.seasonalAmounts).toEqual([{ fromMonth: 11, toMonth: 2, amountCents: 4500 }])
    expect(update.mock.calls[0][1].data.amountCents).toBe(5000)
  })

  test('refuses two seasons sharing a month before sending them', async () => {
    renderScreen()
    await select('Cinéma')

    const f = await form()
    await waitFor(() => expect(f.getByDisplayValue('50')).toBeInTheDocument())
    for (const [from, to] of [
      ['Novembre', 'Février'],
      ['Février', 'Mars'],
    ]) {
      await userEvent.click(await f.findByRole('button', { name: 'Ajouter une saison' }))
      const froms = await f.findAllByLabelText(/^De(?![a-z])/)
      await userEvent.click(froms[froms.length - 1])
      await userEvent.click(await screen.findByRole('option', { name: from }))
      const tos = f.getAllByLabelText(/^À(?![a-z])/)
      await userEvent.click(tos[tos.length - 1])
      await userEvent.click(await screen.findByRole('option', { name: to }))
      const amounts = f.getAllByLabelText(/Montant \(€\)/)
      await userEvent.type(amounts[amounts.length - 1], '10')
    }
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    expect(await f.findByText(/Deux saisons couvrent février/i)).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()
  })

  test('deletes an envelope after confirmation', async () => {
    renderScreen()
    await select('Cinéma')

    const f = await form()
    await userEvent.click(f.getByRole('button', { name: "Supprimer l'enveloppe" }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent(/Supprimer l'enveloppe « Loisirs › Cinéma » \?/)
    expect(deleteOne).not.toHaveBeenCalled()
    await userEvent.click(within(dialog).getByRole('button', { name: /confirm|confirmer/i }))

    await waitFor(() => expect(deleteOne).toHaveBeenCalled())
    expect(deleteOne.mock.calls[0][1].id).toBe('/api/envelopes/e-cinema')
    await waitFor(() => expect(refresh).toHaveBeenCalled())
  })

  test('keeps the envelope when the confirmation is dismissed', async () => {
    renderScreen()
    await select('Cinéma')

    await userEvent.click((await form()).getByRole('button', { name: "Supprimer l'enveloppe" }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: /cancel|annuler/i }))

    expect(deleteOne).not.toHaveBeenCalled()
  })

  test('shows the ruler of a mother with daughters, and the free part', async () => {
    renderScreen()
    await select('Loisirs')

    const ruler = await screen.findByTestId('envelope-ruler')
    expect(within(ruler).getByRole('heading', { name: 'Répartir' })).toBeInTheDocument()
    expect(within(ruler).getAllByRole('slider')).toHaveLength(2)
    expect(within(ruler).getByTestId('envelope-split')).toHaveTextContent(/Libre 150,00\s€/)
    expect(screen.getByTestId('envelope-allocation')).toHaveTextContent(/150,00\s€ répartis aux sous-catégories · 150,00\s€ libres/)
  })

  test('shows no ruler for an envelope without daughters', async () => {
    renderScreen()
    await select('Cinéma')

    await form()
    expect(screen.queryByTestId('envelope-ruler')).toBeNull()
    expect(screen.queryByTestId('envelope-allocation')).toBeNull()
  })

  test('edits an annual envelope listed apart, and offers no seasons', async () => {
    renderScreen(STATUS, 'outside')
    const entry = (await screen.findAllByTestId('envelope-outside-node')).find((e) => e.dataset.category === 'Voyages')!
    await userEvent.click(entry)

    const f = await form()
    await waitFor(() => expect(f.getByDisplayValue('1200')).toBeInTheDocument())
    expect(entry).toHaveAttribute('aria-selected', 'true')
    expect(f.getByLabelText(/Type d'enveloppe/)).toHaveTextContent('Annuelle')
    expect(f.queryByText('Saisons')).toBeNull()
  })

  test('moves a monthly envelope to annual: no month, no seasons', async () => {
    renderScreen()
    await select('Cinéma')

    const f = await form()
    await waitFor(() => expect(f.getByDisplayValue('50')).toBeInTheDocument())
    await userEvent.click(f.getByLabelText(/Type d'enveloppe/))
    await userEvent.click(await screen.findByRole('option', { name: 'Annuelle' }))
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data).toMatchObject({ mode: 'annual', month: null, seasonalAmounts: [], year: 2026 })
  })

  test('creates a one-off envelope: a name, an amount, no category nor threshold', async () => {
    renderScreen(STATUS, 'outside')
    await userEvent.click(await screen.findByRole('button', { name: 'Nouvelle ponctuelle' }))

    const f = await form()
    expect(editor()).not.toHaveTextContent(/Un événement/)
    expect(screen.queryByText(/Hellfest 2026, Vacances en Bretagne/)).toBeNull()
    expect(f.queryByLabelText(/Seuil d'alerte/)).toBeNull()
    expect(f.queryByLabelText(/Type d'enveloppe/)).toBeNull()
    await userEvent.type(f.getByLabelText(/Montant de l'enveloppe/), '400')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))
    expect((await f.findAllByText(/requis|required/i)).length).toBeGreaterThan(0)
    expect(create).not.toHaveBeenCalled()

    await userEvent.type(f.getByLabelText(/Nom de l'événement/), 'Hellfest 2027')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({
      mode: 'one_off',
      name: 'Hellfest 2027',
      amountCents: 40000,
      category: null,
      year: null,
      month: null,
    })
  })

  test('edits a one-off envelope by its name', async () => {
    renderScreen(STATUS, 'outside')
    const entry = (await screen.findAllByTestId('envelope-outside-node')).find((e) => e.dataset.kind === 'one_off')!
    await userEvent.click(entry)

    const f = await form()
    await waitFor(() => expect(f.getByLabelText(/Nom de l'événement/)).toHaveValue('Hellfest 2026'))
    await userEvent.clear(f.getByLabelText(/Nom de l'événement/))
    await userEvent.type(f.getByLabelText(/Nom de l'événement/), 'Hellfest 2027')
    await userEvent.click(f.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data).toMatchObject({ mode: 'one_off', name: 'Hellfest 2027', category: null })
  })

  test('says so when the envelope selected is gone from the period', async () => {
    const { rerender } = renderScreen(STATUS, 'outside')
    const entry = (await screen.findAllByTestId('envelope-outside-node')).find((e) => e.dataset.category === 'Voyages')!
    await userEvent.click(entry)
    await form()

    rerender(
      <AdminContext dataProvider={dataProvider}>
        <ResourceContextProvider value="envelopes">
          <EnvelopeTreeScreen
            year={2027}
            month={10}
            view="outside"
            status={{ ...STATUS, outsideMonthlyBudget: { annual: [], oneOff: [] } }}
            loading={false}
            refresh={refresh}
          />
        </ResourceContextProvider>
      </AdminContext>,
    )

    expect(await screen.findByText('Enveloppe introuvable')).toBeInTheDocument()
  })
})

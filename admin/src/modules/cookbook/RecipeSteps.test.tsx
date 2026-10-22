import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DndContextProps } from '@dnd-kit/core'
import { AdminContext, HttpError, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeEdit } from './RecipeEdit'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

// A real drag needs a layout engine jsdom does not have: the tests finish it the way dnd-kit does,
// by calling the `onDragEnd` the form gave the context. What a real drag does is the Playwright journey's.
const dnd: { props?: DndContextProps } = {}
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: (props: DndContextProps) => {
      dnd.props = props
      return <actual.DndContext {...props} />
    },
  }
})

const PASTA = { alim_code: '9810', alim_name_fr: 'Pâtes', alim_group_name_fr: null }

const line = (id: string, name: string) => ({
  id: `01L${id}`,
  ingredient: { '@id': `/api/ingredients/01I${id}`, id: `01I${id}`, name, ciqualAlimCode: '9810' },
  ingredientName: name,
  ciqualAlimCode: '9810',
  quantity: 200,
  unit: 'g',
})

const step = (position: number, text: string, extra: Record<string, unknown> = {}) => ({
  id: `s${position}`,
  position,
  text,
  durationMinutes: null,
  background: false,
  ingredients: [],
  utensils: [],
  waitsFor: [],
  ...extra,
})

const recipe = {
  id: '01R',
  name: 'Pâtes aux légumes',
  servings: 4,
  tags: [],
  notes: null,
  ingredients: [line('1', 'Pâtes'), line('2', 'Courgettes')],
  utensils: [{ id: '01U1', name: 'Casserole' }],
  steps: [] as Record<string, unknown>[],
}

const example = [
  step(1, "Mettre l'eau à bouillir", { durationMinutes: 10, background: true }),
  step(2, 'Couper les légumes'),
  step(3, 'Cuire les pâtes', { durationMinutes: 12, background: true, waitsFor: [1] }),
  step(4, 'Faire revenir les légumes', { durationMinutes: 8 }),
  step(5, 'Égoutter, mélanger', { waitsFor: [3, 4] }),
]

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const renderEdit = (update: ReturnType<typeof vi.fn>, record = recipe) => {
  const catalogue = [
    { id: '/api/utensils/01U1', name: 'Casserole' },
    { id: '/api/utensils/01U2', name: 'Poêle' },
  ]
  render(
    <MemoryRouter initialEntries={['/recipes/01R']}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: vi.fn().mockResolvedValue({ data: record }),
          getList: vi.fn().mockResolvedValue({ data: catalogue, total: 2 }),
          update,
        })}
        i18nProvider={i18nProvider}
      >
        <Routes>
          <Route
            path="/recipes/:id"
            element={
              <ResourceContextProvider value="recipes">
                <RecipeEdit />
              </ResourceContextProvider>
            }
          />
        </Routes>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )
}

const group = (position: number) => screen.getByRole('group', { name: `Étape ${position}` })

const pick = async (user: ReturnType<typeof userEvent.setup>, root: HTMLElement, field: string, option: string) => {
  await user.click(within(root).getByRole('combobox', { name: field }))
  await user.click(await screen.findByRole('option', { name: option }))
}

const save = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('button', { name: 'Enregistrer' }))

const sentSteps = (update: ReturnType<typeof vi.fn>) => update.mock.calls[0][1].data.steps as Record<string, unknown>[]

describe('Recipe steps in the form', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve(PASTA) } as Response)))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // ~13 s alone, 53 s then 60+ s on a loaded CI runner (MUI comboboxes, ~40 clicks): the shared 60 s was too tight.
  test('the reference example is entered in the form and sent as written', { timeout: 180_000 }, async () => {
    const user = userEvent.setup({ delay: null })
    const update = vi.fn().mockResolvedValue({ data: recipe })
    renderEdit(update)
    await screen.findByLabelText(/^Nom/)

    // The equipment: one from the catalogue, one new.
    await user.type(screen.getByRole('combobox', { name: 'Matériel' }), 'Thermomix')
    await user.click(await screen.findByRole('option', { name: 'Ajouter « Thermomix »' }))

    for (let i = 0; i < 5; i++) await user.click(screen.getByRole('button', { name: 'Ajouter une étape' }))
    const texts = ["Mettre l'eau à bouillir", 'Couper les légumes', 'Cuire les pâtes', 'Faire revenir les légumes', 'Égoutter, mélanger']
    const durations = ['10', '', '12', '8', '']
    for (let i = 0; i < 5; i++) {
      await user.click(within(group(i + 1)).getByLabelText('Texte'))
      await user.paste(texts[i])
      if (durations[i]) {
        await user.click(within(group(i + 1)).getByLabelText('Durée (min)'))
        await user.paste(durations[i])
      }
    }
    await user.click(within(group(1)).getByRole('checkbox', { name: 'En fond' }))
    await user.click(within(group(3)).getByRole('checkbox', { name: 'En fond' }))
    await pick(user, group(3), 'Attend', 'Étape 1')
    await pick(user, group(5), 'Attend', 'Étape 3')
    await pick(user, group(5), 'Attend', 'Étape 4')
    await pick(user, group(2), 'Ingrédients', 'Courgettes')
    await pick(user, group(3), 'Ingrédients', 'Pâtes')
    await pick(user, group(1), 'Ustensiles', 'Casserole')
    await pick(user, group(4), 'Ustensiles', 'Thermomix')
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.utensils).toEqual(['Casserole', 'Thermomix'])
    expect(sentSteps(update)).toEqual([
      { text: "Mettre l'eau à bouillir", durationMinutes: 10, background: true, ingredientIds: [], utensils: ['Casserole'], waitsFor: [] },
      { text: 'Couper les légumes', durationMinutes: null, background: false, ingredientIds: ['Courgettes'], utensils: [], waitsFor: [] },
      { text: 'Cuire les pâtes', durationMinutes: 12, background: true, ingredientIds: ['Pâtes'], utensils: [], waitsFor: [1] },
      { text: 'Faire revenir les légumes', durationMinutes: 8, background: false, ingredientIds: [], utensils: ['Thermomix'], waitsFor: [] },
      { text: 'Égoutter, mélanger', durationMinutes: null, background: false, ingredientIds: [], utensils: [], waitsFor: [3, 4] },
    ])
  })

  test('a utensil of the catalogue is picked, a new one is created by typing it', async () => {
    const user = userEvent.setup({ delay: null })
    const update = vi.fn().mockResolvedValue({ data: recipe })
    renderEdit(update)
    await screen.findByLabelText(/^Nom/)

    await pick(user, document.body, 'Matériel', 'Poêle')
    await user.type(screen.getByRole('combobox', { name: 'Matériel' }), 'Mandoline')
    await user.click(await screen.findByRole('option', { name: 'Ajouter « Mandoline »' }))
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(update.mock.calls[0][1].data.utensils).toEqual(['Casserole', 'Poêle', 'Mandoline'])
  })

  test('a name already in the catalogue is not offered for creation', async () => {
    const user = userEvent.setup({ delay: null })
    renderEdit(vi.fn())
    await screen.findByLabelText(/^Nom/)

    await user.type(screen.getByRole('combobox', { name: 'Matériel' }), 'poêle')

    expect(await screen.findByRole('option', { name: 'Poêle' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Ajouter/ })).not.toBeInTheDocument()
  })

  test('the steps of the recipe are shown in order with their dependencies', async () => {
    renderEdit(vi.fn(), { ...recipe, steps: example })

    expect(await within(await screen.findByRole('group', { name: 'Étape 5' })).findByText('Étape 3')).toBeInTheDocument()
    expect(within(group(5)).getByText('Étape 4')).toBeInTheDocument()
    expect(within(group(3)).getByDisplayValue('Cuire les pâtes')).toBeInTheDocument()
    expect(within(group(3)).getByRole('checkbox', { name: 'En fond' })).toBeChecked()
  })

  test('« en fond » is only available with a duration', async () => {
    const user = userEvent.setup({ delay: null })
    renderEdit(vi.fn(), { ...recipe, steps: [step(1, 'Couper')] })

    const background = within(await screen.findByRole('group', { name: 'Étape 1' })).getByRole('checkbox', { name: 'En fond' })
    expect(background).toBeDisabled()
    await user.type(within(group(1)).getByLabelText('Durée (min)'), '5')
    expect(background).toBeEnabled()
    await user.click(background)
    await user.clear(within(group(1)).getByLabelText('Durée (min)'))
    expect(background).toBeDisabled()
    expect(background).not.toBeChecked()
  })

  test('moving the first step after the third removes the dependency that became impossible before sending', async () => {
    const user = userEvent.setup({ delay: null })
    const update = vi.fn().mockResolvedValue({ data: recipe })
    renderEdit(update, { ...recipe, steps: example })
    await screen.findByRole('group', { name: 'Étape 5' })

    act(() => dnd.props?.onDragEnd?.({ active: { id: 's1' }, over: { id: 's3' } } as never))

    expect(within(group(3)).getByDisplayValue("Mettre l'eau à bouillir")).toBeInTheDocument()
    expect(within(group(2)).queryByText('Étape 1')).not.toBeInTheDocument()
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(sentSteps(update).map((s) => [s.text, s.waitsFor])).toEqual([
      ['Couper les légumes', []],
      ['Cuire les pâtes', []],
      ["Mettre l'eau à bouillir", []],
      ['Faire revenir les légumes', []],
      ['Égoutter, mélanger', [2, 4]],
    ])
  })

  test('removing a step renumbers the others and drops what waited for it', async () => {
    const user = userEvent.setup({ delay: null })
    const update = vi.fn().mockResolvedValue({ data: recipe })
    renderEdit(update, { ...recipe, steps: example })
    await screen.findByRole('group', { name: 'Étape 5' })

    await user.click(screen.getByRole('button', { name: "Supprimer l'étape 1" }))
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalled())
    expect(sentSteps(update).map((s) => [s.text, s.waitsFor])).toEqual([
      ['Couper les légumes', []],
      ['Cuire les pâtes', []],
      ['Faire revenir les légumes', []],
      ['Égoutter, mélanger', [2, 3]],
    ])
  })

  test('a 400 from the API is shown on the step it is about', async () => {
    const user = userEvent.setup({ delay: null })
    const message = 'Step 2: a step runs in the background only with a duration.'
    const update = vi.fn().mockRejectedValue(new HttpError(message, 400, { 'hydra:description': message }))
    renderEdit(update, { ...recipe, steps: [step(1, 'Couper'), step(2, 'Cuire')] })

    const text = within(await screen.findByRole('group', { name: 'Étape 2' })).getByLabelText('Texte')
    await user.type(text, ' les pâtes')
    await save(user)

    expect(await within(group(2)).findByRole('alert')).toHaveTextContent(message)
    expect(within(group(1)).queryByRole('alert')).not.toBeInTheDocument()

    // The refusal goes away as soon as the steps are touched again.
    await user.type(text, '!')
    await waitFor(() => expect(within(group(2)).queryByRole('alert')).not.toBeInTheDocument())
  })

  test('a 400 about the list as a whole is shown above the steps', async () => {
    const user = userEvent.setup({ delay: null })
    const message = 'steps must be a list.'
    const update = vi.fn().mockRejectedValue(new HttpError(message, 400, { 'hydra:description': message }))
    renderEdit(update, { ...recipe, steps: [step(1, 'Couper')] })

    await user.type(within(await screen.findByRole('group', { name: 'Étape 1' })).getByLabelText('Texte'), 's')
    await save(user)

    const alerts = await screen.findAllByRole('alert')
    expect(alerts.some((alert) => alert.textContent === message && !group(1).contains(alert))).toBe(true)
  })
})

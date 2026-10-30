import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, ResourceDefinitionContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { emitMercure } from '../../test/mercure'
import { RecipeShow } from './RecipeShow'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const product = (id: string, name: string) => ({ '@id': `/api/ingredients/${id}`, id, name })
const ref = (id: string, name: string) => ({ id, name })

const BOEUF = product('P-boeuf', 'Bœuf à braiser')
const LARDONS = product('P-lardons', 'Lardons fumés')
const OIGNONS = product('P-oignons', 'Oignons')
const POITRINE = product('P-poitrine', 'Poitrine fumée')

const STEP_2 = 'Faire revenir 200 g de lardons fumés dans la cocotte'
const STEP_6 = 'Servir avec les lardons fumés croustillants'

interface StepFixture {
  id: string
  position: number
  text: string
  durationMinutes: number | null
  background: boolean
  ingredients: Array<{ id: string; name: string }>
  utensils: Array<{ id: string; name: string }>
  waitsFor: number[]
}

const step = (position: number, text: string, extra: Partial<StepFixture> = {}): StepFixture => ({
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
  id: '/api/recipes/01R',
  '@id': '/api/recipes/01R',
  name: 'Bœuf bourguignon',
  servings: 4,
  tags: ['mijoté'],
  notes: 'Meilleur le lendemain',
  ingredients: [
    { id: 'L1', ingredient: BOEUF, ingredientName: BOEUF.name, quantity: 800, unit: 'g' },
    { id: 'L2', ingredient: LARDONS, ingredientName: LARDONS.name, quantity: 200, unit: 'g' },
    { id: 'L3', ingredient: OIGNONS, ingredientName: OIGNONS.name, quantity: 2, unit: 'piece' },
  ],
  utensils: [ref('U1', 'Cocotte')],
  steps: [
    step(1, 'Couper le bœuf en cubes', { ingredients: [ref('P-boeuf', BOEUF.name)] }),
    step(2, STEP_2, { durationMinutes: 10, ingredients: [ref('P-lardons', LARDONS.name)], utensils: [ref('U1', 'Cocotte')] }),
    step(3, 'Ajouter le vin', { waitsFor: [2] }),
    step(4, 'Laisser mijoter', { durationMinutes: 150, background: true, waitsFor: [3] }),
    step(5, 'Rectifier l’assaisonnement', { waitsFor: [4] }),
    step(6, STEP_6, { ingredients: [ref('P-lardons', LARDONS.name)], waitsFor: [5] }),
  ],
}

const CATALOGUE = [
  { ...BOEUF, kcalPer100g: 100, proteinPer100g: 20, carbsPer100g: 0, fatPer100g: 5 },
  { ...LARDONS, kcalPer100g: 300, proteinPer100g: 15, carbsPer100g: 1, fatPer100g: 28, packagingUnit: 'pack', packagingSize: 200, packagingSizeUnit: 'g' },
  { ...OIGNONS },
  { ...POITRINE, kcalPer100g: 280 },
]

const TOMATO = { alim_code: '20047', alim_name_fr: 'Tomate, pulpe, appertisée', alim_group_name_fr: 'fruits, légumes', kcal_per100g: 23.5 }

const json = (body: unknown, ok = true, status = 200) => Promise.resolve({ ok, status, json: () => Promise.resolve(body) } as Response)

type Fetch = (url: string, init?: RequestInit) => Promise<Response>

const stubFetch = (routes: Record<string, () => Promise<Response>> = {}) => {
  const fetcher = vi.fn<Fetch>((input) => {
    const url = String(input)
    for (const [fragment, answer] of Object.entries(routes)) if (url.includes(fragment)) return answer()
    if (url.includes('/ciqual/foods?')) return json([TOMATO])
    if (url.includes('/deletion-impact')) return json({ mealCount: 0, meals: [] })

    return json(null, false, 404)
  })
  vi.stubGlobal('fetch', fetcher)

  return fetcher
}

const refusal = (message: string) => Object.assign(new Error(message), { status: 400, body: { 'hydra:description': message } })

const renderSheet = (overrides: Partial<DataProvider> = {}, record: Record<string, unknown> = recipe, hasEdit = false, address = '/recipes/%2Fapi%2Frecipes%2F01R/show') => {
  // The API answers with the recipe as it keeps it: here, the scalar fields the call changed.
  const update = vi.fn((_resource: string, params: { data: Record<string, unknown> }) => {
    const { name, servings, tags, notes } = params.data

    return Promise.resolve({ data: { ...record, ...(name === undefined ? {} : { name }), ...(servings === undefined ? {} : { servings }), ...(tags === undefined ? {} : { tags }), ...(notes === undefined ? {} : { notes }) } })
  })
  const create = vi.fn((_resource: string, params: { data: Record<string, unknown> }) => Promise.resolve({ data: { ...params.data, id: '/api/recipes/02COPY' } }))
  const remove = vi.fn(() => Promise.resolve({ data: record }))
  const dataProvider = testDataProvider({
    getOne: (() => Promise.resolve({ data: record })) as unknown as DataProvider['getOne'],
    getList: (() => Promise.resolve({ data: CATALOGUE, total: CATALOGUE.length })) as unknown as DataProvider['getList'],
    getMany: (() => Promise.resolve({ data: CATALOGUE })) as unknown as DataProvider['getMany'],
    update: update as unknown as DataProvider['update'],
    create: create as unknown as DataProvider['create'],
    delete: remove as unknown as DataProvider['delete'],
    ...overrides,
  })

  render(
    <MemoryRouter initialEntries={[address]}>
      <AdminContext dataProvider={dataProvider} i18nProvider={i18nProvider}>
        <Routes>
          <Route
            path="/recipes/:id/show"
            element={
              <ResourceDefinitionContextProvider definitions={{ recipes: { name: 'recipes', hasList: true, hasShow: true, hasEdit } }}>
                <ResourceContextProvider value="recipes">
                  <RecipeShow />
                </ResourceContextProvider>
              </ResourceDefinitionContextProvider>
            }
          />
          <Route path="*" element={<p>ailleurs</p>} />
        </Routes>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

  return { update, create, remove }
}

const openMenu = async (user: ReturnType<typeof userEvent.setup>, label: string, item: string) => {
  await user.click(await screen.findByRole('button', { name: label }))
  await user.click(await screen.findByRole('menuitem', { name: item }))
}

const edit = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByRole('button', { name: 'Modifier' }))
  await screen.findByRole('button', { name: 'Terminé' })
}

const stepItems = () => screen.getAllByRole('listitem', { name: /^Étape \d+$/ })

describe('RecipeShow', { timeout: 30_000 }, () => {
  beforeEach(() => stubFetch())
  afterEach(() => vi.unstubAllGlobals())

  test('offers no edit form: react-admin’s default Éditer button is gone even when the resource has an edit route', async () => {
    renderSheet({}, recipe, true)

    expect(await screen.findByRole('heading', { name: 'Bœuf bourguignon' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Éditer' })).not.toBeInTheDocument()
  })

  test('the sheet keeps a reading width: a quantity stays near its ingredient on a wide screen (MAG-612)', async () => {
    renderSheet()

    const heading = await screen.findByRole('heading', { name: 'Bœuf bourguignon' })
    const page = screen.getByTestId('reading-page')
    expect(page).toContainElement(heading)
    expect(page).toContainElement(screen.getByRole('region', { name: 'Ingrédients' }))
    expect(page).toHaveStyle({ maxWidth: '1100px' })
  })

  test('reads like a page: quantities before the names, the steps beside, what one serving brings at the end', async () => {
    renderSheet()

    expect(await screen.findByRole('heading', { name: 'Bœuf bourguignon' })).toBeInTheDocument()
    expect(screen.getByText('mijoté')).toBeInTheDocument()
    expect(screen.getByTestId('guests-value')).toHaveTextContent('4')

    const ingredients = screen.getByRole('region', { name: 'Ingrédients' })
    expect(within(ingredients).getByRole('heading', { name: 'Ingrédients · 4 portions' })).toBeInTheDocument()
    const lardons = within(ingredients).getByText('Lardons fumés').closest('li') as HTMLElement
    expect(lardons.textContent).toMatch(/^200gLardons fumés/)
    expect(within(lardons).getByText('ét. 2 · 6')).toBeInTheDocument()
    expect(within(lardons).getByText('paquet de 200 g')).toBeInTheDocument()
    const oignons = within(ingredients).getByText('Oignons').closest('li') as HTMLElement
    expect(oignons.textContent).toMatch(/^2pièces/)
    expect(within(oignons).queryByText(/ét\./)).not.toBeInTheDocument()
    expect(within(ingredients).getByText('Bœuf à braiser').closest('li')).toHaveTextContent('ét. 1')

    expect(within(screen.getByRole('list', { name: 'Ustensiles' })).getByText('Cocotte')).toBeInTheDocument()
    expect(screen.queryByText('Matériel')).not.toBeInTheDocument()
    expect(screen.getByText('Meilleur le lendemain')).toBeInTheDocument()

    // 10 min + 2 h 30 of simmering, the simmering being left to the pot.
    const steps = screen.getByRole('region', { name: 'Étapes' })
    expect(within(steps).getByRole('heading', { name: 'Étapes · 2 h 40, dont 2 h 30 en fond' })).toBeInTheDocument()
    const items = stepItems()
    expect(items).toHaveLength(6)
    expect(items[1]).toHaveTextContent(new RegExp(`^2${STEP_2}`))
    expect(within(items[1]).getByText('⏱ 10 min')).toBeInTheDocument()
    expect(within(items[1]).getByText('Lardons')).toBeInTheDocument()
    expect(within(items[1]).getByText('Cocotte')).toBeInTheDocument()
    expect(within(items[0]).getByText('Bœuf')).toBeInTheDocument()
    expect(within(items[3]).getByText('en fond')).toBeInTheDocument()
    expect(within(items[3]).getByText('⏱ 2 h 30')).toBeInTheDocument()
    expect(within(items[3]).getByText('attend ét. 3')).toBeInTheDocument()

    // (800 g × 100 + 200 g × 300) / 4 portions; the onions, counted in pieces, are left out and said so.
    expect(await screen.findByTestId('nutrition-energy')).toHaveTextContent('≈ 350 kcal')
    expect(screen.getByText('Protéines 48 g')).toBeInTheDocument()
    expect(screen.getByText('Lipides 24 g')).toBeInTheDocument()
    expect(screen.getByText('Glucides 1 g')).toBeInTheDocument()
    expect(screen.getByText(/1 ingrédient non compté/)).toBeInTheDocument()
  })

  test('opens to read: none of the edit tools is there, the portions and the actions of the page are', async () => {
    renderSheet()

    expect(await screen.findByRole('heading', { name: 'Bœuf bourguignon' })).toBeInTheDocument()
    for (const name of [
      'Ajouter un ingrédient',
      'Ajouter une étape',
      'Modifier la quantité de Lardons fumés',
      'Actions pour Oignons',
      "Actions pour l'étape 1",
      "Déplacer l'étape 1",
      'Modifier les notes',
      'Modifier les ustensiles',
      'Modifier le nom, les portions et les tags',
    ]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: /Un convive de plus/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cuisiner' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Actions de la recette' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Modifier' })).toBeInTheDocument()
  })

  test('« Modifier » turns the edit tools on and « Terminé » turns them off', async () => {
    const user = userEvent.setup()
    renderSheet()

    await edit(user)
    expect(screen.queryByRole('button', { name: 'Modifier' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ajouter un ingrédient' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ajouter une étape' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Modifier la quantité de Lardons fumés' })).toHaveTextContent('200 g')
    expect(screen.getByRole('button', { name: "Actions pour l'étape 1" })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: "Déplacer l'étape 1" })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Modifier les notes' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Modifier les ustensiles' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Modifier le nom, les portions et les tags' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Terminé' }))
    expect(await screen.findByRole('button', { name: 'Modifier' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ajouter un ingrédient' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: "Actions pour l'étape 1" })).not.toBeInTheDocument()
    expect(screen.getByText('Lardons fumés')).toBeInTheDocument()
  })

  test('an ingredient added while editing is on the sheet once back to reading', async () => {
    const user = userEvent.setup()
    const after = { ...recipe, ingredients: [...recipe.ingredients, { id: 'L4', ingredient: POITRINE, ingredientName: POITRINE.name, quantity: 150, unit: 'g' }] }
    renderSheet({ update: (() => Promise.resolve({ data: after })) as unknown as DataProvider['update'] })

    await edit(user)
    await user.click(screen.getByRole('button', { name: 'Ajouter un ingrédient' }))
    await user.type(await screen.findByLabelText('Ingrédient'), 'poitrine')
    await user.click(await screen.findByRole('option', { name: /Poitrine fumée/ }))
    await user.type(screen.getByLabelText('Quantité'), '150')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Terminé' }))
    const ingredients = screen.getByRole('region', { name: 'Ingrédients' })
    expect((await within(ingredients).findByText('Poitrine fumée')).closest('li')?.textContent).toMatch(/^150gPoitrine fumée/)
    expect(screen.queryByRole('button', { name: 'Ajouter un ingrédient' })).not.toBeInTheDocument()
  })

  test('a click on the quantity turns it into a field, and Enter saves the whole list of lines', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: 'Modifier la quantité de Lardons fumés' }))
    const field = screen.getByRole('spinbutton', { name: 'Quantité de Lardons fumés' })
    await user.clear(field)
    await user.type(field, '300{Enter}')

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].data).toEqual({
      ingredients: [
        { ingredientId: 'P-boeuf', quantity: 800, unit: 'g' },
        { ingredientId: 'P-lardons', quantity: 300, unit: 'g' },
        { ingredientId: 'P-oignons', quantity: 2, unit: 'piece' },
      ],
    })
  })

  test('a quantity that is not above zero is refused on the spot, and the API refusal is shown under the field', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet({ update: (() => Promise.reject(refusal('Quantité trop grande'))) as unknown as DataProvider['update'] })
    await edit(user)

    await user.click(await screen.findByRole('button', { name: 'Modifier la quantité de Oignons' }))
    const field = screen.getByRole('spinbutton', { name: 'Quantité de Oignons' })
    await user.clear(field)
    await user.type(field, '0{Enter}')
    expect(await screen.findByText('La quantité doit être supérieure à 0')).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()

    await user.clear(field)
    await user.type(field, '99999{Enter}')
    expect(await screen.findByText('Quantité trop grande')).toBeInTheDocument()
  })

  test('simulating portions shows the API-scaled quantities and takes the edit actions away', async () => {
    const user = userEvent.setup()
    const fetcher = stubFetch({
      'guests=6': () => json({ ingredients: [{ id: 'L2', ingredientName: 'Lardons fumés', quantity: 300, unit: 'g' }] }),
    })
    renderSheet()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: /Un convive de plus/ }))
    await user.click(screen.getByRole('button', { name: /Un convive de plus/ }))

    await waitFor(() => expect(fetcher).toHaveBeenCalledWith(expect.stringContaining('/api/recipes/01R?guests=6'), expect.anything()))
    expect(await screen.findByRole('button', { name: 'Modifier la quantité de Lardons fumés' })).toHaveTextContent('300 g')
    expect(screen.getByText(/une simulation, rien n’est enregistré/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Modifier la quantité de Lardons fumés' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Ajouter un ingrédient' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Revenir à 4' }))
    expect(await screen.findByRole('button', { name: 'Modifier la quantité de Lardons fumés' })).toHaveTextContent('200 g')
  })

  test('says so when the scaled quantities cannot be computed, and keeps the recipe’s own', async () => {
    const user = userEvent.setup()
    stubFetch({ 'guests=5': () => json(null, false, 500) })
    renderSheet()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: /Un convive de plus/ }))

    expect(await screen.findByText(/Impossible de calculer les quantités pour 5 personnes/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Modifier la quantité de Lardons fumés' })).toHaveTextContent('200 g')
  })

  test('Monter and Descendre reorder the steps and send them in their new order', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet()
    await edit(user)

    await openMenu(user, "Actions pour l'étape 2", 'Monter')
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    let sent = update.mock.calls[0][1].data.steps as Array<{ text: string; waitsFor: number[] }>
    expect(sent.map((s) => s.text).slice(0, 2)).toEqual(['Faire revenir 200 g de lardons fumés dans la cocotte', 'Couper le bœuf en cubes'])

    await openMenu(user, "Actions pour l'étape 5", 'Descendre')
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2))
    sent = update.mock.calls[1][1].data.steps as Array<{ text: string; waitsFor: number[] }>
    expect(sent[4].text).toBe(STEP_6)
    expect(sent[5].text).toBe('Rectifier l’assaisonnement')
  })

  test('the first step cannot go up and the last cannot go down', async () => {
    const user = userEvent.setup()
    renderSheet()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: "Actions pour l'étape 1" }))
    expect(screen.getByRole('menuitem', { name: 'Monter' })).toHaveAttribute('aria-disabled', 'true')
  })

  test('a refused reorder is said in a notification', async () => {
    const user = userEvent.setup()
    renderSheet({ update: (() => Promise.reject(refusal('Étape 3 attend une étape qui la suit'))) as unknown as DataProvider['update'] })
    await edit(user)

    await openMenu(user, "Actions pour l'étape 3", 'Monter')

    expect(await screen.findByText('Étape 3 attend une étape qui la suit')).toBeInTheDocument()
  })

  test('deleting a step sends the list without it', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet()
    await edit(user)

    await openMenu(user, "Actions pour l'étape 3", 'Supprimer')

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect((update.mock.calls[0][1].data.steps as unknown[]).length).toBe(5)
  })

  test('a step is edited in a popin', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet()
    await edit(user)

    await openMenu(user, "Actions pour l'étape 3", 'Modifier')
    const text = await screen.findByLabelText('Texte')
    await user.clear(text)
    await user.type(text, 'Ajouter le vin rouge')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const steps = update.mock.calls[0][1].data.steps as Array<{ text: string; waitsFor: number[] }>
    expect(steps[2]).toMatchObject({ text: 'Ajouter le vin rouge', waitsFor: [2] })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  test('a step popin shows the API refusal and stays open', async () => {
    const user = userEvent.setup()
    renderSheet({ update: (() => Promise.reject(refusal('Step 3 needs a text.'))) as unknown as DataProvider['update'] })
    await edit(user)

    await openMenu(user, "Actions pour l'étape 3", 'Modifier')
    await user.click(await screen.findByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Step 3 needs a text.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  test('a new step is added at the end', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: 'Ajouter une étape' }))
    await user.type(await screen.findByLabelText('Texte'), 'Dresser')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const steps = update.mock.calls[0][1].data.steps as Array<{ text: string }>
    expect(steps).toHaveLength(7)
    expect(steps[6].text).toBe('Dresser')
  })

  test('the notes and the utensils each have their own popin', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: 'Modifier les notes' }))
    const notes = await screen.findByRole('textbox', { name: 'Notes' })
    expect(screen.getByRole('toolbar', { name: 'Barre de mise en forme' })).toBeInTheDocument()
    await user.clear(notes)
    await user.type(notes, 'À réchauffer')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('recipes', expect.objectContaining({ data: { notes: 'À réchauffer' } })))

    await user.click(await screen.findByRole('button', { name: 'Modifier les ustensiles' }))
    await user.click(await screen.findByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('recipes', expect.objectContaining({ data: { utensils: ['Cocotte'] } })))
  })

  test('clearing the notes removes the block, although the API leaves a null field out of its answer', async () => {
    const user = userEvent.setup()
    const withoutNotes = Object.fromEntries(Object.entries(recipe).filter(([field]) => field !== 'notes'))
    const update = vi.fn(() => Promise.resolve({ data: withoutNotes }))
    renderSheet({ update: update as unknown as DataProvider['update'] })
    expect(await screen.findByText('Meilleur le lendemain')).toBeInTheDocument()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: 'Modifier les notes' }))
    await user.clear(await screen.findByRole('textbox', { name: 'Notes' }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith('recipes', expect.objectContaining({ data: { notes: null } })))

    await user.click(screen.getByRole('button', { name: 'Terminé' }))
    await waitFor(() => expect(screen.queryByText('Meilleur le lendemain')).not.toBeInTheDocument())
  })

  test('the name, portions and tags are edited together, and a refusal stays in the popin', async () => {
    const user = userEvent.setup()
    const { update } = renderSheet()
    await edit(user)

    await user.click(await screen.findByRole('button', { name: 'Modifier le nom, les portions et les tags' }))
    const name = await screen.findByRole('textbox', { name: 'Nom' })
    await user.clear(name)
    await user.type(name, 'Bœuf bourguignon du dimanche')
    const servings = screen.getByRole('spinbutton', { name: 'Portions' })
    await user.clear(servings)
    await user.type(servings, '6')
    const tags = screen.getByLabelText(/Tags/)
    await user.clear(tags)
    await user.type(tags, 'mijoté, dimanche')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    expect(update.mock.calls[0][1].data).toEqual({ name: 'Bœuf bourguignon du dimanche', servings: 6, tags: ['mijoté', 'dimanche'] })
  })

  test('the header popin keeps its refusal visible', async () => {
    const user = userEvent.setup()
    renderSheet({ update: (() => Promise.reject(refusal('Nom déjà pris'))) as unknown as DataProvider['update'] })
    await edit(user)

    await user.click(await screen.findByRole('button', { name: 'Modifier le nom, les portions et les tags' }))
    await user.click(await screen.findByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Nom déjà pris')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  test('« Cuisiner » opens the Cuisine activity on this recipe', async () => {
    renderSheet()

    const link = await screen.findByRole('link', { name: 'Cuisiner' })

    expect(link).toHaveAttribute('href', '/activites/cuisine?recipe=01R')
  })

  describe('an activity in progress (MAG-615)', () => {
    const inProgress = (recipeIds: string[], step: number, origin = { kind: 'meal', id: 'M1' }) =>
      json({
        origin,
        state: { queue: recipeIds.map((recipeId) => ({ recipeId, servings: 4 })), recipeIndex: 0, step, doneSteps: {}, guests: 4, phase: 'cooking', startedAt: 1785000000000 },
        updatedAt: '2026-10-25T12:00:00+00:00',
      })
    const simmering = { id: 'T1', label: 'Mijotage', recipe: '/api/recipes/01R', stepPosition: 4, durationSeconds: 9000, endsAt: null, remainingSeconds: 6420, status: 'paused', finishedAt: null }

    test('« Reprendre » replaces « Cuisiner », says where the cook stands and reopens the launch it came from', async () => {
      stubFetch({
        '/activity-status/cuisine': () => inProgress(['01R', '02R'], 4),
        '/cooking/timers': () => json({ member: [simmering] }),
      })
      renderSheet()

      const link = await screen.findByRole('link', { name: 'Reprendre' })

      expect(link).toHaveAttribute('href', '/activites/cuisine?meal=M1')
      expect(screen.queryByRole('link', { name: 'Cuisiner' })).not.toBeInTheDocument()
      expect(await screen.findByTestId('recipe-cook-detail')).toHaveTextContent('étape 4/6 · mijotage 1 h 47')
    })

    test('the detail is the step alone when no timer runs', async () => {
      stubFetch({ '/activity-status/cuisine': () => inProgress(['01R'], 3, { kind: 'recipe', id: '01R' }), '/cooking/timers': () => json({ member: [] }) })
      renderSheet()

      expect(await screen.findByRole('link', { name: 'Reprendre' })).toHaveAttribute('href', '/activites/cuisine?recipe=01R')
      expect(await screen.findByTestId('recipe-cook-detail')).toHaveTextContent(/^étape 3\/6$/)
    })

    test('an activity on other recipes leaves « Cuisiner » alone', async () => {
      stubFetch({ '/activity-status/cuisine': () => inProgress(['02R'], 2) })
      renderSheet()

      expect(await screen.findByRole('link', { name: 'Cuisiner' })).toHaveAttribute('href', '/activites/cuisine?recipe=01R')
      expect(screen.queryByTestId('recipe-cook-detail')).not.toBeInTheDocument()
    })
  })

  test('Dupliquer creates a copy from the sheet', async () => {
    const user = userEvent.setup()
    const { create } = renderSheet()

    await openMenu(user, 'Actions de la recette', 'Dupliquer')

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({
      name: 'Bœuf bourguignon (copie)',
      servings: 4,
      tags: ['mijoté'],
      utensils: ['Cocotte'],
    })
    expect((create.mock.calls[0][1].data.ingredients as unknown[]).length).toBe(3)
    expect((create.mock.calls[0][1].data.steps as unknown[]).length).toBe(6)
    expect(await screen.findByText('Recette dupliquée')).toBeInTheDocument()
  })

  test('a copy the API refuses is said, nothing else happens', async () => {
    const user = userEvent.setup()
    renderSheet({ create: (() => Promise.reject(refusal('Copie refusée'))) as unknown as DataProvider['create'] })

    await openMenu(user, 'Actions de la recette', 'Dupliquer')

    expect(await screen.findByText('Copie refusée')).toBeInTheDocument()
  })

  test('Planifier un repas opens the planning popin and creates the meal', async () => {
    const user = userEvent.setup()
    const { create } = renderSheet()

    await openMenu(user, 'Actions de la recette', 'Planifier un repas')
    expect(await screen.findByRole('dialog', { name: /Planifier « Bœuf bourguignon »/ })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Planifier' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][0]).toBe('meals')
    expect(create.mock.calls[0][1].data).toMatchObject({ slot: 'dinner', recipes: ['/api/recipes/01R'] })
  })

  test('Supprimer asks for confirmation before deleting', async () => {
    const user = userEvent.setup()
    const { remove } = renderSheet()

    await openMenu(user, 'Actions de la recette', 'Supprimer')
    expect(await screen.findByText('Supprimer « Bœuf bourguignon » ?')).toBeInTheDocument()
    expect(remove).not.toHaveBeenCalled()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Confirmer' })).toBeEnabled())
    await user.click(screen.getByRole('button', { name: 'Confirmer' }))

    await waitFor(() => expect(remove).toHaveBeenCalledTimes(1))
  })

  test('cancelling the deletion deletes nothing', async () => {
    const user = userEvent.setup()
    const { remove } = renderSheet()

    await openMenu(user, 'Actions de la recette', 'Supprimer')
    await user.click(await screen.findByRole('button', { name: 'Annuler' }))

    expect(remove).not.toHaveBeenCalled()
  })

  describe('removing an ingredient', () => {
    test('one no step uses leaves at once', async () => {
      const user = userEvent.setup()
      const { update } = renderSheet()
      await edit(user)

      await openMenu(user, 'Actions pour Oignons', 'Retirer')

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      expect(update.mock.calls[0][1].data).toEqual({
        ingredients: [
          { ingredientId: 'P-boeuf', quantity: 800, unit: 'g' },
          { ingredientId: 'P-lardons', quantity: 200, unit: 'g' },
        ],
      })
    })

    test('one still linked to steps asks first, lists them, then takes the links off', async () => {
      const user = userEvent.setup()
      const { update } = renderSheet()
      await edit(user)

      await openMenu(user, 'Actions pour Lardons fumés', 'Retirer')

      expect(await screen.findByText('Retirer « Lardons fumés » ?')).toBeInTheDocument()
      expect(screen.getByText(/Les étapes 2, 6 utilisent cet ingrédient/)).toBeInTheDocument()
      expect(update).not.toHaveBeenCalled()
      await user.click(screen.getByRole('button', { name: 'Retirer' }))

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      const data = update.mock.calls[0][1].data as { ingredients: unknown[]; steps: Array<{ text: string; ingredientIds: string[] }> }
      expect(data.ingredients).toHaveLength(2)
      expect(data.steps.map((s) => s.ingredientIds)).toEqual([['P-boeuf'], [], [], [], [], []])
      // The texts are the owner's: only the links go.
      expect(data.steps[1].text).toBe(STEP_2)
    })

    test('declining the confirmation changes nothing', async () => {
      const user = userEvent.setup()
      const { update } = renderSheet()
      await edit(user)

      await openMenu(user, 'Actions pour Lardons fumés', 'Retirer')
      await user.click(await screen.findByRole('button', { name: 'Annuler' }))

      expect(update).not.toHaveBeenCalled()
    })

    test('a refusal is notified and the ingredient stays', async () => {
      const user = userEvent.setup()
      renderSheet({ update: (() => Promise.reject(refusal('Impossible de retirer'))) as unknown as DataProvider['update'] })
      await edit(user)

      await openMenu(user, 'Actions pour Oignons', 'Retirer')

      expect(await screen.findByText('Impossible de retirer')).toBeInTheDocument()
      expect(screen.getByText('Oignons')).toBeInTheDocument()
    })
  })

  describe('adding an ingredient', () => {
    test('a Ciqual food goes into the new line by its code', async () => {
      const user = userEvent.setup()
      const { update } = renderSheet()
      await edit(user)

      await user.click(await screen.findByRole('button', { name: 'Ajouter un ingrédient' }))
      await user.type(await screen.findByLabelText('Ingrédient'), 'tomate')
      await user.click(await screen.findByRole('option', { name: new RegExp(TOMATO.alim_name_fr, 'i') }, { timeout: 5_000 }))
      await user.type(screen.getByLabelText('Quantité'), '400')
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      const sent = update.mock.calls[0][1].data.ingredients as Array<Record<string, unknown>>
      expect(sent).toHaveLength(4)
      expect(sent[3]).toEqual({ ciqualAlimCode: TOMATO.alim_code, quantity: 400, unit: 'g' })
    })

    test('a catalogue product goes in by its id', async () => {
      const user = userEvent.setup()
      const { update } = renderSheet()
      await edit(user)

      await user.click(await screen.findByRole('button', { name: 'Ajouter un ingrédient' }))
      await user.type(await screen.findByLabelText('Ingrédient'), 'poitrine')
      await user.click(await screen.findByRole('option', { name: /Poitrine fumée/ }))
      await user.type(screen.getByLabelText('Quantité'), '150')
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      expect((update.mock.calls[0][1].data.ingredients as Array<Record<string, unknown>>)[3]).toEqual({ ingredientId: 'P-poitrine', quantity: 150, unit: 'g' })
    })

    test('a product already in the recipe is refused before anything is sent', async () => {
      const user = userEvent.setup()
      const { update } = renderSheet()
      await edit(user)

      await user.click(await screen.findByRole('button', { name: 'Ajouter un ingrédient' }))
      await user.type(await screen.findByLabelText('Ingrédient'), 'oignons')
      await user.click(await screen.findByRole('option', { name: 'Oignons' }))
      await user.type(screen.getByLabelText('Quantité'), '1')
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      expect(await screen.findByText(/« Oignons » est déjà dans la recette/)).toBeInTheDocument()
      expect(update).not.toHaveBeenCalled()
    })

    test('the catalogue shows how a product is bought', async () => {
      const user = userEvent.setup()
      renderSheet()
      await edit(user)

      await user.click(await screen.findByRole('button', { name: 'Ajouter un ingrédient' }))
      await user.type(await screen.findByLabelText('Ingrédient'), 'lardons')

      const option = await screen.findByRole('option', { name: /Lardons fumés/ })
      expect(within(option).getByText(/200/)).toBeInTheDocument()
    })
  })

  describe('replacing an ingredient (lardons → poitrine fumée)', () => {
    const after = {
      ...recipe,
      ingredients: [recipe.ingredients[0], { id: 'L2', ingredient: POITRINE, ingredientName: POITRINE.name, quantity: 200, unit: 'g' }, recipe.ingredients[2]],
      steps: recipe.steps.map((s) => (s.ingredients.some((i) => i.id === 'P-lardons') ? { ...s, ingredients: [ref('P-poitrine', POITRINE.name)] } : s)),
    }
    const suggestions = [
      { stepId: 's2', position: 2, before: STEP_2, after: 'Faire revenir 200 g de poitrine fumée dans la cocotte' },
      { stepId: 's6', position: 6, before: STEP_6, after: 'Servir avec la poitrine fumée croustillante' },
    ]

    const replace = async (user: ReturnType<typeof userEvent.setup>) => {
      await openMenu(user, 'Actions pour Lardons fumés', 'Modifier')
      const picker = await screen.findByLabelText('Ingrédient')
      await user.clear(picker)
      await user.type(picker, 'poitrine')
      await user.click(await screen.findByRole('option', { name: /Poitrine fumée/ }))
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))
    }

    test('moves the links, then shows each step before and after; « Mettre à jour » rewrites them', async () => {
      const user = userEvent.setup()
      const fetcher = stubFetch({ '/replace-ingredient': () => json({ recipe: after, textSuggestions: suggestions, textRewritten: false }) })
      const { update } = renderSheet()
      await edit(user)

      await replace(user)

      await waitFor(() => expect(fetcher).toHaveBeenCalledWith('/api/recipes/01R/replace-ingredient', expect.objectContaining({ method: 'POST' })))
      const call = fetcher.mock.calls.find(([url]) => String(url).includes('/replace-ingredient')) as [string, RequestInit]
      expect(JSON.parse(String(call[1].body))).toEqual({ ingredientId: 'P-lardons', with: { ingredientId: 'P-poitrine' }, quantity: 200, unit: 'g' })

      expect(await screen.findByText('Mettre à jour les étapes ?')).toBeInTheDocument()
      const list = screen.getByRole('list', { name: 'Étapes à mettre à jour' })
      expect(within(list).getByText(STEP_2)).toBeInTheDocument()
      expect(within(list).getByText('Faire revenir 200 g de poitrine fumée dans la cocotte')).toBeInTheDocument()
      expect(within(list).getByText(STEP_6)).toBeInTheDocument()
      expect(within(list).getByText('Servir avec la poitrine fumée croustillante')).toBeInTheDocument()
      // Nothing is rewritten until the owner says so.
      expect(update).not.toHaveBeenCalled()

      await user.click(screen.getByRole('button', { name: 'Mettre à jour' }))

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      const steps = update.mock.calls[0][1].data.steps as Array<{ text: string; ingredientIds: string[] }>
      expect(steps[1]).toMatchObject({ text: 'Faire revenir 200 g de poitrine fumée dans la cocotte', ingredientIds: ['P-poitrine'] })
      expect(steps[5]).toMatchObject({ text: 'Servir avec la poitrine fumée croustillante', ingredientIds: ['P-poitrine'] })
      expect(steps[0].text).toBe('Couper le bœuf en cubes')
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })

    test('« Laisser » keeps the texts as they are: the replacement stays, nothing is rewritten', async () => {
      const user = userEvent.setup()
      stubFetch({ '/replace-ingredient': () => json({ recipe: after, textSuggestions: suggestions, textRewritten: false }) })
      const { update } = renderSheet()
      await edit(user)

      await replace(user)
      await user.click(await screen.findByRole('button', { name: 'Laisser' }))

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(update).not.toHaveBeenCalled()
      expect(screen.getByRole('button', { name: 'Modifier la quantité de Poitrine fumée' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Modifier la quantité de Lardons fumés' })).not.toBeInTheDocument()
    })

    test('a step text that cites nothing needs no confirmation: the popin just closes', async () => {
      const user = userEvent.setup()
      stubFetch({ '/replace-ingredient': () => json({ recipe: after, textSuggestions: [], textRewritten: false }) })
      renderSheet()
      await edit(user)

      await replace(user)

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(screen.getByRole('button', { name: 'Modifier la quantité de Poitrine fumée' })).toBeInTheDocument()
    })

    test('a refusal of the API is shown in the popin and nothing changes', async () => {
      const user = userEvent.setup()
      stubFetch({ '/replace-ingredient': () => json({ error: '« Poitrine fumée » est déjà dans la recette.' }, false, 400) })
      renderSheet()
      await edit(user)

      await replace(user)

      expect(await screen.findByText('« Poitrine fumée » est déjà dans la recette.')).toBeInTheDocument()
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    test('an unreachable server is said, not swallowed', async () => {
      const user = userEvent.setup()
      stubFetch({ '/replace-ingredient': () => Promise.reject(new TypeError('network')) })
      renderSheet()
      await edit(user)

      await replace(user)

      expect(await screen.findByText(/Le remplacement a échoué/)).toBeInTheDocument()
    })

    test('changing only the quantity of a line is a plain save, not a replacement', async () => {
      const user = userEvent.setup()
      const fetcher = stubFetch()
      const { update } = renderSheet()
      await edit(user)

      await openMenu(user, 'Actions pour Lardons fumés', 'Modifier')
      const quantity = await screen.findByLabelText('Quantité')
      await user.clear(quantity)
      await user.type(quantity, '250')
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      expect(fetcher).not.toHaveBeenCalledWith(expect.stringContaining('/replace-ingredient'), expect.anything())
    })
  })

  test('what Maggie or another screen changes arrives live, without a reload', async () => {
    renderSheet()
    expect(await screen.findByRole('heading', { name: 'Bœuf bourguignon' })).toBeInTheDocument()

    emitMercure('/api/recipes/{id}', { '@id': '/api/recipes/01R', name: 'Bœuf bourguignon du dimanche' })

    expect(await screen.findByRole('heading', { name: 'Bœuf bourguignon du dimanche' })).toBeInTheDocument()
  })

  test('a Mercure event about another recipe is ignored', async () => {
    renderSheet()
    expect(await screen.findByRole('heading', { name: 'Bœuf bourguignon' })).toBeInTheDocument()

    emitMercure('/api/recipes/{id}', { '@id': '/api/recipes/OTHER', name: 'Autre chose' })

    expect(screen.getByRole('heading', { name: 'Bœuf bourguignon' })).toBeInTheDocument()
  })

  test('a recipe without ingredients, utensils, notes or steps reads without empty blocks, and shows no nutrition', async () => {
    const user = userEvent.setup()
    renderSheet({}, { id: '/api/recipes/01R', name: 'Salade', servings: 1, tags: [], notes: null, ingredients: [], utensils: [], steps: [] })

    expect(await screen.findByRole('heading', { name: 'Salade' })).toBeInTheDocument()
    expect(screen.getByText('Aucun ingrédient pour l’instant.')).toBeInTheDocument()
    expect(screen.getByText('Aucune étape pour l’instant.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Ustensiles' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Notes' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Valeurs par personne/)).not.toBeInTheDocument()

    await edit(user)
    expect(screen.getByText('Aucun ustensile.')).toBeInTheDocument()
    expect(screen.getByText('Aucune note.')).toBeInTheDocument()
  })

  test('an address spelling the ULID as a UUID opens the sheet instead of crashing on the id the API answers with (MAG-641)', async () => {
    const canonical = '/api/recipes/01M5PVZHGMHNHJ6VHJY6G0XWS1'
    const getOne = vi.fn((_resource: string, params: { id: string }) => Promise.resolve({ data: { ...recipe, id: canonical, '@id': canonical, name: `Fiche ${params.id}` } }))
    renderSheet({ getOne: getOne as unknown as DataProvider['getOne'] }, recipe, false, `/recipes/${encodeURIComponent('/api/recipes/01a16dbf-c614-8d63-236e-32f1a00ef321')}/show`)

    expect(await screen.findByRole('heading', { name: `Fiche ${canonical}` })).toBeInTheDocument()
    expect(getOne).not.toHaveBeenCalledWith('recipes', expect.objectContaining({ id: '/api/recipes/01a16dbf-c614-8d63-236e-32f1a00ef321' }))
  })
})

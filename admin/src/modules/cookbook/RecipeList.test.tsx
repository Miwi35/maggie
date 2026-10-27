import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RecipeList } from './RecipeList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const step = (position: number, text: string, durationMinutes: number | null, background = false) => ({
  id: `s${position}`,
  position,
  text,
  durationMinutes,
  background,
  ingredients: [],
  utensils: [],
  waitsFor: [],
})

const GRATIN = {
  id: '/api/recipes/01GRATIN',
  name: 'Gratin dauphinois',
  servings: 4,
  tags: ['four'],
  ingredients: [
    { id: 'L1', ingredient: { id: 'P1', name: 'Pommes de terre' }, ingredientName: 'Pommes de terre', quantity: 800, unit: 'g' },
    { id: 'L2', ingredient: { id: 'P2', name: 'Crème' }, ingredientName: 'Crème', quantity: 20, unit: 'cl' },
  ],
  steps: [step(1, 'Éplucher et trancher les pommes de terre', 10), step(2, 'Enfourner le gratin', 90, true)],
  createdAt: '2026-10-01T10:00:00+00:00',
}
const SALADE = {
  id: '/api/recipes/01SALADE',
  name: 'Salade express',
  servings: 2,
  tags: ['Rapide', 'végétarien'],
  ingredients: [],
  steps: [],
  createdAt: '2026-10-02T10:00:00+00:00',
}
const FLAN = { id: '/api/recipes/01FLAN', name: 'Flan', servings: 6, tags: [], ingredients: [], steps: [], createdAt: '2026-10-03T10:00:00+00:00' }

const FACTS = {
  recipes: {
    '01GRATIN': { lastMealDate: '2026-10-24', kcalPerServing: 300 },
    '01SALADE': { lastMealDate: null, kcalPerServing: null },
  },
}

const renderList = (facts: Response | Promise<never> = { ok: true, json: () => Promise.resolve(FACTS) } as Response) => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(facts)))
  const dataProvider = testDataProvider({
    getList: (() => Promise.resolve({ data: [GRATIN, SALADE, FLAN], total: 3 })) as unknown as DataProvider['getList'],
  })

  return render(
    <MemoryRouter initialEntries={['/recipes']}>
      <AdminContext dataProvider={dataProvider}>
        <ResourceContextProvider value="recipes">
          <Routes>
            <Route path="/recipes" element={<RecipeList />} />
            <Route path="/recipes/create" element={<p>Création</p>} />
            <Route path="/recipes/:id/show" element={<p>Fiche ouverte</p>} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
}

const rowOf = (name: string) => screen.getByRole('row', { name: new RegExp(name) })

describe('RecipeList — the table of the mockup (MAG-629)', { timeout: 30_000 }, () => {
  beforeEach(() => localStorage.setItem('token', 'jwt'))
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('has the mockup’s columns, and neither the ingredient count nor the creation date', async () => {
    renderList()
    await screen.findByText('Gratin dauphinois')

    const headers = screen.getAllByRole('columnheader').map((header) => header.textContent)
    expect(headers).toEqual(['Recette', 'Portions', 'Tags', 'Durée', 'kcal / pers.', 'Dernier repas'])
  })

  test('has no button on a row: no checkbox, no « Afficher », no « Supprimer »', async () => {
    renderList()
    await screen.findByText('Gratin dauphinois')

    expect(within(rowOf('Gratin dauphinois')).queryByRole('button')).not.toBeInTheDocument()
    expect(within(rowOf('Gratin dauphinois')).queryByRole('checkbox')).not.toBeInTheDocument()
  })

  test('reads the duration of the steps, the kcal of a serving and the day of the last meal', async () => {
    renderList()
    await screen.findByText('Gratin dauphinois')

    const row = within(rowOf('Gratin dauphinois'))
    expect(row.getByText('1 h 40')).toBeInTheDocument()
    await waitFor(() => expect(row.getByText('300')).toBeInTheDocument())
    expect(row.getByText('24 oct.')).toBeInTheDocument()
    expect(row.getByText('4')).toBeInTheDocument()
  })

  test('shows a dash where a recipe has no duration, no kcal or no meal', async () => {
    renderList()
    await screen.findByText('Flan')

    await waitFor(() => expect(within(rowOf('Salade express')).getAllByText('—')).toHaveLength(3))
  })

  test('still lists the recipes when the facts cannot be read', async () => {
    renderList({ ok: false, status: 500 } as Response)

    expect(await screen.findByText('Gratin dauphinois')).toBeInTheDocument()
    expect(within(rowOf('Gratin dauphinois')).getByText('1 h 40')).toBeInTheDocument()
  })

  test('filters by tag from the pastilles, and « Toutes » gives everything back', async () => {
    const user = userEvent.setup()
    renderList()
    await screen.findByText('Gratin dauphinois')
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual(expect.arrayContaining(['Toutes', 'Végétarien', 'Rapide', 'Four']))

    await user.click(screen.getByRole('button', { name: 'Rapide' }))

    expect(screen.getByRole('button', { name: 'Rapide' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Salade express')).toBeInTheDocument()
    expect(screen.queryByText('Gratin dauphinois')).not.toBeInTheDocument()
    expect(screen.queryByText('Flan')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Toutes' }))

    expect(screen.getByText('Gratin dauphinois')).toBeInTheDocument()
    expect(screen.getByText('Flan')).toBeInTheDocument()
  })

  test('searches by name', async () => {
    const user = userEvent.setup()
    renderList()
    await screen.findByText('Gratin dauphinois')

    await user.type(screen.getByRole('searchbox', { name: 'Rechercher une recette' }), 'sala')

    expect(screen.getByText('Salade express')).toBeInTheDocument()
    expect(screen.queryByText('Gratin dauphinois')).not.toBeInTheDocument()
  })

  test('offers « + Recette », which opens the creation', async () => {
    const user = userEvent.setup()
    renderList()
    await screen.findByText('Gratin dauphinois')

    await user.click(screen.getByRole('link', { name: '+ Recette' }))

    expect(await screen.findByText('Création')).toBeInTheDocument()
  })

  test('opens the sheet when a row is clicked', async () => {
    const user = userEvent.setup()
    renderList()
    await screen.findByText('Gratin dauphinois')

    await user.click(within(rowOf('Gratin dauphinois')).getByText('4'))

    expect(await screen.findByText('Fiche ouverte')).toBeInTheDocument()
  })

  test('previews no recipe until one is pointed at, then its ingredients and steps on the right', async () => {
    const user = userEvent.setup()
    renderList()
    await screen.findByText('Gratin dauphinois')
    const preview = screen.getByRole('complementary', { name: 'Aperçu de la recette' })
    expect(within(preview).queryByText('Pommes de terre')).not.toBeInTheDocument()

    await user.hover(rowOf('Gratin dauphinois'))

    expect(within(preview).getByRole('heading', { name: 'Gratin dauphinois' })).toBeInTheDocument()
    expect(within(preview).getByText('Pommes de terre')).toBeInTheDocument()
    expect(within(preview).getByText('Enfourner le gratin')).toBeInTheDocument()

    await user.hover(rowOf('Salade express'))

    expect(within(preview).getByRole('heading', { name: 'Salade express' })).toBeInTheDocument()
    expect(within(preview).queryByText('Pommes de terre')).not.toBeInTheDocument()
  })

  test('previews the recipe a row’s keyboard focus is on', async () => {
    const user = userEvent.setup()
    renderList()
    await screen.findByText('Gratin dauphinois')

    rowOf('Gratin dauphinois').focus()

    const preview = screen.getByRole('complementary', { name: 'Aperçu de la recette' })
    expect(await within(preview).findByRole('heading', { name: 'Gratin dauphinois' })).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(await screen.findByText('Fiche ouverte')).toBeInTheDocument()
  })
})

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { veilleuseDarkTheme } from '../../theme'
import { IngredientList } from './IngredientList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const COURGETTE = { id: '/api/ingredients/01COURG', name: 'Courgette', category: 'produce', ciqualAlimCode: '20020', kcalPer100g: 40, pieceWeightGrams: 100 }
const OIGNON = { id: '/api/ingredients/01OIGN', name: 'Oignon jaune', category: 'produce', ciqualAlimCode: null, kcalPer100g: null, pieceWeightGrams: null }
const MAISON = { id: '/api/ingredients/01MAIS', name: 'Pâté maison', category: 'other', ciqualAlimCode: null, kcalPer100g: 250, pieceWeightGrams: null }

const renderList = () => {
  const dataProvider = testDataProvider({
    getList: (() => Promise.resolve({ data: [COURGETTE, OIGNON, MAISON], total: 3 })) as unknown as DataProvider['getList'],
  })

  return render(
    <MemoryRouter initialEntries={['/ingredients']}>
      <AdminContext dataProvider={dataProvider} theme={veilleuseDarkTheme}>
        <ResourceContextProvider value="ingredients">
          <Routes>
            <Route path="/ingredients" element={<IngredientList />} />
            <Route path="/ingredients/create" element={<p>Création</p>} />
            <Route path="/ingredients/:id" element={<p>Fiche ouverte</p>} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
}

const rowOf = (name: string) => screen.getByRole('row', { name: new RegExp(name) })

describe('IngredientList — the table of the mockup (MAG-1044)', { timeout: 30_000 }, () => {
  beforeEach(() => localStorage.setItem('token', 'jwt'))
  afterEach(() => localStorage.clear())

  test('titles the page « Ingrédients », with the search and « + Ingrédient » on its row', async () => {
    renderList()
    await screen.findByText('Courgette')

    expect(screen.getByRole('heading', { level: 1, name: 'Ingrédients' })).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: 'Rechercher un ingrédient' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '+ Ingrédient' })).toHaveAttribute('href', expect.stringContaining('/ingredients/create'))
  })

  test('reads the pastilles with their counts, « Tous » pressed', async () => {
    renderList()
    await screen.findByText('Courgette')

    expect(screen.getByRole('button', { name: 'Tous 3' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Sans valeurs 1' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'Ciqual 1' })).toBeInTheDocument()
  })

  test('the columns are Ingrédient, Valeurs, Énergie / 100 g, Poids par pièce', async () => {
    renderList()
    await screen.findByText('Courgette')

    expect(screen.getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Ingrédient',
      'Valeurs',
      'Énergie / 100 g',
      'Poids par pièce',
    ])
  })

  test('a Ciqual line reads Ciqual, 40 kcal, ≈ 100 g', async () => {
    renderList()
    await screen.findByText('Courgette')

    const cells = within(rowOf('Courgette')).getAllByRole('cell').map((cell) => cell.textContent)
    expect(cells).toEqual(['Courgette', 'Ciqual', '40 kcal', '≈ 100 g'])
  })

  test('a line with no values reads Sans valeurs and dashes', async () => {
    renderList()
    await screen.findByText('Courgette')

    const cells = within(rowOf('Oignon jaune')).getAllByRole('cell').map((cell) => cell.textContent)
    expect(cells).toEqual(['Oignon jaune', 'Sans valeurs', '—', '—'])
  })

  test('« Sans valeurs » keeps only that line and is pressed', async () => {
    renderList()
    await screen.findByText('Courgette')

    await userEvent.click(screen.getByRole('button', { name: /Sans valeurs/ }))

    expect(screen.getByRole('button', { name: /Sans valeurs/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Oignon jaune')).toBeInTheDocument()
    expect(screen.queryByText('Courgette')).not.toBeInTheDocument()
    expect(screen.queryByText('Pâté maison')).not.toBeInTheDocument()
  })

  test('« Ciqual » keeps only the line with a Ciqual code', async () => {
    renderList()
    await screen.findByText('Courgette')

    await userEvent.click(screen.getByRole('button', { name: /Ciqual/ }))

    expect(screen.getByText('Courgette')).toBeInTheDocument()
    expect(screen.queryByText('Oignon jaune')).not.toBeInTheDocument()
  })

  test('the search keeps « Oignon jaune » for « oignon »', async () => {
    renderList()
    await screen.findByText('Courgette')

    await userEvent.type(screen.getByRole('searchbox', { name: 'Rechercher un ingrédient' }), 'oignon')

    expect(screen.getByText('Oignon jaune')).toBeInTheDocument()
    expect(screen.queryByText('Courgette')).not.toBeInTheDocument()
  })

  test('a search with no match says so', async () => {
    renderList()
    await screen.findByText('Courgette')

    await userEvent.type(screen.getByRole('searchbox', { name: 'Rechercher un ingrédient' }), 'zzz')

    expect(screen.getByText('Aucun ingrédient ne correspond.')).toBeInTheDocument()
  })

  test('no line carries an edit or delete button', async () => {
    renderList()
    await screen.findByText('Courgette')

    expect(screen.queryByRole('button', { name: /modifier|supprimer|edit|delete/i })).not.toBeInTheDocument()
  })

  test('a click on a line opens its edit page', async () => {
    renderList()
    await screen.findByText('Courgette')

    await userEvent.click(rowOf('Courgette'))

    expect(await screen.findByText('Fiche ouverte')).toBeInTheDocument()
  })

  test('the active pastille wears the Cuisine apricot', async () => {
    renderList()
    await screen.findByText('Courgette')

    expect(screen.getByRole('button', { name: 'Tous 3' })).toHaveStyle({
      color: veilleuseDarkTheme.palette.module.cuisine,
      height: '36px',
    })
  })
})

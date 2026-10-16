import { describe, test, expect, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { CategoryCreate } from './CategoryCreate'
import { CategoryEdit } from './CategoryEdit'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const RENTE = {
  id: '/api/categories/1',
  name: 'Loyers perçus',
  obligation: 'income',
  passiveIncome: true,
}

/** The create screen, and the `create` the data provider received. */
const renderCreate = () => {
  const create = vi.fn().mockResolvedValue({ data: { id: '/api/categories/1' } })
  // The parent picker asks for the list; without it the harness logs the
  // unimplemented call on every render.
  const getList = vi.fn().mockResolvedValue({ data: [], total: 0 })
  render(
    <MemoryRouter>
      <AdminContext
        dataProvider={testDataProvider({ create, getList })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="categories">
          <CategoryCreate />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

  return create
}

/** The edit screen of a category already declared a rente. */
const renderEdit = (record: Record<string, unknown> = RENTE) => {
  // The id in the route is the record's own — an IRI, as the API Platform data
  // provider gives them — and it has to match what `getOne` returns.
  render(
    <MemoryRouter initialEntries={[`/categories/${encodeURIComponent(RENTE.id)}`]}>
      <AdminContext
        dataProvider={testDataProvider({
          getOne: (() => Promise.resolve({ data: record })) as unknown as DataProvider['getOne'],
          getList: (() => Promise.resolve({ data: [], total: 0 })) as unknown as DataProvider['getList'],
        })}
        i18nProvider={i18nProvider}
      >
        <ResourceContextProvider value="categories">
          {/* Through a route: `useEditController` reads the record's id from
              the `:id` parameter, and there is no id prop to give it. */}
          <Routes>
            <Route path="/categories/:id" element={<CategoryEdit />} />
          </Routes>
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
}

describe('CategoryForm', () => {
  /** MAG-479: a category has no nature any more — the qualification is chosen on the operation. */
  test('offers no nature or obligation field, but the rente box', async () => {
    renderCreate()

    expect(await screen.findByLabelText(/Nom/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/Obligation/)).not.toBeInTheDocument()
    expect(screen.queryByText('Nature de la dépense')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Rente')).toBeInTheDocument()
    expect(screen.getByText(/compteur d'indépendance compare à votre train de vie/)).toBeInTheDocument()
  })

  test('a category without a rente is sent with no obligation', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Courses')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({ name: 'Courses' })
    expect(create.mock.calls[0][1].data).not.toHaveProperty('obligation')
  })

  test('a rente is what the API is sent, tied to the recette flag it still needs', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Loyers perçus')
    await user.click(await screen.findByLabelText('Rente'))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({
      name: 'Loyers perçus',
      obligation: 'income',
      passiveIncome: true,
    })
  })

  /** MAG-353 recette: the colour is picked, not typed as a hex code. */
  test('the colour is picked from a palette and is what the API is sent', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    expect(screen.queryByRole('textbox', { name: /Couleur/ })).not.toBeInTheDocument()

    await user.type(await screen.findByLabelText(/Nom/), 'Courses')
    await user.click(screen.getByRole('button', { name: 'Couleur #4CAF50' }))
    expect(screen.getByRole('button', { name: 'Couleur #4CAF50' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({ name: 'Courses', color: '#4CAF50' })
  })

  test('another colour comes from the browser colour picker', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Courses')
    fireEvent.change(screen.getByLabelText('Autre couleur'), { target: { value: '#123456' } })
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({ color: '#123456' })
  })

  test('the colour can be taken away again', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Courses')
    await user.click(screen.getByRole('button', { name: 'Couleur #4CAF50' }))
    await user.click(screen.getByRole('button', { name: 'Aucune couleur' }))
    expect(screen.getByRole('button', { name: 'Aucune couleur' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data.color ?? null).toBeNull()
  })

  test('an existing colour is shown as chosen, even outside the palette', async () => {
    renderEdit({ ...RENTE, color: '#123456' })

    expect(await screen.findByRole('button', { name: 'Couleur #123456' })).toHaveAttribute('aria-pressed', 'true')
  })

  /** The edit screen opens on a record that is already a rente: the flag stays. */
  test('an existing rente keeps its flag through an edit', async () => {
    const user = userEvent.setup()
    renderEdit()

    expect(await screen.findByLabelText('Rente')).toBeChecked()

    await user.type(screen.getByLabelText(/Nom/), ' et garages')

    expect(screen.getByLabelText('Rente')).toBeChecked()
  })
})

/** MAG-474: the icon is picked from a grid with a search field, not typed as a name. */
describe('CategoryForm, icon picker', () => {
  const openPicker = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByRole('button', { name: 'Icône : Aucune' }))

    return screen.findByRole('dialog', { name: 'Choisir une icône' })
  }

  test('the icon is no longer a text field', async () => {
    renderCreate()

    expect(await screen.findByRole('button', { name: 'Icône : Aucune' })).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /Icône/ })).not.toBeInTheDocument()
  })

  test('opens on a search field and the whole grid, each icon with its label', async () => {
    const user = userEvent.setup()
    renderCreate()

    const dialog = await openPicker(user)

    expect(within(dialog).getByLabelText('Rechercher une icône')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Aucune icône' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(dialog).getByRole('button', { name: 'Courses' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Voiture' })).toBeInTheDocument()
  })

  test('renders only the rows in view, not the two thousand icons', async () => {
    const user = userEvent.setup()
    renderCreate()
    const dialog = await openPicker(user)

    const tiles = within(dialog).getAllByRole('button', { pressed: false })
    expect(tiles.length).toBeGreaterThan(10)
    expect(tiles.length).toBeLessThan(120)
    expect(within(dialog).queryByRole('button', { name: 'Parking' })).not.toBeInTheDocument()
  })

  test('scrolling the grid brings the next rows in', async () => {
    const user = userEvent.setup()
    renderCreate()
    const dialog = await openPicker(user)
    const before = within(dialog).getAllByRole('button').map((b) => b.getAttribute('aria-label'))

    fireEvent.scroll(within(dialog).getByTestId('category-icon-grid'), { target: { scrollTop: 40000 } })

    const after = within(dialog).getAllByRole('button').map((b) => b.getAttribute('aria-label'))
    expect(after).not.toEqual(before)
    expect(after).not.toContain('Courses')
  })

  test('typing « chien » finds the pets icon', async () => {
    const user = userEvent.setup()
    renderCreate()
    const dialog = await openPicker(user)

    await user.type(within(dialog).getByLabelText('Rechercher une icône'), 'chien')

    expect(within(dialog).getByRole('button', { name: 'Animaux' })).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Voiture' })).not.toBeInTheDocument()
  })

  test('typing « voiture » puts the car first', async () => {
    const user = userEvent.setup()
    renderCreate()
    const dialog = await openPicker(user)

    await user.type(within(dialog).getByLabelText('Rechercher une icône'), 'Voiture')

    const labels = within(dialog).getAllByRole('button', { pressed: false }).map((b) => b.getAttribute('aria-label'))
    expect(labels[0]).toBe('Voiture')
  })

  test('the search ignores accents and finds a keyword', async () => {
    const user = userEvent.setup()
    renderCreate()
    const dialog = await openPicker(user)

    await user.type(within(dialog).getByLabelText('Rechercher une icône'), 'supermarche')

    expect(within(dialog).getByRole('button', { name: 'Courses' })).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Voiture' })).not.toBeInTheDocument()
  })

  test('says so when nothing matches, and « Aucune icône » stays available', async () => {
    const user = userEvent.setup()
    renderCreate()
    const dialog = await openPicker(user)

    await user.type(within(dialog).getByLabelText('Rechercher une icône'), 'zzz')

    expect(within(dialog).getByText('Aucune icône ne correspond.')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Aucune icône' })).toBeInTheDocument()
  })

  test('choosing an icon closes the box, shows it on the button and sends its name', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Courses')
    const dialog = await openPicker(user)
    await user.type(within(dialog).getByLabelText('Rechercher une icône'), 'courses')
    await user.click(within(dialog).getByRole('button', { name: 'Courses' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const button = screen.getByRole('button', { name: 'Icône : Courses' })
    expect(within(button).getByTestId('category-glyph')).toHaveAttribute('data-icon', 'shopping-cart')

    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({ name: 'Courses', icon: 'shopping-cart' })
  })

  test('the preview takes the colour of the form', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(await screen.findByRole('button', { name: 'Couleur #4CAF50' }))

    const button = screen.getByRole('button', { name: 'Icône : Aucune' })
    expect(within(button).getByTestId('category-glyph')).toHaveAttribute('data-color', '#4CAF50')
  })

  test('the icon can be taken away again', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(await screen.findByLabelText(/Nom/), 'Courses')
    let dialog = await openPicker(user)
    await user.click(within(dialog).getByRole('button', { name: 'Courses' }))
    await user.click(await screen.findByRole('button', { name: 'Icône : Courses' }))
    dialog = await screen.findByRole('dialog', { name: 'Choisir une icône' })
    expect(within(dialog).getByRole('button', { name: 'Courses' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(within(dialog).getByRole('button', { name: 'Aucune icône' }))

    expect(await screen.findByRole('button', { name: 'Icône : Aucune' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data.icon ?? null).toBeNull()
  })

  test('closing the box without choosing changes nothing', async () => {
    const user = userEvent.setup()
    renderCreate()
    const dialog = await openPicker(user)

    await user.click(within(dialog).getByRole('button', { name: 'Annuler' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Icône : Aucune' })).toBeInTheDocument()
  })

  test('an icon outside the catalogue keeps its name, is listed first and selected', async () => {
    const user = userEvent.setup()
    renderEdit({ ...RENTE, icon: 'icone-disparue' })

    await user.click(await screen.findByRole('button', { name: 'Icône : icone-disparue' }))
    const dialog = await screen.findByRole('dialog', { name: 'Choisir une icône' })

    const tiles = within(dialog).getAllByRole('button', { name: /^(?!Annuler|Aucune icône)/ })
    expect(tiles[0]).toHaveAttribute('aria-label', 'icone-disparue')
    expect(tiles[0]).toHaveAttribute('aria-pressed', 'true')
  })
})

const TREE = [
  { id: '/api/categories/loisirs', name: 'Loisirs', path: 'Loisirs', depth: 1, parent: null, obligation: 'optional' },
  { id: '/api/categories/bars', name: 'Bars', path: 'Loisirs › Bars', depth: 2, parent: '/api/categories/loisirs', obligation: 'optional' },
  { id: '/api/categories/obsydian', name: 'Obsydian', path: 'Loisirs › Bars › Obsydian', depth: 3, parent: '/api/categories/bars', obligation: 'optional' },
  { id: '/api/categories/cinema', name: 'Cinéma', path: 'Loisirs › Cinéma', depth: 2, parent: '/api/categories/loisirs', obligation: 'optional' },
  { id: '/api/categories/courses', name: 'Courses', path: 'Courses', depth: 1, parent: null, obligation: 'mandatory' },
]

const treeList = (() => Promise.resolve({ data: TREE, total: TREE.length })) as unknown as DataProvider['getList']

/** The create screen over a three-level tree; failures of the API show in the notification area. */
const renderCreateInTree = (create = vi.fn().mockResolvedValue({ data: { id: '/api/categories/new' } })) => {
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider({ create, getList: treeList })} i18nProvider={i18nProvider}>
        <ResourceContextProvider value="categories">
          <CategoryCreate />
        </ResourceContextProvider>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

  return create
}

const parentOptions = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByLabelText('Rattacher à une catégorie'))

  return (await screen.findAllByRole('option')).map((option) => option.textContent)
}

describe('CategoryForm, three levels', () => {
  test('the parent is chosen by its path, and level 3 is not offered', async () => {
    const user = userEvent.setup()
    renderCreateInTree()

    expect(await parentOptions(user)).toEqual(['Courses', 'Loisirs', 'Loisirs › Bars', 'Loisirs › Cinéma'])
  })

  test('a category created under Loisirs › Bars is sent with that parent', async () => {
    const user = userEvent.setup()
    const create = renderCreateInTree()

    await user.type(await screen.findByLabelText(/Nom/), 'Obsydian')
    await parentOptions(user)
    await user.click(screen.getByRole('option', { name: 'Loisirs › Bars' }))
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][1].data).toMatchObject({ name: 'Obsydian', parent: '/api/categories/bars' })
  })

  test('editing Bars offers neither Bars, nor what sits under it, nor a place its subtree would not fit', async () => {
    const user = userEvent.setup()
    // Bars as a main category with its own child: it can only go under a level 1.
    const bars = { ...TREE[1], parent: null }
    render(
      <MemoryRouter initialEntries={[`/categories/${encodeURIComponent(bars.id)}`]}>
        <AdminContext
          dataProvider={testDataProvider({
            getOne: (() => Promise.resolve({ data: bars })) as unknown as DataProvider['getOne'],
            getList: treeList,
          })}
          i18nProvider={i18nProvider}
        >
          <ResourceContextProvider value="categories">
            <Routes>
              <Route path="/categories/:id" element={<CategoryEdit />} />
            </Routes>
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    expect(await parentOptions(user)).toEqual(['Courses', 'Loisirs'])
  })

  test('the reason the API refuses a category is shown, and the form stays open', async () => {
    const user = userEvent.setup()
    const create = renderCreateInTree(
      vi.fn().mockRejectedValue(Object.assign(new Error('Une catégorie ne peut pas dépasser trois niveaux.'), { status: 422 })),
    )

    await user.type(await screen.findByLabelText(/Nom/), 'Obsydian')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Une catégorie ne peut pas dépasser trois niveaux.')).toBeInTheDocument()
    expect(create).toHaveBeenCalled()
    expect(screen.getByLabelText(/Nom/)).toHaveValue('Obsydian')
  })
})

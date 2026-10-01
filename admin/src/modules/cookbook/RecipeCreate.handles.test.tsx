import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeCreate } from './RecipeCreate'

/**
 * The handles `e2e/web/tests/recipes-ciqual.spec.ts` reaches for, and the shape
 * the API is sent.
 *
 * A contract between this form and the recipe journey (MAG-101), in the same
 * spirit as `api/contract/`: the journey fills the Ciqual autocomplete and the
 * ingredient row by accessible name, and nothing in a normal component test
 * notices when one of those names moves — the journey would, several minutes
 * later, on a stack that takes minutes to start, with a failure reading "the
 * recipe form is broken" rather than "a label was renamed".
 *
 * The payload matters as much as the names. `CreateRecipeProcessor` reads
 * `ingredients[].ciqualAlimCode` out of the raw request body and
 * `IngredientFromCiqualResolver` turns it into an Ingredient; a form that sent
 * the food's *name*, or the code under another key, would be accepted with no
 * ingredients at all. The ticket says recipes and Ciqual have the worst
 * regressions-per-test-file ratio in the repository, and this is why.
 *
 * `RecipeCreate.test.tsx` owns the tags; this file owns the ingredient row and
 * stubs nothing but the network.
 */

// A real food, with its real code and name: the e2e journey picks this very
// option out of the service's own answer, so a made-up code here would be a
// third thing to keep in step with nothing.
const TOMATO = {
  alim_code: '20276',
  alim_name_fr: 'Tomate ronde, crue',
  alim_group_name_fr: 'fruits, légumes, légumineuses et oléagineux',
  kcal_per100g: 23.5,
  protein_per100g: 1.1,
  carbs_per100g: 3.4,
  fat_per100g: 0.2,
}

/** The Ciqual service, as the autocomplete calls it: `/ciqual/foods?q=…`. */
function stubCiqual(): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)

    if (url.includes('/ciqual/foods?')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve([TOMATO]) } as Response)
    }

    if (url.includes(`/ciqual/foods/${TOMATO.alim_code}`)) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve(TOMATO) } as Response)
    }

    return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) } as unknown as Response)
  })

  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

/**
 * The app's own translations, not react-admin's defaults.
 *
 * Without this, every label renders as its raw key — `ra.action.add` — and a
 * test asserting on them would pin nothing a browser ever shows. The journey
 * reads "Ajouter" and "Enregistrer", so this file has to render them.
 */
const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const renderCreate = (create = vi.fn().mockResolvedValue({ data: { id: '/api/recipes/1' } })) => {
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider({ create })} i18nProvider={i18nProvider}>
        <ResourceContextProvider value="recipes">
          <RecipeCreate />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

  return create
}

/**
 * The ingredient array's own "add a row" button.
 *
 * An icon button, so its accessible name comes from `aria-label` and there is
 * no text to read — which is why the journey has to address it by role and
 * name, and why that name is worth pinning here.
 */
function addRowButton(): HTMLElement {
  return screen.getByRole('button', { name: 'Ajouter' })
}

describe('RecipeCreate — the handles the recipe journey uses', { timeout: 30_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    stubCiqual()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('the form the journey fills is addressable by its labels', () => {
    renderCreate()

    expect(screen.getByLabelText(/Nom/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Portions/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Tags/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeInTheDocument()
    expect(addRowButton()).toBeInTheDocument()
  })

  test('an ingredient row carries the Ciqual autocomplete, a quantity and a unit', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(addRowButton())

    expect(screen.getByLabelText(/Aliment Ciqual/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Quantité/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Unité/)).toBeInTheDocument()
  })

  /**
   * Expected to fail, and it names its ticket rather than asserting the bug.
   *
   * `CiqualFoodAutocomplete` writes with `useFormContext().setValue(source)`,
   * and `source` is the bare string `ciqualAlimCode`. Inside a
   * `SimpleFormIterator`, react-admin 5 scopes a source through a
   * `SourceContext` (`ingredients.0.ciqualAlimCode`) that its own inputs
   * consult — a component calling `setValue` itself does not, so the code
   * lands at the *root* of the form and the row keeps a null:
   *
   *   { name: 'Sauce', ingredients: [{ ciqualAlimCode: null, quantity: 400,
   *     unit: null }], ciqualAlimCode: '20047' }
   *
   * `CreateRecipeHandler::resolveIngredient` then throws, so creating a recipe
   * with a Ciqual ingredient fails outright — the first bullet of MAG-101's own
   * description. Nobody saw it because `IngredientCreate` uses the same
   * component *outside* an array, where it works, and `RecipeCreate.test.tsx`
   * mocks it away to `() => null`.
   *
   * MAG-173 carries the fix; this is its reproduction, already written.
   */
  test.fails('picking a Ciqual food sends its code in the row — MAG-173', async () => {
    const user = userEvent.setup()
    const create = renderCreate()

    await user.type(screen.getByLabelText(/Nom/), 'Sauce tomate MAG-101')
    await user.click(addRowButton())

    // Two characters is the autocomplete's own threshold, and the debounce is
    // 300 ms — both are what the journey has to type and wait through.
    await user.type(screen.getByLabelText(/Aliment Ciqual/), 'tomate')

    const option = await screen.findByRole('option', { name: new RegExp(TOMATO.alim_name_fr, 'i') }, { timeout: 5_000 })
    await user.click(option)

    await user.type(screen.getByLabelText(/Quantité/), '400')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())

    const sent = create.mock.calls[0][1].data as {
      name: string
      ingredients?: Array<{ ciqualAlimCode?: string; quantity?: number }>
    }

    expect(sent.name).toBe('Sauce tomate MAG-101')
    // The key the API reads. `quantity` is the other half: the processor casts
    // it to a float and a row sent without one becomes 0 g of nothing.
    expect(sent.ingredients?.[0]?.ciqualAlimCode).toBe(TOMATO.alim_code)
    expect(sent.ingredients?.[0]?.quantity).toBe(400)
  })

  test('the autocomplete asks the Ciqual service and nothing else', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(addRowButton())
    await user.type(screen.getByLabelText(/Aliment Ciqual/), 'tomate')

    await waitFor(() => {
      const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.map((call) => String(call[0]))

      expect(calls.some((url) => url.includes('/ciqual/foods?q=tomate'))).toBe(true)
    })
  })

  test('a row the owner typed nothing into offers no option rather than a wrong one', async () => {
    const user = userEvent.setup()
    renderCreate()

    await user.click(addRowButton())
    await user.type(screen.getByLabelText(/Aliment Ciqual/), 't')

    // One character: below the threshold, so the service is never called and
    // the list says why. A journey typing too little would otherwise wait for
    // an option that is never coming.
    expect(within(document.body).getByText(/Tapez au moins 2 caractères/)).toBeInTheDocument()
  })
})

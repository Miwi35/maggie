import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { veilleuseDarkTheme } from '../../../theme'
import { CookingActivityPage } from './CookingActivityPage'

vi.mock('../../../components/chat/ChatWidget', () => ({ ChatWidget: () => null }))

const PASTA = { id: 'i-pasta', name: 'Pâtes' }
const VEG = { id: 'i-veg', name: 'Légumes' }
const POT = { id: 'u-pot', name: 'Casserole' }

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

/** The reference example of the Étapes project (MAG-492). */
const REFERENCE_STEPS = [
  step(1, "Mettre l'eau à bouillir", { durationMinutes: 10, background: true, utensils: [POT] }),
  step(2, 'Couper les légumes', { ingredients: [VEG] }),
  step(3, 'Cuire les pâtes', { durationMinutes: 12, background: true, ingredients: [PASTA], waitsFor: [1] }),
  step(4, 'Faire revenir les légumes', { durationMinutes: 8, ingredients: [VEG] }),
  step(5, 'Égoutter, mélanger', { waitsFor: [3, 4] }),
]

const recipeBody = (id: string, name: string, steps: unknown[], guests: number | null) => {
  const factor = (guests ?? 2) / 2

  return {
    id,
    name,
    servings: 2,
    ingredients: [
      { id: `${id}-l1`, ingredient: { id: PASTA.id, name: PASTA.name }, ingredientName: PASTA.name, quantity: 200 * factor, unit: 'g' },
      { id: `${id}-l2`, ingredient: { id: VEG.id, name: VEG.name }, ingredientName: VEG.name, quantity: 300 * factor, unit: 'g' },
    ],
    utensils: [POT],
    steps,
  }
}

const RECIPES: Record<string, { name: string; steps: unknown[] }> = {
  REF: { name: 'Pâtes aux légumes', steps: REFERENCE_STEPS },
  SALAD: { name: 'Salade', steps: [step(1, 'Laver la salade')] },
}

const fakeFetch = (input: RequestInfo | URL) => {
  const url = String(input)
  const respond = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))
  if (url === '/api/meals/MEAL') return respond({ id: 'MEAL', guests: 4, recipes: ['/api/recipes/REF', '/api/recipes/SALAD'] })
  const recipe = url.match(/^\/api\/recipes\/([^?]+)(?:\?guests=(\d+))?$/)
  if (recipe && RECIPES[recipe[1]]) {
    return respond(recipeBody(recipe[1], RECIPES[recipe[1]].name, RECIPES[recipe[1]].steps, recipe[2] ? Number(recipe[2]) : null))
  }

  return Promise.resolve(new Response('', { status: 404 }))
}

const renderActivity = (entry: string) =>
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/activites/cuisine" element={<CookingActivityPage />} />
          <Route path="*" element={<output data-testid="where" />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )

const startReference = async () => {
  renderActivity('/activites/cuisine?recipe=REF')
  await screen.findByText('Pour combien ?')
  await userEvent.click(screen.getByRole('button', { name: 'Continuer' }))
  await screen.findByTestId('cooking-preparation')
  await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
}

const stepText = () => screen.getByTestId('cooking-step-text').textContent

describe('The steps of the Cuisine activity (MAG-581)', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.stubGlobal('fetch', vi.fn(fakeFetch))
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, writable: true, value: () => Promise.reject(new Error('refused')) })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('a step shows its number, its text, its ingredients with their quantities, its utensils and what comes next', async () => {
    await startReference()

    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('1')
    expect(stepText()).toBe("Mettre l'eau à bouillir")
    expect(within(screen.getByTestId('cooking-step-needs')).getByText('Casserole')).toBeInTheDocument()
    expect(screen.getByTestId('cooking-upnext')).toHaveTextContent('Ensuite · Couper les légumes')

    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
    const ingredients = screen.getAllByTestId('cooking-step-ingredient')
    expect(ingredients).toHaveLength(1)
    expect(ingredients[0]).toHaveTextContent('300')
    expect(ingredients[0]).toHaveTextContent('Légumes')
  })

  test('the quantities follow the number of people of the session', async () => {
    renderActivity('/activites/cuisine?recipe=REF')
    await screen.findByText('Pour combien ?')
    await userEvent.click(screen.getByRole('button', { name: /Un de plus/ }))
    await userEvent.click(screen.getByRole('button', { name: /Un de plus/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    await screen.findByTestId('cooking-preparation')
    await waitFor(() => expect(screen.getByRole('button', { name: "C'est parti" })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))

    await waitFor(() => expect(screen.getByTestId('cooking-step-ingredient')).toHaveTextContent('600'))
  })

  test('the segments tell done, current and upcoming steps', async () => {
    await startReference()
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }).closest('footer')!.querySelector('button:last-child') as HTMLElement)

    const states = screen.getAllByTestId('cooking-segment').map((segment) => segment.getAttribute('data-state'))
    expect(states).toEqual(['done', 'done', 'current', 'upcoming', 'upcoming'])
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuetext', 'Étape 3 sur 5')
  })

  test('step 3 waits for the water; « C\'est prêt » lifts it', async () => {
    await startReference()
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))

    expect(screen.getByTestId('cooking-wait')).toHaveTextContent("Attend l'eau à bouillir · étape 1")
    expect(screen.getByRole('button', { name: /Suivant/ })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: "C'est prêt" }))

    expect(screen.queryByTestId('cooking-wait')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('4')
  })

  test('step 5 waits for the pasta and the vegetables, and « C\'est prêt » forces both', async () => {
    await startReference()
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    await userEvent.click(screen.getByRole('button', { name: "C'est prêt" }))
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')

    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('5')
    expect(screen.getByTestId('cooking-wait')).toHaveTextContent('Attend les pâtes et les légumes · étapes 3 et 4')

    await userEvent.click(screen.getByRole('button', { name: "C'est prêt" }))
    expect(screen.queryByTestId('cooking-wait')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Repas servi' })).toBeEnabled()
  })

  test('→ and Espace go on, ← goes back, and a held step ignores them', async () => {
    await startReference()
    expect(stepText()).toBe("Mettre l'eau à bouillir")

    await userEvent.keyboard('{ArrowRight}')
    expect(stepText()).toBe('Couper les légumes')
    await userEvent.keyboard(' ')
    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('3')

    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('3')

    await userEvent.keyboard('{ArrowLeft}')
    expect(stepText()).toBe('Couper les légumes')
  })

  test('the keys also start the recipe from the preparation, and ← brings the first step back to it', async () => {
    renderActivity('/activites/cuisine?meal=MEAL')
    await screen.findByTestId('cooking-preparation')

    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('1')

    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByTestId('cooking-preparation')).toBeInTheDocument()
  })

  test('the keys leave a field alone', async () => {
    await startReference()
    const field = document.createElement('input')
    document.body.appendChild(field)
    field.focus()

    await userEvent.keyboard('{ArrowRight} ')

    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('1')
    field.remove()
  })

  test('Espace on a focused button presses that button, not « Suivant »', async () => {
    await startReference()
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    const ready = screen.getByRole('button', { name: "C'est prêt" })
    ready.focus()

    await userEvent.keyboard(' ')

    expect(screen.queryByTestId('cooking-wait')).not.toBeInTheDocument()
    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('3')
  })

  test('the focus is visible on the buttons of a step', async () => {
    await startReference()

    await userEvent.tab()

    const focused = document.activeElement as HTMLElement
    expect(focused.tagName).toBe('BUTTON')
    expect(getComputedStyle(focused).outlineStyle).not.toBe('none')
  })

  test('a step fades in over 560 ms, and each step restarts the fade', async () => {
    await startReference()
    const first = screen.getByTestId('cooking-step-content')
    expect(document.head.innerHTML).toMatch(/560ms/)

    await userEvent.keyboard('{ArrowRight}')

    expect(screen.getByTestId('cooking-step-content')).not.toBe(first)
  })

  test('the last step of the last recipe reads « Repas servi » and opens « Bon appétit » with the time and the guests', async () => {
    renderActivity('/activites/cuisine?meal=MEAL')
    await screen.findByTestId('cooking-preparation')

    // The reference recipe: five steps, two held.
    await userEvent.keyboard('{ArrowRight}{ArrowRight}{ArrowRight}')
    await userEvent.click(screen.getByRole('button', { name: "C'est prêt" }))
    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    await userEvent.click(screen.getByRole('button', { name: "C'est prêt" }))
    await userEvent.keyboard('{ArrowRight}')

    // The meal keeps no order among its recipes: whichever came first, the other follows.
    for (let recipe = 0; recipe < 2; recipe++) {
      await waitFor(() => expect(screen.queryByTestId('cooking-preparation') ?? screen.queryByTestId('cooking-step')).toBeInTheDocument())
      if (screen.queryByTestId('cooking-step')) break
    }
    expect(screen.getByTestId('cooking-preparation')).toHaveTextContent('Salade')
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('button', { name: 'Repas servi' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Repas servi' }))

    expect(await screen.findByText('Bon appétit')).toBeInTheDocument()
    expect(screen.getByTestId('cooking-done-summary')).toHaveTextContent('1 min en cuisine · 4 convives')
  })

  test('the step survives a reload', async () => {
    const first = renderActivity('/activites/cuisine?recipe=REF')
    await screen.findByText('Pour combien ?')
    await userEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    await screen.findByTestId('cooking-preparation')
    await userEvent.keyboard('{ArrowRight}{ArrowRight}{ArrowRight}')
    await userEvent.click(screen.getByRole('button', { name: "C'est prêt" }))
    first.unmount()

    renderActivity('/activites/cuisine?recipe=REF')

    expect(await screen.findByTestId('cooking-step-number')).toHaveTextContent('3')
    expect(screen.queryByTestId('cooking-wait')).not.toBeInTheDocument()
  })
})

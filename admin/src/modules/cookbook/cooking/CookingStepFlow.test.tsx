import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { veilleuseDarkTheme } from '../../../theme'
import { CookingActivityPage } from './CookingActivityPage'
import { fakeStatusApi } from './fakeStatusApi'
import type { FakeStatusApi } from './fakeStatusApi'
import { fakeTimersApi } from './fakeTimersApi'

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

// The steps in the background start their timer (MAG-582): the API keeps them.
let timersApi = fakeTimersApi()
let statusApi: FakeStatusApi = fakeStatusApi()

const fakeFetch = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input)
  const saved = statusApi.handle(url, init)
  if (saved) return saved
  const timers = timersApi.handle(url, init)
  if (timers) return timers
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

const rows = () => screen.getAllByTestId('cooking-step-row')
const rowOf = (position: number) => rows().find((row) => row.dataset.position === String(position))!
const progress = () => screen.getByTestId('cooking-steps-progress').textContent

const stepText = () => screen.getByTestId('cooking-step-text').textContent
const stepNumber = () => screen.getByTestId('cooking-step-number').textContent

/** → once per step: a step in the background posts its timer first, so each move is awaited. */
const pressNext = async (times = 1) => {
  for (let count = 0; count < times; count++) {
    const from = stepNumber()
    await userEvent.keyboard('{ArrowRight}')
    await waitFor(() => expect(stepNumber()).not.toBe(from))
  }
}

describe('The steps of the Cuisine activity (MAG-581)', () => {
  beforeEach(() => {
    sessionStorage.clear()
    timersApi = fakeTimersApi()
    statusApi = fakeStatusApi()
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

    await userEvent.click(screen.getByRole('button', { name: 'Lancer et continuer' }))
    await waitFor(() => expect(stepText()).toBe('Couper les légumes'))
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
    await userEvent.click(screen.getByRole('button', { name: 'Lancer et continuer' }))

    await waitFor(() => expect(screen.getByTestId('cooking-step-ingredient')).toHaveTextContent('600'))
  })

  test('the column lists every step: ticked and faded, current in front, the others neutral, with « n / m faites »', async () => {
    await startReference()
    expect(progress()).toBe('0 / 5 faites')
    expect(rows().map((row) => row.dataset.state)).toEqual(['current', 'upcoming', 'upcoming', 'upcoming', 'upcoming'])

    // Step 1 is in the background: it launches its timer and ticks nothing; step 2 is ticked by « Suivant ».
    await pressNext(2)

    expect(rows().map((row) => row.dataset.state)).toEqual(['upcoming', 'done', 'current', 'upcoming', 'upcoming'])
    expect(rowOf(3)).toHaveAttribute('aria-current', 'step')
    expect(rowOf(2)).toHaveAttribute('data-done', 'true')
    expect(progress()).toBe('1 / 5 faites')
    expect(screen.getByRole('progressbar', { name: 'Avancement des étapes' })).toHaveAttribute('aria-valuenow', '20')
  })

  test('« Lancer et continuer » starts the timer and moves on without ticking the step', async () => {
    await startReference()

    await userEvent.click(screen.getByRole('button', { name: 'Lancer et continuer' }))

    await waitFor(() => expect(stepNumber()).toBe('2'))
    expect(rowOf(1)).toHaveAttribute('data-done', 'false')
    expect(progress()).toBe('0 / 5 faites')
  })

  test('« Suivant » ticks the step it leaves', async () => {
    await startReference()
    await pressNext(1)

    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))

    await waitFor(() => expect(stepNumber()).toBe('3'))
    expect(rowOf(2)).toHaveAttribute('data-done', 'true')
    expect(rowOf(1)).toHaveAttribute('data-done', 'false')
  })

  test('touching a step goes there without ticking it, then « Suivant » ticks it', async () => {
    await startReference()

    await userEvent.click(rowOf(5))

    expect(stepNumber()).toBe('5')
    expect(stepText()).toBe('Égoutter, mélanger')
    expect(rowOf(5)).toHaveAttribute('data-state', 'current')
    expect(rowOf(5)).toHaveAttribute('data-done', 'false')
    expect(progress()).toBe('0 / 5 faites')

    await userEvent.click(rowOf(2))
    expect(stepNumber()).toBe('2')
    await userEvent.click(rowOf(5))
    await userEvent.click(screen.getByRole('button', { name: 'Repas servi' }))
    await screen.findByText('Bon appétit')
  })

  test('no « Suivant » is ever disabled: the steps do not wait for one another', async () => {
    await startReference()

    for (const position of [1, 2, 3, 4, 5]) {
      await userEvent.click(rowOf(position))
      expect(screen.getByRole('button', { name: /Suivant|Lancer et continuer|Repas servi/ })).toBeEnabled()
    }
    expect(screen.queryByTestId('cooking-wait')).not.toBeInTheDocument()
    expect(screen.queryByText("C'est prêt")).not.toBeInTheDocument()
    expect(screen.queryByText(/Attend /)).not.toBeInTheDocument()
  })

  test('on a phone-sized window the list folds behind « Voir les étapes »', async () => {
    await startReference()

    expect(screen.getByTestId('cooking-steps-toggle')).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(screen.getByTestId('cooking-steps-toggle'))
    expect(screen.getByTestId('cooking-steps-toggle')).toHaveAttribute('aria-expanded', 'true')
  })

  test('→ and Espace go on, ← goes back', async () => {
    await startReference()
    expect(stepText()).toBe("Mettre l'eau à bouillir")

    await pressNext()
    expect(stepText()).toBe('Couper les légumes')
    await userEvent.keyboard(' ')
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

  test('Espace on a focused row presses that row, not « Suivant »', async () => {
    await startReference()
    await pressNext(2)
    rowOf(1).focus()

    await userEvent.keyboard(' ')

    expect(screen.getByTestId('cooking-step-number')).toHaveTextContent('1')
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

    await pressNext()

    expect(screen.getByTestId('cooking-step-content')).not.toBe(first)
  })

  test('the last step of the last recipe reads « Repas servi » and opens « Bon appétit » with the time and the guests', async () => {
    renderActivity('/activites/cuisine?meal=MEAL')
    await screen.findByTestId('cooking-preparation')

    // The reference recipe: five steps.
    await userEvent.keyboard('{ArrowRight}')
    await pressNext(4)
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

  test('the step and the ticked steps survive a reload', async () => {
    const first = renderActivity('/activites/cuisine?recipe=REF')
    await screen.findByText('Pour combien ?')
    await userEvent.click(screen.getByRole('button', { name: 'Continuer' }))
    await screen.findByTestId('cooking-preparation')
    await userEvent.keyboard('{ArrowRight}')
    await pressNext(2)
    first.unmount()

    renderActivity('/activites/cuisine?recipe=REF')

    expect(await screen.findByTestId('cooking-step-number')).toHaveTextContent('3')
    expect(rows().map((row) => row.dataset.done)).toEqual(['false', 'true', 'false', 'false', 'false'])
    expect(progress()).toBe('1 / 5 faites')
  })
})

describe('The column of a meal with two recipes', () => {
  beforeEach(() => {
    sessionStorage.clear()
    timersApi = fakeTimersApi()
    statusApi = fakeStatusApi()
    vi.stubGlobal('fetch', vi.fn(fakeFetch))
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, writable: true, value: () => Promise.reject(new Error('refused')) })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('lists the steps recipe after recipe, counts them all, and a touch jumps to the other recipe without ticking', async () => {
    renderActivity('/activites/cuisine?meal=MEAL')
    await screen.findByTestId('cooking-preparation')
    await userEvent.keyboard('{ArrowRight}')
    await screen.findByTestId('cooking-steps-column')

    await waitFor(() => expect(progress()).toBe('0 / 6 faites'))
    const column = screen.getByTestId('cooking-steps-column')
    expect(within(column).getByRole('heading', { name: 'Pâtes aux légumes' })).toBeInTheDocument()
    expect(within(column).getByRole('heading', { name: 'Salade' })).toBeInTheDocument()
    expect(rows()).toHaveLength(6)

    const salad = within(within(column).getByRole('region', { name: 'Salade' })).getByTestId('cooking-step-row')
    await userEvent.click(salad)

    expect(stepText()).toBe('Laver la salade')
    expect(salad).toHaveAttribute('data-state', 'current')
    expect(progress()).toBe('0 / 6 faites')
  })
})

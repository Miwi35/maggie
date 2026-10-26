import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { veilleuseDarkTheme } from '../../../theme'
import { CookingActivityPage } from './CookingActivityPage'
import { fakeStatusApi } from './fakeStatusApi'
import type { FakeStatusApi } from './fakeStatusApi'
import { fakeTimersApi } from './fakeTimersApi'
import type { FakeTimersApi } from './fakeTimersApi'

vi.mock('../../../components/chat/ChatWidget', () => ({ ChatWidget: () => null }))

const step = (position: number, text: string) => ({ id: `s${position}`, position, text, durationMinutes: null, background: false, ingredients: [], utensils: [], waitsFor: [] })

/** A recipe the way the API serves it: quantities for `guests`, from a base of 400 g for 4. */
const recipeBody = (id: string, name: string, servings: number, guests: number | null, steps: ReturnType<typeof step>[]) => ({
  id,
  name,
  servings,
  ingredients: [{ id: `${id}-l1`, ingredientName: 'Farine', quantity: (400 * (guests ?? servings)) / 4, unit: 'g' }],
  utensils: [{ id: 'u1', name: 'Fouet' }],
  steps,
})

const RECIPES: Record<string, { name: string; servings: number; steps: ReturnType<typeof step>[] }> = {
  A: { name: 'Lasagnes', servings: 4, steps: [step(1, 'Faire la sauce'), step(2, 'Monter le plat')] },
  B: { name: 'Salade verte', servings: 4, steps: [step(1, 'Laver la salade')] },
  C: { name: 'Tiramisu', servings: 4, steps: [step(1, 'Monter les blancs')] },
  EMPTY: { name: 'Pain perdu', servings: 4, steps: [] },
}

const MEALS: Record<string, { guests: number; recipes: string[] }> = {
  M3: { guests: 4, recipes: ['A', 'B', 'C'] },
  M0: { guests: 2, recipes: [] },
  MS: { guests: 2, recipes: ['EMPTY', 'B'] },
}

let failing = false
let status: FakeStatusApi
let timers: FakeTimersApi
const calls: string[] = []

const answer = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200 }))

const fakeFetch = (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input)
  calls.push(url)
  if (failing) return Promise.resolve(new Response('', { status: 503 }))
  const saved = status.handle(url, init)
  if (saved) return saved
  const running = timers.handle(url, init)
  if (running) return running
  const meal = url.match(/^\/api\/meals\/(.+)$/)
  if (meal) {
    const found = MEALS[meal[1]]

    return found ? answer({ id: meal[1], guests: found.guests, recipes: found.recipes.map((id) => `/api/recipes/${id}`) }) : Promise.resolve(new Response('', { status: 404 }))
  }
  const recipe = url.match(/^\/api\/recipes\/([^?]+)(?:\?guests=(\d+))?$/)
  if (recipe && RECIPES[recipe[1]]) {
    const found = RECIPES[recipe[1]]

    return answer(recipeBody(recipe[1], found.name, found.servings, recipe[2] ? Number(recipe[2]) : null, found.steps))
  }

  return Promise.resolve(new Response('', { status: 404 }))
}

const Where = () => {
  const location = useLocation()

  return <output data-testid="where">{`${location.pathname}${location.search}`}</output>
}

const renderActivity = (entry: string, from?: string) =>
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <MemoryRouter initialEntries={[{ pathname: entry.split('?')[0], search: entry.includes('?') ? `?${entry.split('?')[1]}` : '', state: from ? { from } : undefined }]}>
        <Routes>
          <Route path="/activites/cuisine" element={<CookingActivityPage />} />
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )

describe('CookingActivityPage', () => {
  beforeEach(() => {
    failing = false
    calls.length = 0
    status = fakeStatusApi()
    timers = fakeTimersApi()
    sessionStorage.clear()
    vi.stubGlobal('fetch', vi.fn(fakeFetch))
    // The browser refuses full screen: the activity covers the window.
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { configurable: true, writable: true, value: () => Promise.reject(new Error('refused')) })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('shows the loading state first', () => {
    renderActivity('/activites/cuisine?meal=M3')

    expect(screen.getByTestId('cooking-loading')).toBeInTheDocument()
  })

  test('a meal opens its preparation: guests, ingredients, utensils and the recipes that follow', async () => {
    renderActivity('/activites/cuisine?meal=M3')

    const preparation = await screen.findByTestId('cooking-preparation')
    expect(within(preparation).getByText('On sort tout')).toBeInTheDocument()
    expect(screen.getByTestId('cooking-guests-value')).toHaveTextContent('4')
    expect(within(screen.getByTestId('cooking-ingredients')).getByText('Farine')).toBeInTheDocument()
    expect(within(screen.getByTestId('cooking-utensils')).getByText('Fouet')).toBeInTheDocument()

    const queue = screen.getAllByTestId('cooking-queue-item')
    expect(queue).toHaveLength(3)
    expect(queue[0]).toHaveTextContent('1')
    expect(queue[0]).toHaveTextContent('Lasagnes')
    expect(queue[0]).toHaveAttribute('data-current', 'true')
    await waitFor(() => expect(queue[1]).toHaveTextContent('Salade verte'))
    expect(queue[2]).toHaveTextContent('Tiramisu')
    expect(queue[1]).toHaveAttribute('data-current', 'false')
    expect(screen.getAllByTestId('cooking-queue-servings')[0]).toHaveTextContent('4 pers.')
  })

  test('a meal does not ask « Pour combien ? »', async () => {
    renderActivity('/activites/cuisine?meal=M3')
    await screen.findByTestId('cooking-preparation')

    expect(screen.queryByText('Pour combien ?')).not.toBeInTheDocument()
  })

  test('a recipe asks « Pour combien ? » filled with its servings, and 4 → 2 halves the quantities', async () => {
    renderActivity('/activites/cuisine?recipe=A')

    expect(await screen.findByText('Pour combien ?')).toBeInTheDocument()
    expect(screen.getByTestId('cooking-guests-value')).toHaveTextContent('4')

    await userEvent.click(screen.getByRole('button', { name: /Un de moins/ }))
    await userEvent.click(screen.getByRole('button', { name: /Un de moins/ }))
    expect(screen.getByTestId('cooking-guests-value')).toHaveTextContent('2')
    await userEvent.click(screen.getByRole('button', { name: 'Continuer' }))

    const ingredients = await screen.findByTestId('cooking-ingredients')
    await waitFor(() => expect(within(ingredients).getByTestId('cooking-ingredient')).toHaveTextContent('200'))
    expect(calls).toContain('/api/recipes/A?guests=2')
  })

  test('the number of people never goes under one', async () => {
    renderActivity('/activites/cuisine?recipe=A')
    await screen.findByText('Pour combien ?')

    for (let i = 0; i < 6; i++) {
      const minus = screen.getByRole('button', { name: /Un de moins/ })
      if (!(minus as HTMLButtonElement).disabled) await userEvent.click(minus)
    }

    expect(screen.getByTestId('cooking-guests-value')).toHaveTextContent('1')
    expect(screen.getByRole('button', { name: /Un de moins/ })).toBeDisabled()
  })

  test('the guests of the preparation change the quantities', async () => {
    renderActivity('/activites/cuisine?meal=M3')
    await screen.findByTestId('cooking-preparation')

    await userEvent.click(screen.getByRole('button', { name: /Un de plus/ }))

    expect(screen.getByTestId('cooking-guests-value')).toHaveTextContent('5')
    await waitFor(() => expect(screen.getByTestId('cooking-ingredient')).toHaveTextContent('500'))
  })

  test('« C\'est parti » opens step 1, then Précédent and Suivant walk the steps', async () => {
    renderActivity('/activites/cuisine?meal=M3')
    await screen.findByTestId('cooking-preparation')

    await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
    expect(screen.getByTestId('cooking-step-text')).toHaveTextContent('Faire la sauce')
    expect(screen.getByTestId('cooking-step-count')).toHaveTextContent('Étape 1 sur 2')

    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
    expect(screen.getByTestId('cooking-step-text')).toHaveTextContent('Monter le plat')

    await userEvent.click(screen.getByRole('button', { name: /Précédent/ }))
    expect(screen.getByTestId('cooking-step-text')).toHaveTextContent('Faire la sauce')

    await userEvent.click(screen.getByRole('button', { name: /Précédent/ }))
    expect(screen.getByTestId('cooking-preparation')).toBeInTheDocument()
  })

  test('three recipes follow one another in order, then « Bon appétit » and back to the origin', async () => {
    renderActivity('/activites/cuisine?meal=M3', '/')

    const seen: string[] = []
    await screen.findByTestId('cooking-preparation')
    for (let recipe = 0; recipe < 3; recipe++) {
      await waitFor(() => expect(screen.getByTestId('cooking-preparation')).toBeInTheDocument())
      seen.push(screen.getByTestId('cooking-recipe-name').textContent ?? '')
      await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
      while (!screen.queryByTestId('cooking-preparation') && !screen.queryByTestId('cooking-done')) {
        const next = screen.getByRole('button', { name: /Suivant|Repas servi/ })
        await userEvent.click(next)
      }
    }

    expect(seen).toEqual(['Lasagnes', 'Salade verte', 'Tiramisu'])
    expect(await screen.findByText('Bon appétit')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Terminer' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/')
    expect(sessionStorage.getItem('maggie.cooking-session')).toBeNull()
  })

  test('a reload resumes the same recipe, step and guests', async () => {
    const first = renderActivity('/activites/cuisine?meal=M3')
    await screen.findByTestId('cooking-preparation')
    await userEvent.click(screen.getByRole('button', { name: /Un de plus/ }))
    await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
    await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
    first.unmount()

    renderActivity('/activites/cuisine?meal=M3')

    expect(await screen.findByTestId('cooking-step-text')).toHaveTextContent('Monter le plat')
    expect(JSON.parse(sessionStorage.getItem('maggie.cooking-session') ?? '{}')).toMatchObject({ guests: 5, step: 2, recipeIndex: 0 })
  })

  test('a recipe without a step says so and lets the owner go on', async () => {
    renderActivity('/activites/cuisine?meal=MS')
    await screen.findByTestId('cooking-preparation')

    expect(await screen.findByTestId('cooking-no-steps')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Recette suivante' }))

    await waitFor(() => expect(screen.getByTestId('cooking-recipe-name')).toHaveTextContent('Salade verte'))
  })

  test('a meal without a recipe says so, and Retour leaves', async () => {
    renderActivity('/activites/cuisine?meal=M0', '/')

    expect(await screen.findByText('Aucune recette à cuisiner')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retour' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/')
  })

  test('a network error offers « Réessayer » and does not close the activity', async () => {
    failing = true
    renderActivity('/activites/cuisine?meal=M3')

    expect(await screen.findByText(/n'a pas pu être chargée/)).toBeInTheDocument()
    expect(screen.queryByTestId('where')).not.toBeInTheDocument()

    failing = false
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByTestId('cooking-preparation')).toBeInTheDocument()
  })

  test('with no meal nor recipe in the address, nothing to cook', async () => {
    renderActivity('/activites/cuisine')

    expect(await screen.findByText('Aucune recette à cuisiner')).toBeInTheDocument()
  })

  test('the ✕ goes back where the activity was launched from, Échap too, without a confirmation', async () => {
    renderActivity('/activites/cuisine?meal=M3', '/meals')
    await screen.findByTestId('cooking-preparation')

    await userEvent.keyboard('{Escape}')

    expect(await screen.findByTestId('where')).toHaveTextContent('/meals')
    expect(screen.queryByText("Quitter l'activité ?")).not.toBeInTheDocument()
  })

  test('opened by its address, quitting goes to the meals', async () => {
    renderActivity('/activites/cuisine?recipe=A')
    await screen.findByText('Pour combien ?')

    await userEvent.click(screen.getByRole('button', { name: 'Quitter Cuisine' }))

    expect(await screen.findByTestId('where')).toHaveTextContent('/meals')
  })

  test('the main buttons are at least 64 px', async () => {
    renderActivity('/activites/cuisine?meal=M3')
    await screen.findByTestId('cooking-preparation')

    expect(getComputedStyle(screen.getByRole('button', { name: "C'est parti" })).minHeight).toBe('64px')
  })

  describe('the status of the activity (MAG-615)', () => {
    const mealState = (overrides: Record<string, unknown> = {}) => ({
      origin: { kind: 'meal' as const, id: 'M3' },
      state: {
        queue: [
          { recipeId: 'A', servings: 5 },
          { recipeId: 'B', servings: 5 },
          { recipeId: 'C', servings: 5 },
        ],
        recipeIndex: 0,
        step: 2,
        doneSteps: { A: [1] },
        guests: 5,
        phase: 'cooking',
        startedAt: 1785000000000,
        ...overrides,
      },
    })

    test('a launch with a status of its own resumes at the step, ticked steps and guests included, on any device', async () => {
      status = fakeStatusApi(mealState())

      renderActivity('/activites/cuisine?meal=M3')

      expect(await screen.findByTestId('cooking-step-text')).toHaveTextContent('Monter le plat')
      expect(screen.getByTestId('cooking-steps-progress')).toHaveTextContent('1 / 4 faites')
      expect(status.deletes()).toHaveLength(0)
      expect(calls).not.toContain('/api/meals/M3')
    })

    test('the state is written at each change: guests, « C\'est parti », « Suivant »', async () => {
      renderActivity('/activites/cuisine?meal=M3')
      await screen.findByTestId('cooking-preparation')
      await userEvent.click(screen.getByRole('button', { name: /Un de plus/ }))
      await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
      await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))

      await waitFor(() => expect(status.current()?.state).toMatchObject({ guests: 5, step: 2, recipeIndex: 0, doneSteps: { A: [1] }, phase: 'cooking' }))
      expect(status.current()?.origin).toEqual({ kind: 'meal', id: 'M3' })
      expect(status.writes().length).toBeGreaterThanOrEqual(3)
    })

    test('quitting asks nothing, keeps the status and only forgets the tab\'s copy', async () => {
      renderActivity('/activites/cuisine?meal=M3', '/meals')
      await screen.findByTestId('cooking-preparation')
      await userEvent.click(screen.getByRole('button', { name: "C'est parti" }))
      await userEvent.click(screen.getByRole('button', { name: /Suivant/ }))
      await waitFor(() => expect(status.current()?.state).toMatchObject({ step: 2 }))

      await userEvent.click(screen.getByRole('button', { name: 'Quitter Cuisine' }))

      expect(await screen.findByTestId('where')).toHaveTextContent('/meals')
      expect(status.deletes()).toHaveLength(0)
      expect(status.current()?.state).toMatchObject({ step: 2 })
      expect(sessionStorage.getItem('maggie.cooking-session')).toBeNull()
    })

    test('« Repas servi » ends the activity: the status is erased', async () => {
      renderActivity('/activites/cuisine?meal=MS', '/')
      await screen.findByTestId('cooking-preparation')
      await userEvent.click(screen.getByRole('button', { name: 'Recette suivante' }))
      await userEvent.click(await screen.findByRole('button', { name: "C'est parti" }))
      await userEvent.click(await screen.findByRole('button', { name: /Repas servi/ }))

      expect(await screen.findByText('Bon appétit')).toBeInTheDocument()
      await waitFor(() => expect(status.deletes()).toHaveLength(1))
      expect(status.current()).toBeNull()
    })

    test('« Recommencer » erases the status, ticks nothing and goes back to the preparation', async () => {
      status = fakeStatusApi(mealState())
      renderActivity('/activites/cuisine?meal=M3')
      await screen.findByTestId('cooking-step-text')

      await userEvent.click(screen.getByTestId('cooking-restart'))

      expect(await screen.findByTestId('cooking-preparation')).toBeInTheDocument()
      await waitFor(() => expect(status.deletes()).toHaveLength(1))
      expect(status.current()).toBeNull()
    })

    test('another meal while one is in progress asks: nothing is lost silently', async () => {
      status = fakeStatusApi(mealState())

      renderActivity('/activites/cuisine?recipe=B', '/')

      expect(await screen.findByText('Une cuisine est déjà en cours')).toBeInTheDocument()
      expect(screen.queryByTestId('cooking-preparation')).not.toBeInTheDocument()
      expect(status.deletes()).toHaveLength(0)
      await waitFor(() => expect(screen.getByTestId('cooking-conflict-where')).toHaveTextContent('Lasagnes'))
    })

    test('« Reprendre » on that question opens the activity in progress', async () => {
      status = fakeStatusApi(mealState())
      renderActivity('/activites/cuisine?recipe=B', '/')
      await screen.findByText('Une cuisine est déjà en cours')

      await userEvent.click(screen.getByTestId('cooking-conflict-resume'))

      expect(await screen.findByTestId('cooking-step-text')).toHaveTextContent('Monter le plat')
    })

    test('« Recommencer » on that question erases the one in progress and starts this launch', async () => {
      status = fakeStatusApi(mealState())
      renderActivity('/activites/cuisine?recipe=B', '/')
      await screen.findByText('Une cuisine est déjà en cours')

      await userEvent.click(screen.getByTestId('cooking-conflict-replace'))

      expect(await screen.findByText('Pour combien ?')).toBeInTheDocument()
      await waitFor(() => expect(status.deletes()).toHaveLength(1))
    })

    test('an unreachable status offers « Réessayer » instead of overwriting what may be in progress', async () => {
      const unreachable = fakeStatusApi()
      status = { ...unreachable, handle: () => Promise.resolve(new Response('', { status: 503 })) }

      renderActivity('/activites/cuisine?meal=M3')

      expect(await screen.findByText(/n'a pas pu être chargée/)).toBeInTheDocument()
    })
  })
})

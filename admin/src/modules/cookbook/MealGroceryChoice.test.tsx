import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MealGroceryChoice } from './MealGroceryChoice'

const mockNotify = vi.fn()
vi.mock('react-admin', () => ({ useNotify: () => mockNotify }))

const fetchMock = vi.fn()
const onDone = vi.fn()

const rice = {
  ingredientId: '01RICE',
  name: 'Riz',
  quantity: 300,
  unit: 'g',
  packaging: { unit: 'pack', size: 500, sizeUnit: 'g' },
  toBuy: { quantity: 1, unit: 'pack' },
  stockState: 'out',
  suggested: true,
}
const vegetables = {
  ingredientId: '01VEG',
  name: 'Légumes pour couscous',
  quantity: 1,
  unit: 'jar',
  packaging: { unit: 'jar', size: null, sizeUnit: null },
  toBuy: { quantity: 1, unit: 'jar' },
  stockState: 'in_stock',
  suggested: false,
}
const pasta = {
  ingredientId: '01PASTA',
  name: 'Pâtes',
  quantity: 200,
  unit: 'g',
  packaging: { unit: 'pack', size: 500, sizeUnit: 'g' },
  toBuy: { quantity: 1, unit: 'pack' },
  stockState: 'low',
  suggested: true,
}

const json = (body: unknown, status = 200) => Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) })
const previewOf = (...ingredients: unknown[]) => json({ mealId: '01MEAL', groceryChoiceMadeAt: null, ingredients })

const posts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')

const renderStep = async (...ingredients: unknown[]) => {
  fetchMock.mockImplementation((_url: string, init?: { method?: string }) =>
    init?.method === 'POST' ? json({}) : previewOf(...ingredients),
  )
  const user = userEvent.setup()
  render(<MealGroceryChoice mealIri="/api/meals/01MEAL" onDone={onDone} />)
  await screen.findByRole('checkbox', { name: ingredients.map((i) => (i as { name: string }).name)[0] })

  return user
}

describe('MealGroceryChoice', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('reads the preview of the meal that was created', async () => {
    await renderStep(rice)

    expect(fetchMock).toHaveBeenCalledWith('/api/meals/01MEAL/grocery_preview', expect.anything())
  })

  test('shows one row per ingredient with the packaged quantity and the stock chip', async () => {
    await renderStep(rice, vegetables)

    expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    expect(screen.getByText('1 paquet (500 g)')).toBeInTheDocument()
    expect(screen.getByText('Recette : 300 g')).toBeInTheDocument()
    expect(screen.getByText('1 bocal')).toBeInTheDocument()
    expect(screen.getByText('Rupture')).toBeInTheDocument()
    expect(screen.queryByText('En stock')).not.toBeInTheDocument()
  })

  test('ticks only the products that are low or out', async () => {
    await renderStep(rice, vegetables, pasta)

    expect(screen.getByRole('checkbox', { name: 'Riz' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Pâtes' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Légumes pour couscous' })).not.toBeChecked()
    expect(screen.getByText('Stock faible')).toBeInTheDocument()
    expect(screen.getByText('2 sur 3 cochés')).toBeInTheDocument()
  })

  test('ticks and unticks one by one, all at once, none at once', async () => {
    const user = await renderStep(rice, vegetables)

    await user.click(screen.getByRole('checkbox', { name: 'Légumes pour couscous' }))
    expect(screen.getByRole('checkbox', { name: 'Légumes pour couscous' })).toBeChecked()
    await user.click(screen.getByRole('checkbox', { name: 'Riz' }))
    expect(screen.getByRole('checkbox', { name: 'Riz' })).not.toBeChecked()

    await user.click(screen.getByRole('button', { name: 'Tout décocher' }))
    expect(screen.getAllByRole('checkbox').every((box) => !(box as HTMLInputElement).checked)).toBe(true)

    await user.click(screen.getByRole('button', { name: 'Tout cocher' }))
    expect(screen.getAllByRole('checkbox').every((box) => (box as HTMLInputElement).checked)).toBe(true)
  })

  test('sends the ticked ingredients and nothing else, then is done', async () => {
    const user = await renderStep(rice, vegetables)

    await user.click(screen.getByRole('button', { name: 'Ajouter aux courses' }))

    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(posts()).toHaveLength(1)
    const [url, init] = posts()[0]
    expect(url).toBe('/api/meals/01MEAL/grocery_items')
    expect(JSON.parse(init.body)).toEqual({ ingredients: [{ ingredientId: '01RICE' }] })
    expect(mockNotify).toHaveBeenCalledWith(expect.stringMatching(/ajoutés/), { type: 'success' })
  })

  test('sends the ingredients ticked by hand as well', async () => {
    const user = await renderStep(rice, vegetables)

    await user.click(screen.getByRole('checkbox', { name: 'Légumes pour couscous' }))
    await user.click(screen.getByRole('button', { name: 'Ajouter aux courses' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(JSON.parse(posts()[0][1].body).ingredients).toEqual([{ ingredientId: '01RICE' }, { ingredientId: '01VEG' }])
  })

  test('« Plus tard » closes without calling the add', async () => {
    const user = await renderStep(rice, vegetables)

    await user.click(screen.getByRole('button', { name: 'Plus tard' }))

    expect(onDone).toHaveBeenCalled()
    expect(posts()).toHaveLength(0)
  })

  test('keeps the selection and says what was not added when the API refuses', async () => {
    const user = await renderStep(rice, vegetables)
    fetchMock.mockImplementation((_url: string, init?: { method?: string }) =>
      init?.method === 'POST' ? json({ error: 'Not an ingredient of this meal' }, 400) : previewOf(rice, vegetables),
    )
    await user.click(screen.getByRole('checkbox', { name: 'Légumes pour couscous' }))

    await user.click(screen.getByRole('button', { name: 'Ajouter aux courses' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/Non ajouté.*Riz, Légumes pour couscous/)
    expect(alert).toHaveTextContent('Not an ingredient of this meal')
    expect(alert).toHaveTextContent(/repas est bien créé/)
    expect(onDone).not.toHaveBeenCalled()
    expect(screen.getByRole('checkbox', { name: 'Riz' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Légumes pour couscous' })).toBeChecked()
  })

  test('keeps the selection when the API cannot be reached, and can retry', async () => {
    const user = await renderStep(rice, vegetables)
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await user.click(screen.getByRole('button', { name: 'Ajouter aux courses' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/Non ajouté.*Riz/)
    expect(screen.getByRole('checkbox', { name: 'Riz' })).toBeChecked()

    await user.click(screen.getByRole('button', { name: 'Ajouter aux courses' }))
    await waitFor(() => expect(onDone).toHaveBeenCalled())
  })

  test('offers to retry when the preview cannot be loaded, and still lets the owner leave', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 500, json: () => Promise.resolve({}) })
    const user = userEvent.setup()
    render(<MealGroceryChoice mealIri="/api/meals/01MEAL" onDone={onDone} />)

    expect(await screen.findByRole('alert')).toHaveTextContent(/repas est créé/)

    fetchMock.mockImplementation(() => previewOf(rice))
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))
    expect(await screen.findByRole('checkbox', { name: 'Riz' })).toBeChecked()
  })

  test('closes at once when the recipes have no ingredient to choose from', async () => {
    fetchMock.mockImplementation(() => previewOf())
    render(<MealGroceryChoice mealIri="/api/meals/01MEAL" onDone={onDone} />)

    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(posts()).toHaveLength(0)
  })

  test('makes one choice of a product used in two units', async () => {
    const riceCups = { ...rice, quantity: 2, unit: 'piece', toBuy: { quantity: 1, unit: 'pack' } }
    const user = await renderStep(rice, riceCups)

    expect(screen.getAllByRole('checkbox')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Ajouter aux courses' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(JSON.parse(posts()[0][1].body).ingredients).toEqual([{ ingredientId: '01RICE' }])
  })
  describe('loading the preview (MAG-373)', () => {
    const networkError = () => Promise.reject(new TypeError('NetworkError when attempting to fetch resource.'))
    const abortError = () => new DOMException('The operation was aborted.', 'AbortError')

    test('retries a network failure once and shows the list with no message', async () => {
      fetchMock.mockImplementationOnce(networkError).mockImplementation(() => previewOf(rice))
      render(<MealGroceryChoice mealIri="/api/meals/01MEAL" onDone={onDone} />)

      expect(await screen.findByRole('checkbox', { name: 'Riz' })).toBeChecked()
      expect(fetchMock).toHaveBeenCalledTimes(2)
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    test('shows the message and « Réessayer » after two network failures', async () => {
      fetchMock.mockImplementation(networkError)
      const user = userEvent.setup()
      render(<MealGroceryChoice mealIri="/api/meals/01MEAL" onDone={onDone} />)

      expect(await screen.findByRole('alert')).toHaveTextContent(/repas est créé/)
      expect(fetchMock).toHaveBeenCalledTimes(2)

      fetchMock.mockImplementation(() => previewOf(rice))
      await user.click(screen.getByRole('button', { name: 'Réessayer' }))
      expect(await screen.findByRole('checkbox', { name: 'Riz' })).toBeChecked()
    })

    test('shows the message at once on a 500, without retrying', async () => {
      fetchMock.mockImplementation(() => json({}, 500))
      render(<MealGroceryChoice mealIri="/api/meals/01MEAL" onDone={onDone} />)

      expect(await screen.findByRole('alert')).toHaveTextContent(/repas est créé/)
      expect(fetchMock).toHaveBeenCalledTimes(1)
    })

    test('aborts the request when unmounted and shows nothing for it', async () => {
      let signal: AbortSignal | undefined
      fetchMock.mockImplementation(
        (_url: string, init: { signal?: AbortSignal }) =>
          new Promise((_resolve, reject) => {
            signal = init.signal
            init.signal?.addEventListener('abort', () => reject(abortError()))
          }),
      )
      const { unmount } = render(<MealGroceryChoice mealIri="/api/meals/01MEAL" onDone={onDone} />)
      await waitFor(() => expect(fetchMock).toHaveBeenCalled())

      unmount()

      expect(signal?.aborted).toBe(true)
      await Promise.resolve()
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(onDone).not.toHaveBeenCalled()
    })

    test('an abort is never shown as an error, and is not retried', async () => {
      const calls: string[] = []
      fetchMock.mockImplementation(
        (url: string, init: { signal?: AbortSignal }) =>
          new Promise((resolve, reject) => {
            calls.push(url)
            if (url.includes('01OLD')) {
              init.signal?.addEventListener('abort', () => reject(abortError()))
              return
            }
            resolve({ ok: true, status: 200, json: () => Promise.resolve({ ingredients: [rice] }) })
          }),
      )
      const { rerender } = render(<MealGroceryChoice mealIri="/api/meals/01OLD" onDone={onDone} />)
      await waitFor(() => expect(calls).toHaveLength(1))

      rerender(<MealGroceryChoice mealIri="/api/meals/01NEW" onDone={onDone} />)

      expect(await screen.findByRole('checkbox', { name: 'Riz' })).toBeChecked()
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
      expect(calls).toEqual(['/api/meals/01OLD/grocery_preview', '/api/meals/01NEW/grocery_preview'])
    })
  })
})

import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, RecordContextProvider, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { RecipeDeleteButton } from './RecipeDeleteButton'

/**
 * MAG-289: deleting a recipe deletes the meals it was the only recipe of, so
 * the confirmation says how many.
 */

const recipe = { id: '/api/recipes/01C', name: 'Couscous' }

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const renderButton = (remove: ReturnType<typeof vi.fn>) =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider({ delete: remove })} i18nProvider={i18nProvider}>
        <ResourceContextProvider value="recipes">
          <RecordContextProvider value={recipe}>
            <RecipeDeleteButton />
          </RecordContextProvider>
        </ResourceContextProvider>
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

const answerWith = (impact: Partial<Response> | Error) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(() => (impact instanceof Error ? Promise.reject(impact) : Promise.resolve(impact as Response))),
  )

const mealCount = (count: number) => answerWith({ ok: true, json: () => Promise.resolve({ mealCount: count }) })

describe('RecipeDeleteButton', { timeout: 30_000 }, () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test('announces how many planned meals leave with the recipe', async () => {
    const user = userEvent.setup()
    mealCount(3)
    renderButton(vi.fn())

    await user.click(screen.getByRole('button', { name: 'Supprimer' }))

    expect(await screen.findByText('Supprimer « Couscous » et ses 3 repas planifiés ?')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(
      '/api/recipes/01C/deletion-impact',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer jwt' }) }),
    )
  })

  test('says « son repas » for a single meal', async () => {
    const user = userEvent.setup()
    mealCount(1)
    renderButton(vi.fn())

    await user.click(screen.getByRole('button', { name: 'Supprimer' }))

    expect(await screen.findByText('Supprimer « Couscous » et son repas planifié ?')).toBeInTheDocument()
  })

  test('asks plainly when no meal is planned', async () => {
    const user = userEvent.setup()
    mealCount(0)
    renderButton(vi.fn())

    await user.click(screen.getByRole('button', { name: 'Supprimer' }))

    expect(await screen.findByText('Supprimer « Couscous » ?')).toBeInTheDocument()
  })

  test('deletes the recipe once the owner confirms', async () => {
    const user = userEvent.setup()
    mealCount(2)
    const remove = vi.fn().mockResolvedValue({ data: recipe })
    renderButton(remove)

    await user.click(screen.getByRole('button', { name: 'Supprimer' }))
    await screen.findByText('Supprimer « Couscous » et ses 2 repas planifiés ?')
    await user.click(screen.getByRole('button', { name: 'Confirmer' }))

    await waitFor(() => expect(remove).toHaveBeenCalled())
    expect(remove.mock.calls[0][0]).toBe('recipes')
    expect(remove.mock.calls[0][1]).toMatchObject({ id: '/api/recipes/01C' })
    expect(await screen.findByText('Recette supprimée')).toBeInTheDocument()
  })

  test('deletes nothing when the owner cancels', async () => {
    const user = userEvent.setup()
    mealCount(2)
    const remove = vi.fn()
    renderButton(remove)

    await user.click(screen.getByRole('button', { name: 'Supprimer' }))
    await screen.findByText('Supprimer « Couscous » et ses 2 repas planifiés ?')
    await user.click(screen.getByRole('button', { name: 'Annuler' }))

    expect(remove).not.toHaveBeenCalled()
  })

  test('still warns about the meals when the count could not be read', async () => {
    const user = userEvent.setup()
    answerWith(new Error('network'))
    renderButton(vi.fn())

    await user.click(screen.getByRole('button', { name: 'Supprimer' }))

    expect(await screen.findByText('Supprimer « Couscous » ?')).toBeInTheDocument()
    expect(await screen.findByText(/seront supprimés avec elle/)).toBeInTheDocument()
  })

  test('says so when the API refuses the deletion', async () => {
    const user = userEvent.setup()
    mealCount(0)
    renderButton(vi.fn().mockRejectedValue(new Error('Forbidden')))

    await user.click(screen.getByRole('button', { name: 'Supprimer' }))
    await screen.findByText('Supprimer « Couscous » ?')
    await user.click(screen.getByRole('button', { name: 'Confirmer' }))

    expect(await screen.findByText('Erreur : Forbidden')).toBeInTheDocument()
  })
})

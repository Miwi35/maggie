import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MealsWeekView } from './MealsWeekView'

const mockGetList = vi.fn()
const mockCreate = vi.fn()
const mockNotify = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({ getList: mockGetList, create: mockCreate, delete: vi.fn() }),
  useNotify: () => mockNotify,
  Title: () => null,
}))

const agendas = [
  { id: '/api/agendas/01FAMILLE', '@id': '/api/agendas/01FAMILLE', name: 'Famille', default: false },
  { id: '/api/agendas/01PERSO', '@id': '/api/agendas/01PERSO', name: 'Perso', default: true },
]

const answer = (agendaRows: unknown[]) => (resource: string) =>
  Promise.resolve({ data: resource === 'agendas' ? agendaRows : [], total: 0 })

const createFirstEmptyMeal = async () => {
  const user = userEvent.setup()
  render(<MealsWeekView />)
  await waitFor(() => expect(mockGetList).toHaveBeenCalledWith('meals', expect.any(Object)))

  await user.click(screen.getByTestId('meal-cell-lunch-0'))
  await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Créer' }))
}

describe('MealsWeekView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreate.mockResolvedValue({ data: {} })
    mockGetList.mockImplementation(answer(agendas))
  })

  test('creates the meal in a real agenda, never in the agendas collection', async () => {
    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    const { agenda } = mockCreate.mock.calls[0][1].data
    expect(agenda).not.toBe('/api/agendas')
    expect(agenda).toMatch(/^\/api\/agendas\/\w+$/)
  })

  test('prefers the agenda named Repas', async () => {
    mockGetList.mockImplementation(
      answer([...agendas, { id: '/api/agendas/01REPAS', '@id': '/api/agendas/01REPAS', name: 'Repas', default: false }]),
    )

    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data.agenda).toBe('/api/agendas/01REPAS')
  })

  test('falls back to the default agenda', async () => {
    await createFirstEmptyMeal()

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data.agenda).toBe('/api/agendas/01PERSO')
  })

  test('tells the user instead of posting when there is no agenda', async () => {
    mockGetList.mockImplementation(answer([]))

    await createFirstEmptyMeal()

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith(expect.stringMatching(/agenda/i), { type: 'error' }))
    expect(mockCreate).not.toHaveBeenCalled()
  })
})

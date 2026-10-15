import { describe, test, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskCreateDialog } from './TaskCreateDialog'

const mockCreate = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({ create: mockCreate }),
  useNotify: () => vi.fn(),
}))

describe('TaskCreateDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreate.mockResolvedValue({ data: {} })
  })

  // A due date is a calendar day, sent as that day with no time to shift it (MAG-404).
  test('posts the due date as a plain day', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} defaultDueDate={new Date(2026, 9, 5)} />)

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Payer le loyer' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({ data: expect.objectContaining({ title: 'Payer le loyer', dueDate: '2026-10-05' }) }),
      ),
    )
  })

  test('posts the notes as the description', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Déménager' } })
    fireEvent.change(screen.getByLabelText('Notes'), { target: { value: 'Les cartons sont à la cave' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({ data: expect.objectContaining({ description: 'Les cartons sont à la cave' }) }),
      ),
    )
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('dueDate')
  })

  test('posts the estimated minutes and the first step when given (MAG-497)', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Déclarer les impôts' } })
    fireEvent.change(screen.getByLabelText('Durée estimée'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText('Premier pas'), { target: { value: 'Retrouver le numéro fiscal' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({
          data: expect.objectContaining({ estimatedMinutes: 30, firstStep: 'Retrouver le numéro fiscal' }),
        }),
      ),
    )
  })

  test('leaves the minutes and the first step out when empty', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Déménager' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() => expect(mockCreate).toHaveBeenCalled())
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('estimatedMinutes')
    expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('firstStep')
  })

  test('refuses minutes out of 1 to 1440', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Déménager' } })
    fireEvent.change(screen.getByLabelText('Durée estimée'), { target: { value: '0' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    expect(screen.getByText('Entre 1 et 1440 minutes')).toBeInTheDocument()
    expect(mockCreate).not.toHaveBeenCalled()
  })

  test('refuses an empty title', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    expect(mockCreate).not.toHaveBeenCalled()
  })
})

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

  // A due date is a calendar day, stored as that day in UTC with the zone written out (MAG-168).
  test('posts the due date as a whole day in UTC', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} defaultDueDate={new Date(2026, 9, 5)} />)

    fireEvent.change(screen.getByLabelText(/Titre/), { target: { value: 'Payer le loyer' } })
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({ data: expect.objectContaining({ title: 'Payer le loyer', dueDate: '2026-10-05T00:00:00Z' }) }),
      ),
    )
  })

  test('refuses an empty title', async () => {
    render(<TaskCreateDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    expect(mockCreate).not.toHaveBeenCalled()
  })
})

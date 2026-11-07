import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskFormDialog, type TaskData } from './TaskFormDialog'

const mockCreate = vi.fn()
const mockUpdate = vi.fn()
const mockDelete = vi.fn()
const mockNotify = vi.fn()
const dataProvider = { create: mockCreate, update: mockUpdate, delete: mockDelete }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => mockNotify,
}))

const TASK: TaskData = {
  id: '/api/tasks/01MOVING',
  title: 'Préparer le déménagement',
  description: 'Les cartons sont à la cave',
  priority: 'medium',
  criticality: 'medium',
  dueDate: '2026-03-20',
}

const renderForm = (task: TaskData | null, over: Partial<Parameters<typeof TaskFormDialog>[0]> = {}) => {
  const onClose = vi.fn()
  const onSaved = vi.fn()
  render(<TaskFormDialog open task={task} onClose={onClose} onSaved={onSaved} {...over} />)

  return { onClose, onSaved }
}

const pressed = (group: string) =>
  screen.getByRole('group', { name: group }).querySelector('[aria-pressed="true"]')?.textContent

describe('TaskFormDialog (MAG-723)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCreate.mockResolvedValue({ data: { id: '/api/tasks/01NEW' } })
    mockUpdate.mockResolvedValue({ data: {} })
    mockDelete.mockResolvedValue({ data: {} })
  })

  describe('creating', () => {
    test('is « Nouvelle tâche » with « Créer », starts empty and has no « Supprimer »', () => {
      renderForm(null, { defaultDueDate: '2026-03-17' })

      expect(screen.getByRole('dialog', { name: 'Nouvelle tâche' })).toBeInTheDocument()
      expect(screen.getByLabelText(/Titre/)).toHaveValue('')
      expect(screen.getByLabelText('Échéance')).toHaveValue('2026-03-17')
      expect(pressed('Criticité')).toBe('Basse')
      expect(pressed('Priorité')).toBe('Moyenne')
      expect(screen.getByRole('button', { name: 'Créer' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Supprimer' })).toBeNull()
    })

    test('refuses a blank title', async () => {
      renderForm(null)

      await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

      expect(screen.getByText('Le titre est requis')).toBeInTheDocument()
      expect(mockCreate).not.toHaveBeenCalled()
    })

    test('creates a task planned tomorrow at 18:00 with a reminder 30 minutes before', async () => {
      const { onSaved, onClose } = renderForm(null)

      await userEvent.type(screen.getByLabelText(/Titre/), 'Appeler le garage')
      await userEvent.type(screen.getByLabelText('Programmée'), '2026-03-18')
      await userEvent.click(screen.getByRole('button', { name: 'Heure précise' }))
      const time = screen.getByLabelText(/Heure/)
      await userEvent.clear(time)
      await userEvent.type(time, '18:00')
      await userEvent.click(screen.getByRole('combobox', { name: 'Rappels' }))
      await userEvent.click(await screen.findByRole('option', { name: '30 minutes avant' }))
      await userEvent.keyboard('{Escape}')
      await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

      await waitFor(() =>
        expect(mockCreate).toHaveBeenCalledWith('tasks', {
          data: expect.objectContaining({
            title: 'Appeler le garage',
            scheduledDate: '2026-03-18',
            scheduledMoment: 'time',
            scheduledTime: '18:00',
            reminders: [30],
          }),
        }),
      )
      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      expect(onClose).toHaveBeenCalled()
    })

    test('sends no schedule for a task with no programmed day', async () => {
      renderForm(null)

      await userEvent.type(screen.getByLabelText(/Titre/), 'Ranger')
      await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

      await waitFor(() => expect(mockCreate).toHaveBeenCalled())
      expect(mockCreate.mock.calls[0][1].data).not.toHaveProperty('scheduledDate')
    })

    test('reports a refusal and stays open', async () => {
      mockCreate.mockRejectedValue(new Error('boom'))
      const { onClose } = renderForm(null)

      await userEvent.type(screen.getByLabelText(/Titre/), 'Ranger')
      await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

      await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: boom', { type: 'error' }))
      expect(onClose).not.toHaveBeenCalled()
    })
  })

  describe('« Rappels » only at a precise time', () => {
    test('« Heure précise » shows « Rappels », a moment hides it', async () => {
      renderForm(null)

      expect(screen.queryByLabelText('Rappels')).toBeNull()
      await userEvent.type(screen.getByLabelText('Programmée'), '2026-03-18')
      expect(screen.queryByLabelText('Rappels')).toBeNull()

      await userEvent.click(screen.getByRole('button', { name: 'Heure précise' }))
      expect(screen.getByRole('combobox', { name: 'Rappels' })).toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Matin' }))
      expect(screen.queryByRole('combobox', { name: 'Rappels' })).toBeNull()
    })

    test('switching to a moment saves no time and no reminder', async () => {
      renderForm({ ...TASK, scheduledDate: '2026-03-18', scheduledMoment: 'time', scheduledTime: '18:00', reminders: [30] })

      await userEvent.click(screen.getByRole('button', { name: 'Matin' }))
      await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'tasks',
          expect.objectContaining({
            data: expect.objectContaining({ scheduledMoment: 'morning', scheduledTime: null, reminders: [] }),
          }),
        ),
      )
    })

    test('clearing the programmed day clears the whole schedule', async () => {
      renderForm({ ...TASK, scheduledDate: '2026-03-18', scheduledMoment: 'evening' })

      await userEvent.clear(screen.getByLabelText('Programmée'))
      await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'tasks',
          expect.objectContaining({
            data: expect.objectContaining({ scheduledDate: null, scheduledMoment: null, scheduledTime: null, reminders: [] }),
          }),
        ),
      )
    })
  })

  describe('modifying', () => {
    test('is « Modifier la tâche », prefilled, with « Supprimer » and « Enregistrer »', () => {
      renderForm({ ...TASK, criticality: 'high', estimatedMinutes: 20, firstStep: 'Ouvrir le dossier' })

      expect(screen.getByRole('dialog', { name: 'Modifier la tâche' })).toBeInTheDocument()
      expect(screen.getByLabelText(/Titre/)).toHaveValue('Préparer le déménagement')
      expect(screen.getByLabelText('Notes')).toHaveValue('Les cartons sont à la cave')
      expect(screen.getByLabelText('Échéance')).toHaveValue('2026-03-20')
      expect(pressed('Criticité')).toBe('Haute')
      expect(screen.getByLabelText('Durée estimée')).toHaveValue(20)
      expect(screen.getByLabelText('Premier pas')).toHaveValue('Ouvrir le dossier')
      expect(screen.getByRole('button', { name: 'Supprimer' })).toBeInTheDocument()
    })

    test('saves the changes through the API', async () => {
      const { onSaved, onClose } = renderForm(TASK)

      await userEvent.click(within(screen.getByRole('group', { name: 'Priorité' })).getByRole('button', { name: 'Haute' }))
      await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'tasks',
          expect.objectContaining({
            id: TASK.id,
            data: expect.objectContaining({ title: 'Préparer le déménagement', dueDate: '2026-03-20', priority: 'high' }),
          }),
        ),
      )
      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      expect(onClose).toHaveBeenCalled()
    })

    test('clearing the minutes and the first step sends null for both', async () => {
      renderForm({ ...TASK, estimatedMinutes: 20, firstStep: 'Ouvrir le dossier' })

      await userEvent.clear(screen.getByLabelText('Durée estimée'))
      await userEvent.clear(screen.getByLabelText('Premier pas'))
      await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() =>
        expect(mockUpdate).toHaveBeenCalledWith(
          'tasks',
          expect.objectContaining({ data: expect.objectContaining({ estimatedMinutes: null, firstStep: null }) }),
        ),
      )
    })

    test('refuses minutes out of 1 to 1440', async () => {
      renderForm(TASK)

      await userEvent.type(screen.getByLabelText('Durée estimée'), '2000')
      await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

      expect(screen.getByText('Entre 1 et 1440 minutes')).toBeInTheDocument()
      expect(mockUpdate).not.toHaveBeenCalled()
    })

    test('« Supprimer » asks again, then deletes through the API', async () => {
      const { onSaved, onClose } = renderForm(TASK)

      await userEvent.click(screen.getByRole('button', { name: 'Supprimer' }))
      expect(mockDelete).not.toHaveBeenCalled()
      await userEvent.click(screen.getByRole('button', { name: 'Confirmer la suppression' }))

      await waitFor(() => expect(mockDelete).toHaveBeenCalledWith('tasks', { id: TASK.id, previousData: TASK }))
      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      expect(onClose).toHaveBeenCalled()
    })

    test('« Annuler » closes without writing', async () => {
      const { onClose } = renderForm(TASK)

      await userEvent.click(screen.getByRole('button', { name: 'Annuler' }))

      expect(onClose).toHaveBeenCalled()
      expect(mockUpdate).not.toHaveBeenCalled()
    })
  })
})

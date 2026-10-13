import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskEditDialog, type TaskData } from './TaskEditDialog'

const mockGetList = vi.fn()
const mockUpdate = vi.fn()
const mockCreate = vi.fn()
const mockNotify = vi.fn()
// Stable references, as react-admin's: the subtask fetch depends on them.
const dataProvider = { getList: mockGetList, update: mockUpdate, create: mockCreate }
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
  position: '00000000000000000010',
  links: [{ type: 'email', description: 'Le mail du déménageur', link: 'https://mail.google.com/mail/#all/abc' }],
}

const BOXES: TaskData = {
  id: '/api/tasks/01BOXES',
  title: 'Réserver le camion',
  priority: 'medium',
  criticality: 'low',
  parent: '/api/tasks/01MOVING',
  position: '00000000000000000000',
}

const ADDRESS: TaskData = {
  id: '/api/tasks/01ADDRESS',
  title: "Changer d'adresse à la banque",
  priority: 'medium',
  criticality: 'low',
  parent: '/api/tasks/01MOVING',
  position: '00000000000000000001',
}

const renderDialog = (task: TaskData = TASK) =>
  render(<TaskEditDialog open task={task} onClose={vi.fn()} onUpdated={vi.fn()} />)

const subtaskTitles = () =>
  within(screen.getByRole('region', { name: 'Sous-tâches' }))
    .getAllByRole('checkbox')
    .map((box) => box.closest('label')?.textContent)

describe('TaskEditDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Served out of order: the dialog sorts by position whatever comes back.
    mockGetList.mockResolvedValue({ data: [ADDRESS, BOXES], total: 2 })
    mockUpdate.mockResolvedValue({ data: {} })
    mockCreate.mockImplementation((_resource, { data }) =>
      Promise.resolve({ data: { ...data, id: '/api/tasks/01NEW', position: '00000000000000000002' } }),
    )
  })

  test('shows the notes, the due day, the links and the subtasks in order', async () => {
    renderDialog()

    expect(screen.getByLabelText('Notes')).toHaveValue('Les cartons sont à la cave')
    expect(screen.getByLabelText('Échéance')).toHaveValue('2026-03-20')

    const link = screen.getByRole('link', { name: 'Le mail du déménageur' })
    expect(link).toHaveAttribute('href', 'https://mail.google.com/mail/#all/abc')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noreferrer')

    await waitFor(() => expect(subtaskTitles()).toEqual(['Réserver le camion', "Changer d'adresse à la banque"]))
    expect(mockGetList).toHaveBeenCalledWith(
      'tasks',
      expect.objectContaining({
        sort: { field: 'position', order: 'ASC' },
        filter: { parent: '/api/tasks/01MOVING' },
      }),
    )
  })

  test('a subtask offers no subtasks of its own', () => {
    renderDialog(BOXES)

    expect(screen.queryByRole('region', { name: 'Sous-tâches' })).toBeNull()
    expect(mockGetList).not.toHaveBeenCalled()
  })

  test('saves the due date as a plain day', async () => {
    renderDialog()

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({ id: TASK.id, data: expect.objectContaining({ dueDate: '2026-03-20' }) }),
      ),
    )
  })

  test('ticking a subtask completes that subtask only', async () => {
    renderDialog()

    const box = await screen.findByRole('checkbox', { name: 'Réserver le camion' })
    await userEvent.click(box)

    expect(box).toBeChecked()
    expect(mockUpdate).toHaveBeenCalledTimes(1)
    expect(mockUpdate).toHaveBeenCalledWith(
      'tasks',
      expect.objectContaining({ id: BOXES.id, data: { completedAt: expect.any(String) } }),
    )
    expect(screen.getByRole('switch', { name: 'Terminée' })).not.toBeChecked()
  })

  test('unticking a subtask reopens it', async () => {
    mockGetList.mockResolvedValue({ data: [{ ...BOXES, completedAt: '2026-03-19T10:00:00+00:00' }], total: 1 })
    renderDialog()

    const box = await screen.findByRole('checkbox', { name: 'Réserver le camion' })
    expect(box).toBeChecked()
    await userEvent.click(box)

    expect(box).not.toBeChecked()
    expect(mockUpdate).toHaveBeenCalledWith('tasks', expect.objectContaining({ id: BOXES.id, data: { completedAt: null } }))
  })

  test('moving a subtask down swaps its position with the next one', async () => {
    renderDialog()

    await userEvent.click(await screen.findByRole('button', { name: 'Descendre Réserver le camion' }))

    await waitFor(() => expect(subtaskTitles()).toEqual(["Changer d'adresse à la banque", 'Réserver le camion']))
    expect(mockUpdate).toHaveBeenCalledWith(
      'tasks',
      expect.objectContaining({ id: BOXES.id, data: { position: ADDRESS.position } }),
    )
    expect(mockUpdate).toHaveBeenCalledWith(
      'tasks',
      expect.objectContaining({ id: ADDRESS.id, data: { position: BOXES.position } }),
    )
    expect(screen.getByRole('button', { name: 'Monter Réserver le camion' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Descendre Réserver le camion' })).toBeDisabled()
  })

  test('adding a subtask posts it under the task and lists it last', async () => {
    renderDialog()
    await screen.findByRole('checkbox', { name: 'Réserver le camion' })

    await userEvent.type(screen.getByLabelText('Nouvelle sous-tâche'), 'Résilier la box')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter une sous-tâche' }))

    expect(mockCreate).toHaveBeenCalledWith('tasks', {
      data: { title: 'Résilier la box', parent: '/api/tasks/01MOVING' },
    })
    await waitFor(() =>
      expect(subtaskTitles()).toEqual(['Réserver le camion', "Changer d'adresse à la banque", 'Résilier la box']),
    )
    expect(screen.getByLabelText('Nouvelle sous-tâche')).toHaveValue('')
  })

  test('a refused tick is undone and reported', async () => {
    mockUpdate.mockRejectedValue(new Error('Accès refusé'))
    renderDialog()

    const box = await screen.findByRole('checkbox', { name: 'Réserver le camion' })
    await userEvent.click(box)

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Accès refusé', { type: 'error' }))
    expect(box).not.toBeChecked()
  })

  test('a refused subtask is reported and left out of the list', async () => {
    mockCreate.mockRejectedValue(new Error('Une sous-tâche ne peut pas avoir de sous-tâches.'))
    renderDialog()
    await screen.findByRole('checkbox', { name: 'Réserver le camion' })

    await userEvent.type(screen.getByLabelText('Nouvelle sous-tâche'), 'Résilier la box')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter une sous-tâche' }))

    await waitFor(() =>
      expect(mockNotify).toHaveBeenCalledWith('Erreur: Une sous-tâche ne peut pas avoir de sous-tâches.', {
        type: 'error',
      }),
    )
    expect(subtaskTitles()).toHaveLength(2)
    expect(screen.getByLabelText('Nouvelle sous-tâche')).toHaveValue('Résilier la box')
  })
})

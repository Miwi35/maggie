import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskSheet } from './TaskSheet'
import type { TaskData } from './TaskFormDialog'

const mockGetList = vi.fn()
const mockUpdate = vi.fn()
const mockNotify = vi.fn()
// Stable references, as react-admin's: the subtask fetch depends on them.
const dataProvider = { getList: mockGetList, update: mockUpdate }
vi.mock('react-admin', () => ({
  useDataProvider: () => dataProvider,
  useNotify: () => mockNotify,
}))

const TASK: TaskData = {
  id: '/api/tasks/01MOVING',
  title: 'Préparer le déménagement',
  description: 'Les cartons sont à la cave',
  priority: 'medium',
  criticality: 'high',
  dueDate: '2026-03-20',
  scheduledDate: '2026-03-18',
  scheduledMoment: 'time',
  scheduledTime: '18:00',
  reminders: [30],
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

const renderSheet = (task: TaskData = TASK, over: Partial<Parameters<typeof TaskSheet>[0]> = {}) =>
  render(<TaskSheet task={task} today="2026-03-17" onClose={vi.fn()} onEdit={vi.fn()} onChanged={vi.fn()} {...over} />)

const subtaskTitles = () =>
  within(screen.getByRole('region', { name: 'Sous-tâches' }))
    .getAllByRole('checkbox')
    .map((box) => box.closest('label')?.textContent)

describe('TaskSheet (MAG-723)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetList.mockResolvedValue({ data: [ADDRESS, BOXES], total: 2 })
    mockUpdate.mockResolvedValue({ data: {} })
  })

  test('reads the task and holds no editable field', async () => {
    renderSheet()

    const panel = screen.getByRole('complementary', { name: 'Fiche de la tâche' })
    expect(within(panel).getByRole('heading', { name: 'Préparer le déménagement' })).toBeInTheDocument()
    expect(within(panel).getByText(/20 mars/)).toBeInTheDocument()
    expect(within(panel).getByText(/18 mars · 18:00/)).toBeInTheDocument()
    expect(within(panel).getByText('30 minutes avant')).toBeInTheDocument()
    expect(within(panel).getByText('Haute')).toBeInTheDocument()
    expect(within(panel).getByText('Moyenne')).toBeInTheDocument()
    expect(within(panel).getByText('Les cartons sont à la cave')).toBeInTheDocument()
    await waitFor(() => expect(subtaskTitles()).toEqual(['Réserver le camion', "Changer d'adresse à la banque"]))
    expect(within(panel).queryByRole('textbox')).toBeNull()
    expect(within(panel).queryByRole('combobox')).toBeNull()
    expect(within(panel).queryByRole('spinbutton')).toBeNull()
  })

  test('a moment reads as its word, and no reminder row without a precise time', () => {
    renderSheet({ ...TASK, scheduledMoment: 'evening', scheduledTime: null, reminders: [] })

    expect(screen.getByText(/18 mars · Soir/)).toBeInTheDocument()
    expect(screen.queryByText('Rappels')).toBeNull()
  })

  test('the pencil opens the form, the cross closes the sheet', async () => {
    const onEdit = vi.fn()
    const onClose = vi.fn()
    renderSheet(TASK, { onEdit, onClose })

    await userEvent.click(screen.getByRole('button', { name: 'Modifier la tâche' }))
    await userEvent.click(screen.getByRole('button', { name: 'Fermer la fiche' }))

    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  test('shows the links', () => {
    renderSheet()

    const link = screen.getByRole('link', { name: 'Le mail du déménageur' })
    expect(link).toHaveAttribute('href', 'https://mail.google.com/mail/#all/abc')
    expect(link).toHaveAttribute('rel', 'noreferrer')
  })

  test('shows the urgency the API computed (MAG-406)', () => {
    renderSheet({ ...TASK, urgency: 'critical' })

    expect(screen.getByTestId('urgency-badge')).toHaveTextContent('critique')
  })

  test('a box in the notes is ticked in place and saved as Markdown (MAG-722)', async () => {
    const onChanged = vi.fn()
    renderSheet({ ...TASK, description: '- [ ] Douche solaire\n- [ ] Courses' }, { onChanged })

    await userEvent.click(screen.getByRole('checkbox', { name: 'Douche solaire' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({ data: { description: '- [x] Douche solaire\n- [ ] Courses' } }),
      ),
    )
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  test('« Marquer comme faite » completes the task', async () => {
    const onChanged = vi.fn()
    renderSheet(TASK, { onChanged })

    await userEvent.click(screen.getByRole('button', { name: 'Marquer comme faite' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(
        'tasks',
        expect.objectContaining({ id: TASK.id, data: { completedAt: expect.any(String) } }),
      ),
    )
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  test('« Reporter » moves the due day to tomorrow', async () => {
    renderSheet()

    await userEvent.click(screen.getByRole('button', { name: 'Reporter' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('tasks', expect.objectContaining({ id: TASK.id, data: { dueDate: '2026-03-18' } })),
    )
  })

  test('a done task offers « Rouvrir » and no « Reporter »', async () => {
    renderSheet({ ...TASK, completedAt: '2026-03-16T10:00:00+00:00' })

    expect(screen.queryByRole('button', { name: 'Reporter' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Rouvrir' }))

    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith('tasks', expect.objectContaining({ data: { completedAt: null } })),
    )
  })

  test('a subtask offers no subtasks of its own', () => {
    renderSheet(BOXES)

    expect(screen.queryByRole('region', { name: 'Sous-tâches' })).toBeNull()
    expect(mockGetList).not.toHaveBeenCalled()
  })

  test('ticking a subtask completes that subtask only, and a refusal undoes it', async () => {
    renderSheet()

    const box = await screen.findByRole('checkbox', { name: 'Réserver le camion' })
    await userEvent.click(box)
    expect(box).toBeChecked()
    expect(mockUpdate).toHaveBeenCalledWith('tasks', expect.objectContaining({ id: BOXES.id, data: { completedAt: expect.any(String) } }))

    mockUpdate.mockRejectedValue(new Error('Accès refusé'))
    await userEvent.click(box)
    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith('Erreur: Accès refusé', { type: 'error' }))
    expect(box).toBeChecked()
  })

  test('moving a subtask down sends one PATCH with the next one’s position', async () => {
    renderSheet()

    await userEvent.click(await screen.findByRole('button', { name: 'Descendre Réserver le camion' }))

    await waitFor(() => expect(subtaskTitles()).toEqual(["Changer d'adresse à la banque", 'Réserver le camion']))
    expect(mockUpdate).toHaveBeenCalledWith('tasks', expect.objectContaining({ id: BOXES.id, data: { position: ADDRESS.position } }))
  })
})

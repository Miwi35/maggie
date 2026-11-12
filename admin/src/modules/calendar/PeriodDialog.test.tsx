import { describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { PeriodDialog } from './PeriodDialog'
import type { Period } from './periodsApi'

const AGENDA = '/api/agendas/famille'
const AGENDAS = [{ id: AGENDA, name: 'Famille', color: '#e91e63', default: true }]

const VACANCES: Period = { id: '/api/periods/01V', name: 'Vacances', agenda: AGENDA, allDay: true, startDate: '2026-10-18', endDate: '2026-10-21', rrule: null }

function renderDialog(period: Period | null, overrides: Partial<DataProvider> = {}) {
  const onClose = vi.fn()
  const update = vi.fn(() => Promise.resolve({ data: {} }))
  const create = vi.fn(() => Promise.resolve({ data: {} }))
  const remove = vi.fn(() => Promise.resolve({ data: {} }))
  const dataProvider = testDataProvider({
    getList: (() => Promise.resolve({ data: AGENDAS, total: 1 })) as unknown as DataProvider['getList'],
    update: update as unknown as DataProvider['update'],
    create: create as unknown as DataProvider['create'],
    delete: remove as unknown as DataProvider['delete'],
    ...overrides,
  })
  render(
    <AdminContext dataProvider={dataProvider}>
      <PeriodDialog open period={period} onClose={onClose} />
    </AdminContext>,
  )

  return { onClose, update, create, remove }
}

describe('PeriodDialog (MAG-816)', () => {
  test('sends the end excluded', async () => {
    const { update, onClose } = renderDialog(VACANCES)
    const dialog = await screen.findByRole('dialog')
    // Read included: the 20th is the last day of [18, 21).
    expect(within(dialog).getByLabelText(/Fin/)).toHaveValue('2026-10-20')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalled())
    const params = (update.mock.calls[0] as unknown as [string, { id: string; data: Record<string, unknown> }])[1]
    expect(params.id).toBe('/api/periods/01V')
    expect(params.data).toMatchObject({ allDay: true, startDate: '2026-10-18', endDate: '2026-10-21', startAt: null, endAt: null, rrule: null })
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  test('all day hides the hours', async () => {
    renderDialog(VACANCES)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText(/Début/)).toHaveAttribute('type', 'date')

    await userEvent.click(within(dialog).getByRole('switch', { name: 'Toute la journée' }))

    expect(within(dialog).getByLabelText(/Début/)).toHaveAttribute('type', 'datetime-local')
    expect(within(dialog).getByLabelText(/Début/)).toHaveValue('2026-10-18T09:00')
  })

  test('keeps the API error in the dialog', async () => {
    const { onClose } = renderDialog(VACANCES, { update: (() => Promise.reject(new Error('422'))) as unknown as DataProvider['update'] })
    const dialog = await screen.findByRole('dialog')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(dialog).findByText(/Erreur : la période n'a pas été enregistrée/)).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  test('delete asks for confirmation', async () => {
    const { remove, onClose } = renderDialog(VACANCES)
    const dialog = await screen.findByRole('dialog')

    await userEvent.click(within(dialog).getByRole('button', { name: 'Supprimer' }))
    expect(remove).not.toHaveBeenCalled()

    const confirm = await screen.findByText('Supprimer la période « Vacances » ?')
    await userEvent.click(within(confirm.closest('[role="dialog"]') as HTMLElement).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(remove).toHaveBeenCalled())
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  test('offers the repeat presets and creates a named period', async () => {
    const { create } = renderDialog(null)
    const dialog = await screen.findByRole('dialog')

    await userEvent.type(within(dialog).getByLabelText(/Nom/), 'Garde')
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Répéter' }))
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Toutes les 2 semaines',
      'Jamais',
      'Chaque semaine',
      'Chaque année',
      'Personnalisé…',
    ])
    await userEvent.click(screen.getByRole('option', { name: 'Toutes les 2 semaines' }))
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Enregistrer' })).toBeEnabled())
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalled())
    const params = (create.mock.calls[0] as unknown as [string, { data: Record<string, unknown> }])[1]
    expect(params.data).toMatchObject({ name: 'Garde', agenda: AGENDA, rrule: 'FREQ=WEEKLY;INTERVAL=2' })
  })
})

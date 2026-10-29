import { describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { WorkoutForm } from './WorkoutForm'
import type { Workout } from './workoutFormat'

function renderForm(workout?: Workout, overrides: Partial<DataProvider> = {}) {
  const create = vi.fn((_resource: string, params: { data: Record<string, unknown> }) => Promise.resolve({ data: { id: '/api/workouts/01NEW', ...params.data } }))
  const update = vi.fn((_resource: string, params: { data: Record<string, unknown> }) => Promise.resolve({ data: { id: workout?.id, ...params.data } }))
  const dataProvider = testDataProvider({
    create: create as unknown as DataProvider['create'],
    update: update as unknown as DataProvider['update'],
    ...overrides,
  })
  const onClose = vi.fn()
  const onSaved = vi.fn()
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider} theme={veilleuseLightTheme}>
        <WorkoutForm open workout={workout} onClose={onClose} onSaved={onSaved} />
      </AdminContext>
    </MemoryRouter>,
  )

  return { create, update, onClose, onSaved }
}

const dialog = () => screen.getByRole('dialog')

async function choose(user: ReturnType<typeof userEvent.setup>, field: string, option: string) {
  await user.click(within(dialog()).getByRole('combobox', { name: field }))
  await user.click(await screen.findByRole('option', { name: option }))
}

describe('WorkoutForm', () => {
  test('adds a workout: kilometres are sent as metres, the start with its offset, the source as manual', async () => {
    const user = userEvent.setup()
    const { create, onSaved, onClose } = renderForm()

    expect(within(dialog()).getByRole('heading', { name: 'Ajouter une séance' })).toBeInTheDocument()
    await choose(user, 'Type', 'Vélo')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Titre' }), 'Vélo')
    fireEvent.change(within(dialog()).getByLabelText('Date et heure de début'), { target: { value: '2026-10-18T07:10' } })
    await user.type(within(dialog()).getByRole('textbox', { name: 'Durée (minutes)' }), '65')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Distance (km)' }), '22')
    await user.click(within(dialog()).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    const [resource, params] = create.mock.calls[0]
    expect(resource).toBe('workouts')
    expect(params.data).toMatchObject({
      type: 'bike',
      title: 'Vélo',
      durationMinutes: 65,
      distanceMeters: 22000,
      kcal: null,
      avgHeartRate: null,
      feeling: null,
      notes: null,
      source: 'manual',
    })
    expect(params.data.startedAt).toMatch(/^2026-10-18T07:10:00[+-]\d{2}:\d{2}$/)
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith('/api/workouts/01NEW'))
    expect(onClose).toHaveBeenCalled()
  })

  test('5,4 km is 5400 m', async () => {
    const user = userEvent.setup()
    const { create } = renderForm()

    await choose(user, 'Type', 'Course à pied')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Titre' }), 'Course')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Durée (minutes)' }), '32')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Distance (km)' }), '5,4')
    await choose(user, 'Ressenti', 'Bien')
    await user.click(within(dialog()).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({ distanceMeters: 5400, feeling: 'good' })
  })

  test('refuses an empty form with a message under each required field, and sends nothing', async () => {
    const user = userEvent.setup()
    const { create } = renderForm()

    await user.click(within(dialog()).getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Choisissez le type de séance.')).toBeInTheDocument()
    expect(screen.getByText('Indiquez le titre de la séance.')).toBeInTheDocument()
    expect(screen.getByText('Indiquez la durée en minutes.')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })

  test('refuses a duration out of bounds', async () => {
    const user = userEvent.setup()
    const { create } = renderForm()

    await choose(user, 'Type', 'Marche')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Titre' }), 'Marche')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Durée (minutes)' }), '2000')
    await user.click(within(dialog()).getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('La durée doit être comprise entre 1 et 1 440 minutes.')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })

  test('says in French when the workout was not added', async () => {
    const user = userEvent.setup()
    const { create } = renderForm(undefined, {
      create: (() => Promise.reject(new Error('boom'))) as unknown as DataProvider['create'],
    })

    await choose(user, 'Type', 'Marche')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Titre' }), 'Marche')
    await user.type(within(dialog()).getByRole('textbox', { name: 'Durée (minutes)' }), '30')
    await user.click(within(dialog()).getByRole('button', { name: 'Enregistrer' }))

    expect(create).not.toHaveBeenCalled()
    await waitFor(() => expect(dialog()).toBeInTheDocument())
  })

  test('edits a workout: its values are in the form, the distance in kilometres', async () => {
    const user = userEvent.setup()
    const workout: Workout = {
      id: '/api/workouts/01RUN',
      type: 'run',
      title: 'Course',
      startedAt: new Date(2026, 9, 20, 7, 10).toISOString(),
      durationMinutes: 32,
      distanceMeters: 5400,
      kcal: 386,
      avgHeartRate: 152,
      feeling: 'good',
      notes: null,
      source: 'watch',
    }
    const { update } = renderForm(workout)

    expect(within(dialog()).getByRole('heading', { name: 'Modifier la séance' })).toBeInTheDocument()
    expect(within(dialog()).getByRole('textbox', { name: 'Distance (km)' })).toHaveValue('5,4')
    expect(within(dialog()).getByRole('textbox', { name: 'Titre' })).toHaveValue('Course')

    const duration = within(dialog()).getByRole('textbox', { name: 'Durée (minutes)' })
    await user.clear(duration)
    await user.type(duration, '35')
    await user.click(within(dialog()).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const [, params] = update.mock.calls[0] as unknown as [string, { id: string; data: Record<string, unknown> }]
    expect(params.id).toBe('/api/workouts/01RUN')
    expect(params.data).toMatchObject({ durationMinutes: 35, distanceMeters: 5400, kcal: 386, avgHeartRate: 152, feeling: 'good' })
    expect(params.data).not.toHaveProperty('source')
  })
})

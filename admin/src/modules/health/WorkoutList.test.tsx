import { describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { veilleuseLightTheme } from '../../theme'
import { WorkoutList } from './WorkoutList'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const daysAgo = (days: number, hour = 7) => {
  const at = new Date()
  at.setHours(hour, 10, 0, 0)
  at.setDate(at.getDate() - days)

  return at.toISOString()
}

const RUN = {
  id: '/api/workouts/01RUN',
  type: 'run',
  title: 'Course',
  startedAt: daysAgo(2),
  durationMinutes: 32,
  distanceMeters: 5400,
  kcal: 386,
  avgHeartRate: 152,
  feeling: 'good',
  notes: null,
  source: 'watch',
  externalId: null,
}
const BIKE = {
  id: '/api/workouts/01BIKE',
  type: 'bike',
  title: 'Vélo',
  startedAt: daysAgo(4),
  durationMinutes: 65,
  distanceMeters: 22000,
  kcal: null,
  avgHeartRate: null,
  feeling: null,
  notes: null,
  source: 'manual',
  externalId: null,
}
const PLANNED = {
  id: '/api/workouts/01PLAN',
  type: 'strength',
  title: 'Pompes',
  startedAt: daysAgo(6),
  durationMinutes: 45,
  distanceMeters: null,
  kcal: null,
  avgHeartRate: null,
  feeling: null,
  notes: null,
  source: 'planned',
  externalId: null,
}

function renderList(listed: unknown[] = [RUN, BIKE, PLANNED], overrides: Partial<DataProvider> = {}) {
  const getList = vi.fn((read: string) =>
    Promise.resolve(read === 'workouts' ? { data: listed, total: listed.length } : { data: [], total: 0 }),
  )
  const deleteOne = vi.fn(() => Promise.resolve({ data: {} }))
  const dataProvider = testDataProvider({
    getList: getList as unknown as DataProvider['getList'],
    delete: deleteOne as unknown as DataProvider['delete'],
    ...overrides,
  })
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider} i18nProvider={i18nProvider} theme={veilleuseLightTheme}>
        <ResourceContextProvider value="workouts">
          <WorkoutList />
          <Notification />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

  return { getList, deleteOne }
}

const workoutParams = (getList: ReturnType<typeof renderList>['getList']) =>
  (getList.mock.calls as unknown as [string, { filter: Record<string, string> }][]).filter(([resource]) => resource === 'workouts').map(([, params]) => params)

describe('WorkoutList', () => {
  test('draws the header buttons, the three cards and the table headers in the mockup order', async () => {
    renderList()

    expect(await screen.findByRole('button', { name: 'Ajouter une séance' })).toBeInTheDocument()
    const buttons = screen.getAllByRole('button').map((button) => button.textContent)
    expect(buttons.slice(0, 4)).toEqual(['Type ▾', 'Source ▾', '90 j ▾', 'Ajouter une séance'])
    expect(screen.getByRole('region', { name: 'Minutes par semaine' })).toBeInTheDocument()
    const table = screen.getByRole('region', { name: 'Séances' })
    expect(within(table).getAllByRole('columnheader').map((header) => header.textContent)).toEqual([
      'Date', 'Séance', 'Type', 'Durée', 'Distance', 'kcal', 'FC moy.', 'Source', 'Ressenti',
    ])
    expect(await screen.findByTestId('workout-detail-01RUN')).toBeInTheDocument()
  })

  test('a row shows the formats of the mockup', async () => {
    renderList()

    const row = await screen.findByTestId('workout-row-01RUN')
    const cells = within(row).getAllByRole('cell').map((cell) => cell.textContent)
    expect(cells.slice(1)).toEqual(['Course', 'Course à pied', '32 min', '5,4 km', '386', '152', 'Montre', 'Bien'])
    const bike = screen.getByTestId('workout-row-01BIKE')
    expect(within(bike).getByText('1 h 05')).toBeInTheDocument()
    expect(within(bike).getByText('22,0 km')).toBeInTheDocument()
    expect(within(bike).getByText('Saisie')).toBeInTheDocument()
  })

  test('selecting a row shows only its filled metrics and its feeling', async () => {
    const user = userEvent.setup()
    renderList()

    await user.click(await screen.findByTestId('workout-row-01RUN'))
    const detail = screen.getByTestId('workout-detail-01RUN')
    expect(within(detail).getByText('152 bpm')).toBeInTheDocument()
    expect(within(detail).getByText('Bien')).toBeInTheDocument()

    await user.click(screen.getByTestId('workout-row-01BIKE'))
    const bike = screen.getByTestId('workout-detail-01BIKE')
    expect(within(bike).queryByText(/bpm/)).not.toBeInTheDocument()
    expect(within(bike).getAllByTestId('workout-metric')).toHaveLength(2)

    await user.click(screen.getByTestId('workout-row-01PLAN'))
    expect(within(screen.getByTestId('workout-detail-01PLAN')).queryByText('Ressenti')).not.toBeInTheDocument()
  })

  test('the filters and the period go to the API', async () => {
    const user = userEvent.setup()
    const { getList } = renderList()
    await screen.findByTestId('workout-row-01RUN')
    expect(workoutParams(getList)[0].filter).toHaveProperty('startedAt[after]')

    await user.click(screen.getByRole('button', { name: 'Type ▾' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Vélo' }))
    await waitFor(() => expect(workoutParams(getList).at(-1)?.filter).toMatchObject({ type: 'bike' }))

    await user.click(screen.getByRole('button', { name: 'Source ▾' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Saisie' }))
    await waitFor(() => expect(workoutParams(getList).at(-1)?.filter).toMatchObject({ type: 'bike', source: 'manual' }))

    const before = workoutParams(getList).at(-1)?.filter['startedAt[after]']
    await user.click(screen.getByRole('button', { name: '90 j ▾' }))
    await user.click(await screen.findByRole('menuitem', { name: '30 j' }))
    await waitFor(() => expect(workoutParams(getList).at(-1)?.filter['startedAt[after]']).not.toBe(before))
    expect(screen.getByRole('button', { name: '30 j ▾' })).toBeInTheDocument()
  })

  test('« Ajouter une séance » opens the form', async () => {
    const user = userEvent.setup()
    renderList()

    await user.click(await screen.findByRole('button', { name: 'Ajouter une séance' }))

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  test('says so when there is no workout, and when a filter finds none', async () => {
    const user = userEvent.setup()
    renderList([])

    expect(await screen.findByText('Aucune séance pour l’instant')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Type ▾' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Natation' }))
    expect(await screen.findByText('Aucune séance pour ce filtre')).toBeInTheDocument()
  })

  test('says in French when the workouts cannot be read', async () => {
    renderList([], { getList: (() => Promise.reject(new Error('boom'))) as unknown as DataProvider['getList'] })

    expect(await screen.findByText('Erreur : les séances n’ont pas pu être chargées.')).toBeInTheDocument()
  })

  test('deleting is undoable: nothing is deleted while « Annuler » can still be pressed', async () => {
    const user = userEvent.setup()
    const { deleteOne } = renderList()

    await user.click(await screen.findByRole('button', { name: 'Supprimer Course' }))
    await user.click(await screen.findByRole('button', { name: 'Annuler' }))

    expect(deleteOne).not.toHaveBeenCalled()
    expect(screen.getByTestId('workout-row-01RUN')).toBeInTheDocument()
  })
})

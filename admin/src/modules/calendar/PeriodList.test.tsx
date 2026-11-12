import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { PeriodList } from './PeriodList'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

const AGENDA = '/api/agendas/famille'
const AGENDAS = [{ id: AGENDA, name: 'Famille', color: '#e91e63', default: true }]
const PERIODS = [
  { id: '/api/periods/garde', name: 'Garde', agenda: AGENDA, allDay: false, startAt: '2026-10-23T18:00:00+02:00', endAt: '2026-10-30T18:00:00+01:00', rrule: 'FREQ=WEEKLY;INTERVAL=2' },
  { id: '/api/periods/vacances', name: 'Vacances', agenda: AGENDA, allDay: true, startDate: '2026-10-18', endDate: '2026-10-26', rrule: null },
]

function renderPeriods(listed: unknown[] = PERIODS, overrides: Partial<DataProvider> = {}) {
  const dataProvider = testDataProvider({
    getList: ((resource: string) =>
      Promise.resolve(resource === 'agendas' ? { data: AGENDAS, total: 1 } : { data: listed, total: listed.length })) as unknown as DataProvider['getList'],
    getOne: (() => Promise.resolve({ data: PERIODS[0] })) as unknown as DataProvider['getOne'],
    ...overrides,
  })
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider}>
        <ResourceContextProvider value="periods">
          <PeriodList />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )
}

describe('PeriodList (MAG-816)', () => {
  beforeEach(() => vi.stubEnv('TZ', 'Europe/Paris'))
  afterEach(() => vi.unstubAllEnvs())

  test('renders the columns and formats of the mockup', async () => {
    renderPeriods()
    const rows = await screen.findAllByTestId('period-row')

    expect(screen.getByRole('heading', { name: 'Périodes' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nouvelle période' })).toBeInTheDocument()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Nom', 'Début', 'Fin', 'Répétition', 'Agenda'])

    const garde = rows.find((r) => r.dataset.period === 'Garde') as HTMLElement
    expect(within(garde).getByText(/^ven\. 23\/10 \d\d:\d\d$/)).toBeInTheDocument()
    expect(within(garde).getByText('Toutes les 2 semaines')).toBeInTheDocument()
    expect(within(garde).getByText('Famille')).toBeInTheDocument()

    const vacances = rows.find((r) => r.dataset.period === 'Vacances') as HTMLElement
    expect(within(vacances).getByText('18/10/2026')).toBeInTheDocument()
    // The end day is excluded in the API and shown included.
    expect(within(vacances).getByText('25/10/2026')).toBeInTheDocument()
    expect(within(vacances).getByText('Jamais')).toBeInTheDocument()
  })

  test('opens a centred dialog on Nouvelle période', async () => {
    renderPeriods()
    await screen.findAllByTestId('period-row')

    await userEvent.click(screen.getByRole('button', { name: 'Nouvelle période' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Nouvelle période')).toBeInTheDocument()
    expect(screen.queryByTestId('period-panel')).toBeNull()
  })

  test('opens the dialog on a row click', async () => {
    renderPeriods()
    const rows = await screen.findAllByTestId('period-row')

    await userEvent.click(rows.find((r) => r.dataset.period === 'Vacances') as HTMLElement)

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Vacances', { selector: 'h2' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Supprimer' })).toBeInTheDocument()
  })

  test('shows the empty and error states', async () => {
    renderPeriods([])
    expect(await screen.findByText("Aucune période pour l'instant")).toBeInTheDocument()
  })

  test('shows the error state in French', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    renderPeriods([], {
      getList: ((resource: string) =>
        resource === 'agendas' ? Promise.resolve({ data: AGENDAS, total: 1 }) : Promise.reject(new Error('boom'))) as unknown as DataProvider['getList'],
    })
    expect(await screen.findByText("Erreur : les périodes n'ont pas pu être chargées.")).toBeInTheDocument()
  })
})

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { veilleuseLightTheme } from '../../theme'
import { SuiviPage } from './SuiviPage'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const at = (month: number, day: number, hour = 7, minute = 0) => new Date(2026, month - 1, day, hour, minute).toISOString()

const measurement = (ulid: string, measuredAt: string, weightKg: number | null, bodyFatPercent: number | null, excluded = false) => ({
  id: `/api/body-measurements/${ulid}`,
  measuredAt,
  weightKg,
  bodyFatPercent,
  waistCm: null,
  source: 'manual',
  externalId: null,
  excluded,
})

const TODAY = measurement('01TODAY', at(10, 21, 7, 12), 72.4, 21.3)
const YESTERDAY = measurement('01YEST', at(10, 20), 71.8, 21.5)
const EXCLUDED = measurement('01BAD', at(10, 18), 90.0, null, true)
const OLDER = measurement('01OLD', at(10, 15), 73.0, null)
// Before the 30 days: read for the trend of the first column, not listed.
const EARLY = measurement('01EARLY', at(9, 20), 74.0, null)

const ENERGY_ROW = (date: string, over: Record<string, unknown> = {}) => ({
  date,
  steps: null,
  activeKcal: null,
  bmrKcal: 1700,
  workoutKcal: 0,
  stepsKcal: null,
  expendedKcal: 1700,
  ...over,
})

/** The daily energy of the range asked: one row per day, today with 8 000 steps. */
function stubEnergy(options: { fail?: boolean; withSteps?: boolean } = {}) {
  const { fail = false, withSteps = true } = options
  const fetcher = vi.fn((url: string) => {
    if (fail) return Promise.resolve(new Response('{}', { status: 500 }))
    const { searchParams } = new URL(url, 'http://localhost')
    const from = searchParams.get('from') ?? ''
    const to = searchParams.get('to') ?? ''
    const rows = []
    for (let d = new Date(`${from}T12:00:00Z`); d <= new Date(`${to}T12:00:00Z`); d.setUTCDate(d.getUTCDate() + 1)) {
      const date = d.toISOString().slice(0, 10)
      rows.push(
        date === to && withSteps
          ? ENERGY_ROW(date, { steps: 8000, activeKcal: 400, stepsKcal: 250, expendedKcal: 1950 })
          : ENERGY_ROW(date),
      )
    }

    return Promise.resolve(new Response(JSON.stringify(rows), { status: 200 }))
  })
  vi.stubGlobal('fetch', fetcher)

  return fetcher
}

function renderPage(listed: unknown[] = [TODAY, YESTERDAY, EXCLUDED, OLDER, EARLY], overrides: Partial<DataProvider> = {}) {
  const getList = vi.fn(() => Promise.resolve({ data: listed, total: listed.length }))
  const update = vi.fn((_resource: string, params: { data: Record<string, unknown>; previousData: Record<string, unknown> }) =>
    Promise.resolve({ data: { ...params.previousData, ...params.data } }),
  )
  const dataProvider = testDataProvider({
    getList: getList as unknown as DataProvider['getList'],
    update: update as unknown as DataProvider['update'],
    ...overrides,
  })
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider} i18nProvider={i18nProvider} theme={veilleuseLightTheme}>
        <SuiviPage />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

  return { getList, update }
}

type ListParams = { filter: Record<string, string>; pagination: { perPage: number } }
const lastRead = (getList: ReturnType<typeof renderPage>['getList']) => (getList.mock.calls.at(-1) as unknown as [string, ListParams])

const weightCard = () => screen.getByRole('region', { name: 'Poids' })

describe('SuiviPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 21, 15, 0))
    stubEnergy()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('the period is a radiogroup, 30 j checked, and reads from six days before the period, 1 000 at most', async () => {
    const user = userEvent.setup()
    const { getList } = renderPage()

    const group = await screen.findByRole('radiogroup', { name: 'Période' })
    expect(within(group).getAllByRole('radio').map((radio) => radio.textContent)).toEqual(['7 j', '30 j', '90 j', '1 an'])
    expect(within(group).getByRole('radio', { name: '30 j' })).toHaveAttribute('aria-checked', 'true')
    await waitFor(() => expect(getList).toHaveBeenCalled())
    const [resource, params] = lastRead(getList)
    expect(resource).toBe('body-measurements')
    expect(params.filter).toEqual({ 'measuredAt[after]': new Date(2026, 8, 16).toISOString() })
    expect(params.pagination.perPage).toBe(1000)

    await user.click(within(group).getByRole('radio', { name: '7 j' }))
    expect(within(group).getByRole('radio', { name: '7 j' })).toHaveAttribute('aria-checked', 'true')
    await waitFor(() => expect(lastRead(getList)[1].filter).toEqual({ 'measuredAt[after]': new Date(2026, 9, 9).toISOString() }))
    expect(within(weightCard()).getAllByTestId('weight-column')).toHaveLength(7)
  })

  test('the Poids card shows today: its weight, the trend without the excluded weighing, the body fat', async () => {
    renderPage()

    const card = await screen.findByRole('region', { name: 'Poids' })
    await waitFor(() => expect(within(card).getByTestId('weight-value')).toHaveTextContent('72,4 kg'))
    expect(within(card).getByTestId('weight-label')).toHaveTextContent('Aujourd’hui')
    // (72,4 + 71,8 + 73,0) / 3: the 90 kg of the 18th is excluded.
    expect(within(card).getByTestId('weight-trend')).toHaveTextContent('— tendance 72,4 kg')
    expect(within(card).getByTestId('weight-fat')).toHaveTextContent('· masse grasse 21,3 %')
    expect(within(card).getAllByTestId('weight-column')).toHaveLength(30)
    expect(within(card).getByRole('button', { name: /^dim\. 18 oct\. : pas de pesée/ })).toBeInTheDocument()
    expect(within(card).getByText('22 sept.')).toBeInTheDocument()
    expect(within(card).getByText('21 oct.')).toBeInTheDocument()
    expect(within(card).getByTestId('weight-delta')).toHaveTextContent('−1,6 kg sur la période')
    expect(within(card).getByTestId('weight-dots').getAttribute('d')?.match(/M/g)).toHaveLength(3)
  })

  test('clicking a column shows that day in the header; hovering does too', async () => {
    const user = userEvent.setup()
    renderPage()

    const card = await screen.findByRole('region', { name: 'Poids' })
    const yesterday = await within(card).findByRole('button', { name: 'mar. 20 oct. : 71,8 kg, tendance 72,4 kg' })
    await user.click(yesterday)

    expect(yesterday).toHaveAttribute('aria-pressed', 'true')
    expect(within(card).getByTestId('weight-label')).toHaveTextContent('mar. 20 oct.')
    expect(within(card).getByTestId('weight-value')).toHaveTextContent('71,8 kg')
    expect(within(card).getByTestId('weight-fat')).toHaveTextContent('21,5 %')

    await user.hover(within(card).getByRole('button', { name: /^dim\. 18 oct\./ }))
    expect(within(card).getByTestId('weight-value')).toHaveTextContent('—')
    expect(within(card).getByTestId('weight-fat')).toHaveTextContent('· masse grasse —')
  })

  test('« Pesées » lists the period newest first; an excluded one is struck through, with « Réintégrer »', async () => {
    renderPage()

    const card = await screen.findByRole('region', { name: 'Pesées' })
    await waitFor(() => expect(within(card).getAllByRole('row')).toHaveLength(5))
    expect(within(card).getAllByRole('columnheader').map((header) => header.textContent)).toEqual(['Date', 'Poids', 'Masse grasse', 'Actions'])
    const rows = within(card).getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getAllByRole('cell')[0].textContent)).toEqual(['mer. 21 oct.', 'mar. 20 oct.', 'dim. 18 oct.', 'jeu. 15 oct.'])
    expect(within(rows[0]).getAllByRole('cell').slice(1, 3).map((cell) => cell.textContent)).toEqual(['72,4 kg', '21,3 %'])
    expect(within(rows[3]).getAllByRole('cell')[2]).toHaveTextContent('—')

    const excluded = screen.getByTestId('weighing-row-01BAD')
    expect(excluded).toHaveAttribute('data-excluded', 'true')
    expect(excluded).toHaveStyle({ opacity: '0.5' })
    expect(within(excluded).getAllByRole('cell')[0]).toHaveStyle({ textDecoration: 'line-through' })
    expect(within(excluded).getByRole('button', { name: 'Réintégrer la pesée du dim. 18 oct.' })).toBeInTheDocument()
    expect(within(screen.getByTestId('weighing-row-01TODAY')).getByRole('button', { name: 'Écarter la pesée du mer. 21 oct.' })).toBeInTheDocument()
  })

  test('« Écarter » sends excluded: true for that weighing, « Réintégrer » sends false', async () => {
    const user = userEvent.setup()
    const { update } = renderPage()

    await user.click(await screen.findByRole('button', { name: 'Écarter la pesée du mer. 21 oct.' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const [resource, params] = update.mock.calls[0] as unknown as [string, { id: string; data: Record<string, unknown> }]
    expect(resource).toBe('body-measurements')
    expect(params.id).toBe('/api/body-measurements/01TODAY')
    expect(params.data).toEqual({ excluded: true })

    await user.click(screen.getByRole('button', { name: 'Réintégrer la pesée du dim. 18 oct.' }))
    await waitFor(() => expect(update).toHaveBeenCalledTimes(2))
    expect((update.mock.calls[1] as unknown as [string, { id: string; data: Record<string, unknown> }])[1]).toMatchObject({
      id: '/api/body-measurements/01BAD',
      data: { excluded: false },
    })
  })

  test('says in French when the weighing could not be excluded', async () => {
    const user = userEvent.setup()
    renderPage(undefined, { update: (() => Promise.reject(new Error('boom'))) as unknown as DataProvider['update'] })

    await user.click(await screen.findByRole('button', { name: 'Écarter la pesée du mer. 21 oct.' }))

    expect(await screen.findByText('Erreur : la pesée n’a pas été écartée.')).toBeInTheDocument()
  })

  test('« Ajouter une pesée » and « Modifier » open the form', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Ajouter une pesée' }))
    expect(await screen.findByRole('heading', { name: 'Ajouter une pesée' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Annuler' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Modifier la pesée du mar. 20 oct.' }))
    expect(await screen.findByRole('heading', { name: 'Modifier la pesée' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Poids (kg)' })).toHaveValue('71,8')
  })

  test('says so when there is no weighing', async () => {
    renderPage([])
    expect(await screen.findByText('Aucune pesée sur la période')).toBeInTheDocument()
    expect(within(weightCard()).getByTestId('weight-value')).toHaveTextContent('—')
    expect(within(weightCard()).getByTestId('weight-delta')).toHaveTextContent('— sur la période')
  })

  test('says in French when the weighings cannot be read', async () => {
    renderPage([], { getList: (() => Promise.reject(new Error('boom'))) as unknown as DataProvider['getList'] })

    expect(await screen.findByText('Erreur : les pesées n’ont pas pu être chargées.')).toBeInTheDocument()
  })

  test('the Pas and Énergie cards read the period ending today, and read again when it changes', async () => {
    const user = userEvent.setup()
    const fetcher = stubEnergy()
    renderPage()

    const steps = await screen.findByRole('region', { name: 'Pas' })
    await waitFor(() => expect(within(steps).getAllByTestId('steps-bar')).toHaveLength(30))
    expect(String(fetcher.mock.calls.at(-1)?.[0])).toBe('/api/health/daily-energy?from=2026-09-22&to=2026-10-21')
    expect(within(steps).getByTestId('steps-value')).toHaveTextContent('8 000')

    await user.click(within(screen.getByRole('radiogroup', { name: 'Période' })).getByRole('radio', { name: '7 j' }))
    await waitFor(() => expect(within(steps).getAllByTestId('steps-bar')).toHaveLength(7))
    expect(String(fetcher.mock.calls.at(-1)?.[0])).toBe('/api/health/daily-energy?from=2026-10-15&to=2026-10-21')
  })

  test('picking a day updates both cards; changing the period resets it', async () => {
    const user = userEvent.setup()
    renderPage()

    const steps = await screen.findByRole('region', { name: 'Pas' })
    const energy = screen.getByRole('region', { name: 'Énergie' })
    await waitFor(() => expect(within(energy).getByTestId('energy-total')).toHaveTextContent('1 950 kcal'))
    expect(within(energy).getByTestId('energy-detail')).toHaveTextContent('base 1 700 kcal · pas 250 kcal · séances 0 kcal')

    await user.click(await within(steps).findByRole('button', { name: 'mar. 20 oct. : pas de donnée' }))
    expect(within(steps).getByTestId('steps-label')).toHaveTextContent('mar. 20 oct.')
    expect(within(steps).getByTestId('steps-value')).toHaveTextContent('—')
    expect(within(energy).getByTestId('energy-label')).toHaveTextContent('mar. 20 oct.')
    expect(within(energy).getByTestId('energy-total')).toHaveTextContent('1 700 kcal')

    await user.click(within(screen.getByRole('radiogroup', { name: 'Période' })).getByRole('radio', { name: '90 j' }))
    await waitFor(() => expect(within(steps).getByTestId('steps-label')).toHaveTextContent('Moyenne par jour'))
  })

  test('the Pas card is sober when no day has steps', async () => {
    stubEnergy({ withSteps: false })
    renderPage()

    expect(await screen.findByText('Aucune donnée de pas')).toBeInTheDocument()
  })

  test('says in French when the energy cannot be read', async () => {
    stubEnergy({ fail: true })
    renderPage()

    expect(await screen.findByText('Erreur : l’énergie n’a pas pu être chargée.')).toBeInTheDocument()
    expect(screen.getByText('Erreur : les pas n’ont pas pu être chargés.')).toBeInTheDocument()
  })
})

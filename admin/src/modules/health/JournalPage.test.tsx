import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { veilleuseLightTheme } from '../../theme'
import { JournalPage } from './JournalPage'
import type { HealthDay } from './healthDaysApi'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const TODAY = '2026-11-07'
const YESTERDAY = '2026-11-06'

const empty = (date: string): HealthDay => ({
  id: null,
  date,
  weightKg: null,
  bodyFatPercent: null,
  weighedAt: null,
  steps: null,
  eatenKcal: null,
  expendedKcal: null,
  balanceKcal: null,
  sportMinutes: null,
  mood: null,
  fatigue: null,
  note: null,
  notedBy: null,
  notedAt: null,
})

const TODAY_ENTRY: HealthDay = {
  ...empty(TODAY),
  id: '01TODAY',
  weightKg: 71.6,
  bodyFatPercent: 21.3,
  weighedAt: '2026-11-07T07:12:00+01:00',
  steps: 8000,
  eatenKcal: 1580,
  expendedKcal: 1900,
  balanceKcal: -320,
  sportMinutes: 45,
}

const YESTERDAY_ENTRY: HealthDay = {
  ...empty(YESTERDAY),
  id: '01YEST',
  weightKg: 71.9,
  mood: 6,
  fatigue: 5,
  note: 'Longue journée de travail, mais une belle balade en forêt le soir avec les enfants.',
  notedBy: 'maggie',
  notedAt: '2026-11-06T21:42:00+01:00',
}

interface Server {
  days: HealthDay[]
  fail?: boolean
  /** What a PATCH answers instead of recording it. */
  refuse?: boolean
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** The journal's API: the range, one day, and the PATCH that records mood, fatigue and the note. */
function stubApi(server: Server) {
  const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
    const { pathname, searchParams } = new URL(url, 'http://localhost')
    const method = init.method ?? 'GET'
    if (server.fail) return json({ error: 'boom' }, 500)
    if (pathname === '/api/health/days' && method === 'GET') {
      const from = searchParams.get('from') ?? ''
      const to = searchParams.get('to') ?? ''

      return json({ days: server.days.filter((day) => day.date >= from && day.date <= to).sort((a, b) => a.date.localeCompare(b.date)) })
    }
    const date = pathname.replace('/api/health/days/', '')
    if (method === 'GET') return json(server.days.find((day) => day.date === date) ?? empty(date))
    if (method === 'PATCH') {
      const body = JSON.parse(String(init.body)) as Partial<HealthDay>
      if (server.refuse) return json({ error: 'mood: must be between 1 and 10.' }, 400)
      const current = server.days.find((day) => day.date === date) ?? { ...empty(date), id: '01NEW' }
      const next: HealthDay = { ...current, ...body, notedBy: 'user', notedAt: '2026-11-07T15:00:00+01:00' }
      server.days = [...server.days.filter((day) => day.date !== date), next]

      return json(next)
    }

    return json({}, 404)
  })
  vi.stubGlobal('fetch', fetcher)

  return fetcher
}

const patches = (fetcher: ReturnType<typeof stubApi>) =>
  fetcher.mock.calls
    .filter(([, init]) => init?.method === 'PATCH')
    .map(([url, init]) => [url, JSON.parse(String(init?.body)), (init?.headers as Record<string, string>)['Content-Type']])

function renderPage() {
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()} i18nProvider={i18nProvider} theme={veilleuseLightTheme}>
        <JournalPage />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )
}

const entry = () => screen.getByRole('region', { name: 'Entrée du jour' })
const table = () => screen.getByRole('region', { name: 'Jours' })
const scale = (name: 'Moral' | 'Fatigue') => within(entry()).getByRole('radiogroup', { name })

describe('JournalPage', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 7, 15, 0))
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  test('lists the days of the last 30 days, newest first, and opens on today with its figures', async () => {
    const fetcher = stubApi({ days: [YESTERDAY_ENTRY, TODAY_ENTRY] })
    renderPage()

    expect(screen.getAllByRole('region').map((region) => region.getAttribute('aria-label'))).toEqual(['Jours', 'Entrée du jour'])
    expect(within(screen.getByRole('radiogroup', { name: 'Période' })).getByRole('radio', { name: '30 j' })).toHaveAttribute('aria-checked', 'true')
    expect(within(entry()).getByTestId('journal-day')).toHaveTextContent('Aujourd’hui samedi 7 nov.')

    const rows = await within(table()).findAllByTestId(/^journal-row-/)
    expect(rows.map((row) => row.getAttribute('data-testid'))).toEqual([`journal-row-${TODAY}`, `journal-row-${YESTERDAY}`])
    expect(within(table()).getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
      'Date',
      'Moral',
      'Fatigue',
      'Poids',
      'Pas',
      'Bilan',
      'Sport',
      'Note',
    ])
    expect(rows[0]).toHaveTextContent(/^Aujourd’hui——71,6 kg8\s000−320 kcal45 min—$/)
    expect(rows[1]).toHaveTextContent(/^ven\. 6 nov\.6571,9 kg———Longue journée de travail, mais une bell…$/)
    expect(fetcher.mock.calls.map(([url]) => url)).toEqual(expect.arrayContaining(['/api/health/days?from=2026-10-09&to=2026-11-07', '/api/health/days/2026-11-07']))

    expect(await within(entry()).findByTestId('journal-weight-value')).toHaveTextContent('71,6 kg')
    expect(within(entry()).getByTestId('journal-weight-detail')).toHaveTextContent('pesé à 07:12')
    expect(within(entry()).getByTestId('journal-steps-value')).toHaveTextContent(/^8\s000$/)
    expect(within(entry()).getByTestId('journal-balance-value')).toHaveTextContent('−320 kcal')
    expect(within(entry()).getByTestId('journal-sport-value')).toHaveTextContent('45 min')
    expect(within(entry()).getByTestId('journal-weight')).toHaveAttribute('href', '/health/suivi')
    expect(within(entry()).getByTestId('journal-steps')).toHaveAttribute('href', '/health/suivi')
    expect(within(entry()).getByTestId('journal-balance')).toHaveAttribute('href', '/health/nutrition')
    expect(within(entry()).getByTestId('journal-sport')).toHaveAttribute('href', '/workouts')
    expect(within(scale('Moral')).getAllByRole('radio').map((radio) => radio.textContent)).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '10'])
    expect(within(scale('Moral')).queryByRole('radio', { checked: true })).not.toBeInTheDocument()
    expect(within(entry()).queryByTestId('journal-noted-by')).not.toBeInTheDocument()
    expect(within(entry()).getByRole('button', { name: 'Jour suivant' })).toBeDisabled()
  })

  test('a click on a scale writes the score at once', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ days: [TODAY_ENTRY] })
    renderPage()

    await within(entry()).findByTestId('journal-weight-value')
    await user.click(within(scale('Moral')).getByRole('radio', { name: '7' }))
    await waitFor(() => expect(within(scale('Moral')).getByRole('radio', { name: '7' })).toHaveAttribute('aria-checked', 'true'))
    await user.click(within(scale('Fatigue')).getByRole('radio', { name: '4' }))
    await waitFor(() => expect(within(scale('Fatigue')).getByRole('radio', { name: '4' })).toHaveAttribute('aria-checked', 'true'))

    expect(patches(fetcher)).toEqual([
      ['/api/health/days/2026-11-07', { mood: 7 }, 'application/json'],
      ['/api/health/days/2026-11-07', { fatigue: 4 }, 'application/json'],
    ])
    await waitFor(() => expect(within(table()).getByTestId(`journal-row-${TODAY}`)).toHaveTextContent(/^Aujourd’hui7471,6 kg/))
  })

  test('the checked score, clicked again, clears it', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ days: [{ ...TODAY_ENTRY, mood: 7 }] })
    renderPage()

    await waitFor(() => expect(within(scale('Moral')).getByRole('radio', { name: '7' })).toHaveAttribute('aria-checked', 'true'))
    await user.click(within(scale('Moral')).getByRole('radio', { name: '7' }))

    await waitFor(() => expect(within(scale('Moral')).queryByRole('radio', { checked: true })).not.toBeInTheDocument())
    expect(patches(fetcher)).toEqual([['/api/health/days/2026-11-07', { mood: null }, 'application/json']])
  })

  test('the note is saved with « Enregistrer »', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ days: [TODAY_ENTRY] })
    renderPage()

    const note = await within(entry()).findByRole('textbox', { name: 'Note' })
    const save = within(entry()).getByRole('button', { name: 'Enregistrer' })
    expect(save).toBeDisabled()
    await user.type(note, '  Bien dormi, séance facile.  ')
    await user.click(save)

    await waitFor(() => expect(patches(fetcher)).toEqual([['/api/health/days/2026-11-07', { note: 'Bien dormi, séance facile.' }, 'application/json']]))
    await waitFor(() => expect(within(entry()).getByRole('button', { name: 'Enregistrer' })).toBeDisabled())
    expect(within(entry()).getByRole('textbox', { name: 'Note' })).toHaveValue('Bien dormi, séance facile.')
    await waitFor(() => expect(within(table()).getByTestId(`journal-row-${TODAY}`)).toHaveTextContent('Bien dormi, séance facile.'))
  })

  test('a click on a row chooses the day; Maggie’s entry says « avec Maggie » and when', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ days: [YESTERDAY_ENTRY, TODAY_ENTRY] })
    renderPage()

    await user.click(await within(table()).findByTestId(`journal-row-${YESTERDAY}`))

    expect(within(entry()).getByTestId('journal-day')).toHaveTextContent('vendredi 6 nov.')
    expect(await within(entry()).findByTestId('journal-noted-by')).toHaveTextContent('avec Maggie · 21:42')
    expect(within(scale('Moral')).getByRole('radio', { name: '6' })).toHaveAttribute('aria-checked', 'true')
    expect(within(scale('Fatigue')).getByRole('radio', { name: '5' })).toHaveAttribute('aria-checked', 'true')
    expect(within(entry()).getByRole('textbox', { name: 'Note' })).toHaveValue(YESTERDAY_ENTRY.note)
    expect(within(entry()).getByTestId('journal-steps-value')).toHaveTextContent('—')
    expect(within(table()).getByTestId(`journal-row-${YESTERDAY}`)).toHaveClass('Mui-selected')
    expect(fetcher.mock.calls.map(([url]) => url)).toContain('/api/health/days/2026-11-06')

    await user.click(within(entry()).getByRole('button', { name: 'Jour suivant' }))
    expect(within(entry()).getByTestId('journal-day')).toHaveTextContent('Aujourd’hui samedi 7 nov.')
    await user.click(within(entry()).getByRole('button', { name: 'Jour précédent' }))
    await user.click(within(entry()).getByRole('button', { name: 'Jour précédent' }))
    expect(within(entry()).getByTestId('journal-day')).toHaveTextContent('jeudi 5 nov.')
    expect(await within(entry()).findByTestId('journal-weight-value')).toHaveTextContent('—')
  })

  test('a refused score says so in French and leaves the scale as it was', async () => {
    const user = userEvent.setup()
    stubApi({ days: [TODAY_ENTRY], refuse: true })
    renderPage()

    await within(entry()).findByTestId('journal-weight-value')
    await user.click(within(scale('Moral')).getByRole('radio', { name: '7' }))

    expect(await screen.findByText('Erreur : la note du jour n’a pas été enregistrée.')).toBeInTheDocument()
    expect(within(scale('Moral')).queryByRole('radio', { checked: true })).not.toBeInTheDocument()
    expect(screen.queryByText(/must be between/)).not.toBeInTheDocument()
  })

  test('a refused note says so in French and keeps what was typed', async () => {
    const user = userEvent.setup()
    stubApi({ days: [TODAY_ENTRY], refuse: true })
    renderPage()

    await user.type(await within(entry()).findByRole('textbox', { name: 'Note' }), 'Fatigué')
    await user.click(within(entry()).getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Erreur : la note n’a pas été enregistrée.')).toBeInTheDocument()
    expect(within(entry()).getByRole('textbox', { name: 'Note' })).toHaveValue('Fatigué')
  })

  test('an empty period says so, and today shows « — » everywhere', async () => {
    stubApi({ days: [] })
    renderPage()

    expect(await within(table()).findByText('Aucun jour noté sur la période.')).toBeInTheDocument()
    expect(await within(entry()).findByTestId('journal-weight-value')).toHaveTextContent('—')
    expect(within(entry()).queryByTestId('journal-weight-detail')).not.toBeInTheDocument()
    expect(within(entry()).getByTestId('journal-balance-value')).toHaveTextContent('—')
    expect(within(entry()).getByTestId('journal-sport-value')).toHaveTextContent('—')
  })

  test('says in French when the journal cannot be read', async () => {
    stubApi({ days: [], fail: true })
    renderPage()

    expect(await within(table()).findByText('Erreur : le journal n’a pas pu être chargé.')).toBeInTheDocument()
    expect(within(entry()).getByText('Erreur : le jour n’a pas pu être chargé.')).toBeInTheDocument()
  })

  test('the period changes the days read', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ days: [TODAY_ENTRY] })
    renderPage()

    await within(table()).findByTestId(`journal-row-${TODAY}`)
    await user.click(within(screen.getByRole('radiogroup', { name: 'Période' })).getByRole('radio', { name: '7 j' }))

    await waitFor(() => expect(fetcher.mock.calls.map(([url]) => url)).toContain('/api/health/days?from=2026-11-01&to=2026-11-07'))
  })
})

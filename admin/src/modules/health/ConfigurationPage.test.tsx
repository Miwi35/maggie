import { afterEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { veilleuseLightTheme } from '../../theme'
import { ConfigurationPage } from './ConfigurationPage'
import type { HealthProfile } from './healthProfileApi'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

/** The ticket's criterion: Homme, 38 ans, 178 cm, 72 kg, Sédentaire → 1 648 and 1 980 kcal. */
const CRITERION: HealthProfile = {
  id: '01HPROFILE',
  sex: 'male',
  birthYear: new Date().getFullYear() - 38,
  age: 38,
  heightCm: 178,
  weightKg: 72,
  activityLevel: 'sedentary',
  mode: 'computed',
  enteredNeedKcal: null,
  bmrKcal: 1648,
  needKcal: 1980,
  factor: 1.2,
  stepGoal: 8000,
  suggestionWindowStart: '08:00',
  suggestionWindowEnd: '20:00',
  sessionMinutes: 20,
  stepSource: 'health_connect',
}

/** What GET answers before the profile is ever written. */
const NONE: HealthProfile = {
  ...CRITERION,
  id: null,
  sex: null,
  birthYear: null,
  age: null,
  heightCm: null,
  weightKg: null,
  bmrKcal: null,
  needKcal: null,
  factor: null,
  sessionMinutes: 20,
  stepSource: 'none',
}

const json = (body: unknown, status = 200) =>
  Promise.resolve({ ok: status < 400, status, json: () => Promise.resolve(body) } as Response)

type Answer = (patch: Record<string, unknown>, current: HealthProfile) => Promise<Response>

/** The API: GET gives `profile`; a PATCH answers with `answer`, by default the profile with the fields sent. */
const stubApi = (profile: HealthProfile, answer?: Answer) => {
  let current = profile
  const fetcher = vi.fn((_url: string, init?: RequestInit) => {
    if (init?.method === 'PATCH') {
      const patch = JSON.parse(String(init.body)) as Record<string, unknown>
      if (answer) return answer(patch, current)
      current = { ...current, ...patch }

      return json(current)
    }

    return json(current)
  })
  vi.stubGlobal('fetch', fetcher)

  return fetcher
}

const patches = (fetcher: ReturnType<typeof stubApi>) =>
  fetcher.mock.calls.filter(([, init]) => init?.method === 'PATCH').map(([, init]) => JSON.parse(String(init?.body)))

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()} i18nProvider={i18nProvider} theme={veilleuseLightTheme}>
        <ConfigurationPage />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

const profileCard = () => screen.getByRole('region', { name: 'Profil' })

afterEach(() => vi.unstubAllGlobals())

describe('ConfigurationPage', () => {
  test('draws the mockup: the profile, the day’s need and the activity settings', async () => {
    stubApi(CRITERION)
    renderPage()

    const profile = await screen.findByRole('region', { name: 'Profil' })
    const mode = within(profile).getByRole('radiogroup', { name: 'Mode' })
    expect(within(mode).getAllByRole('radio').map((radio) => radio.textContent)).toEqual(['Calculé', 'Saisi'])
    expect(within(mode).getByRole('radio', { name: 'Calculé' })).toHaveAttribute('aria-checked', 'true')
    expect(within(profile).getByLabelText('Sexe')).toHaveDisplayValue('Homme')
    expect(within(profile).getByLabelText('Âge')).toHaveValue('38')
    expect(within(profile).getByText('ans')).toBeInTheDocument()
    expect(within(profile).getByLabelText('Taille')).toHaveValue('178')
    expect(within(profile).getByText('cm')).toBeInTheDocument()
    expect(within(profile).getByLabelText('Poids')).toHaveValue('72')
    expect(within(profile).getByText('kg')).toBeInTheDocument()
    expect(within(profile).getByLabelText('Activité')).toHaveDisplayValue('Sédentaire · × 1,2')
    expect(within(profile).getByText('Mifflin-St Jeor')).toBeInTheDocument()
    expect(within(profile).queryByLabelText('Besoin du jour')).toBeNull()

    const need = screen.getByRole('region', { name: 'Besoin du jour' })
    expect(within(need).getByTestId('need-kcal').textContent).toMatch(/^1\s980 kcal$/)
    expect(within(need).getByText('besoin du jour')).toBeInTheDocument()
    expect(within(need).getByTestId('bmr-kcal').textContent).toMatch(/^métabolisme de base 1\s648 kcal$/)
    expect(within(need).getByText('× 1,2')).toBeInTheDocument()
    expect(within(need).getByRole('img').getAttribute('aria-label')).toMatch(/^Métabolisme de base 1\s648 kcal sur un besoin de 1\s980 kcal$/)
    expect(within(need).getByTestId('need-ring-arc')).toBeInTheDocument()
    // The reference intakes are out of the ticket's scope: no table, no place for one.
    expect(screen.queryByText(/Apports de référence/)).toBeNull()
    expect(screen.queryByRole('table')).toBeNull()

    const activity = screen.getByRole('region', { name: 'Activité' })
    expect(within(activity).getByLabelText('Objectif de pas')).toHaveValue('8\u202f000')
    expect(within(activity).getByText('/ jour')).toBeInTheDocument()
    const window = within(activity).getByRole('group', { name: 'Maggie propose entre' })
    expect(within(window).getByLabelText('Maggie propose à partir de')).toHaveValue('08:00')
    expect(within(window).getByLabelText('Maggie propose jusqu’à')).toHaveValue('20:00')
    expect(within(activity).getByLabelText('Durée d’une session')).toHaveDisplayValue('20 min')
    expect(within(activity).getByLabelText('Source des pas')).toHaveDisplayValue('Health Connect')
    expect(
      within(within(activity).getByLabelText('Durée d’une session'))
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['15 min', '20 min', '30 min'])
    expect(
      within(within(activity).getByLabelText('Source des pas'))
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['Health Connect', 'Aucune'])
  })

  test('without a profile: « — » in the fields, no need, an empty ring', async () => {
    stubApi(NONE)
    renderPage()

    const profile = await screen.findByRole('region', { name: 'Profil' })
    expect(within(profile).getByLabelText('Sexe')).toHaveDisplayValue('—')
    expect(within(profile).getByLabelText('Âge')).toHaveValue('')
    expect(within(profile).getByLabelText('Âge')).toHaveAttribute('placeholder', '—')
    expect(screen.getByTestId('need-kcal')).toHaveTextContent('— kcal')
    expect(screen.getByTestId('bmr-kcal')).toHaveTextContent('métabolisme de base — kcal')
    expect(screen.queryByTestId('need-ring-arc')).toBeNull()
    expect(screen.queryByText(/^× /)).toBeNull()
  })

  test('« Saisi » sends the need shown, to the nearest 50, then shows the field of the need alone', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi(CRITERION, (patch, current) =>
      json({ ...current, ...patch, needKcal: patch.enteredNeedKcal, factor: 1.21 }),
    )
    renderPage()

    await user.click(await screen.findByRole('radio', { name: 'Saisi' }))

    await waitFor(() => expect(patches(fetcher)).toEqual([{ mode: 'entered', enteredNeedKcal: 2000 }]))
    expect(await within(profileCard()).findByLabelText('Besoin du jour')).toHaveValue(2000)
    expect(within(profileCard()).queryByLabelText('Sexe')).toBeNull()
    expect(screen.getByRole('radio', { name: 'Saisi' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('need-kcal').textContent).toMatch(/^2\s000 kcal$/)
    expect(screen.getByText('× 1,21')).toBeInTheDocument()
  })

  test('« Calculé » sends the mode alone, and the profile comes back', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ ...CRITERION, mode: 'entered', enteredNeedKcal: 2450, needKcal: 2450, factor: 1.49 })
    renderPage()

    await user.click(await screen.findByRole('radio', { name: 'Calculé' }))

    await waitFor(() => expect(patches(fetcher)).toEqual([{ mode: 'computed' }]))
    expect(await within(profileCard()).findByLabelText('Sexe')).toHaveDisplayValue('Homme')
  })

  test('the entered need, changed, sends it alone', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi({ ...CRITERION, mode: 'entered', enteredNeedKcal: 2450, needKcal: 2450, factor: 1.49 })
    renderPage()

    const field = await within(await screen.findByRole('region', { name: 'Profil' })).findByLabelText('Besoin du jour')
    expect(field).toHaveAttribute('step', '50')
    expect(field).toHaveAttribute('min', '1000')
    expect(field).toHaveAttribute('max', '5000')
    await user.clear(field)
    await user.type(field, '2500{Enter}')

    await waitFor(() => expect(patches(fetcher)).toEqual([{ enteredNeedKcal: 2500 }]))
  })

  test('each field sends only itself; the age goes as a year of birth, the weight as a weighing', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi(CRITERION)
    renderPage()
    const profile = await screen.findByRole('region', { name: 'Profil' })

    await user.clear(within(profile).getByLabelText('Âge'))
    await user.type(within(profile).getByLabelText('Âge'), '40{Enter}')
    await waitFor(() => expect(patches(fetcher)).toEqual([{ birthYear: new Date().getFullYear() - 40 }]))

    await user.clear(within(profile).getByLabelText('Poids'))
    await user.type(within(profile).getByLabelText('Poids'), '73,5')
    await user.tab()
    await waitFor(() => expect(patches(fetcher).at(-1)).toEqual({ weightKg: 73.5 }))

    await user.selectOptions(within(profile).getByLabelText('Sexe'), 'Femme')
    await waitFor(() => expect(patches(fetcher).at(-1)).toEqual({ sex: 'female' }))

    await user.selectOptions(within(profile).getByLabelText('Activité'), 'Modérée · × 1,55')
    await waitFor(() => expect(patches(fetcher).at(-1)).toEqual({ activityLevel: 'moderate' }))

    const activity = screen.getByRole('region', { name: 'Activité' })
    await user.clear(within(activity).getByLabelText('Objectif de pas'))
    await user.type(within(activity).getByLabelText('Objectif de pas'), '9 500{Enter}')
    await waitFor(() => expect(patches(fetcher).at(-1)).toEqual({ stepGoal: 9500 }))

    await user.selectOptions(within(activity).getByLabelText('Durée d’une session'), '30 min')
    await waitFor(() => expect(patches(fetcher).at(-1)).toEqual({ sessionMinutes: 30 }))

    await user.selectOptions(within(activity).getByLabelText('Source des pas'), 'Aucune')
    await waitFor(() => expect(patches(fetcher).at(-1)).toEqual({ stepSource: 'none' }))

    expect(patches(fetcher)).toHaveLength(7)
  })

  test('a field left unchanged, or with no figure, sends nothing', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi(CRITERION)
    renderPage()
    const height = within(await screen.findByRole('region', { name: 'Profil' })).getByLabelText('Taille')

    await user.click(height)
    await user.tab()
    await user.clear(height)
    await user.type(height, 'abc{Enter}')

    expect(height).toHaveValue('178')
    expect(patches(fetcher)).toEqual([])
  })

  test('a value the API refuses: the message shows and the field goes back to the server’s value', async () => {
    const user = userEvent.setup()
    const fetcher = stubApi(CRITERION, () => json({ error: 'heightCm is a whole number of centimetres from 100 to 250.' }, 400))
    renderPage()
    const height = within(await screen.findByRole('region', { name: 'Profil' })).getByLabelText('Taille')

    await user.clear(height)
    await user.type(height, '300{Enter}')

    expect(await screen.findByText('Valeur refusée : rien n’a été enregistré.')).toBeInTheDocument()
    expect(patches(fetcher)).toEqual([{ heightCm: 300 }])
    await waitFor(() => expect(height).toHaveValue('178'))
    expect(screen.getByTestId('need-kcal').textContent).toMatch(/^1\s980 kcal$/)
  })

  test('a choice the API refuses goes back to the server’s', async () => {
    const user = userEvent.setup()
    stubApi(CRITERION, () => json({ error: 'nope' }, 400))
    renderPage()
    const sessions = within(await screen.findByRole('region', { name: 'Activité' })).getByLabelText('Durée d’une session')

    await user.selectOptions(sessions, '30 min')

    expect(await screen.findByText('Valeur refusée : rien n’a été enregistré.')).toBeInTheDocument()
    await waitFor(() => expect(sessions).toHaveDisplayValue('20 min'))
  })

  test('a profile that cannot be read says so', async () => {
    vi.stubGlobal('fetch', vi.fn(() => json({ error: 'boom' }, 500)))
    renderPage()

    expect(await screen.findByText('Erreur : le profil n’a pas pu être chargé.')).toBeInTheDocument()
    expect(screen.getByText('Le profil n’a pas pu être chargé.')).toBeInTheDocument()
  })
})

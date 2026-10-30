import { afterEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { emitMercure } from '../../test/mercure'
import { veilleuseLightTheme } from '../../theme'
import { ConfigurationPage } from './ConfigurationPage'
import { HEALTH_PROFILE_TOPICS } from './healthProfileApi'
import type { HealthProfile } from './healthProfileApi'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

const NONE: HealthProfile = {
  id: null,
  sex: null,
  birthYear: null,
  age: null,
  heightCm: null,
  weightKg: null,
  activityLevel: 'sedentary',
  mode: 'computed',
  enteredNeedKcal: null,
  bmrKcal: null,
  needKcal: null,
  factor: null,
  stepGoal: 8000,
  suggestionWindowStart: '08:00',
  suggestionWindowEnd: '20:00',
  sessionMinutes: 20,
  stepSource: 'none',
}

/** What the hub publishes: the whole profile, with its IRI. */
const PUBLISHED = {
  '@id': '/api/health-profile/01HPROFILE',
  ...NONE,
  id: '01HPROFILE',
  sex: 'male',
  birthYear: new Date().getFullYear() - 38,
  age: 38,
  heightCm: 178,
  weightKg: 72,
  bmrKcal: 1648,
  needKcal: 1980,
  factor: 1.2,
}

const json = (body: unknown) => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)

const renderPage = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme}>
        <ConfigurationPage />
      </AdminContext>
    </MemoryRouter>,
  )

afterEach(() => vi.unstubAllGlobals())

describe('ConfigurationPage — live', () => {
  test('listens to the profile’s topic as a template: it hears the profile’s creation', () => {
    expect(HEALTH_PROFILE_TOPICS).toEqual(['/api/health-profile/{id}'])
  })

  test('a profile written elsewhere (the phone, Maggie) replaces the one shown, without a read', async () => {
    const fetcher = vi.fn(() => json(NONE))
    vi.stubGlobal('fetch', fetcher)
    renderPage()
    expect(await screen.findByTestId('need-kcal')).toHaveTextContent('— kcal')

    emitMercure('/api/health-profile/{id}', PUBLISHED)

    await waitFor(() => expect(screen.getByTestId('need-kcal').textContent).toMatch(/^1\s980 kcal$/))
    expect(screen.getByTestId('bmr-kcal').textContent).toMatch(/^métabolisme de base 1\s648 kcal$/)
    expect(within(screen.getByRole('region', { name: 'Profil' })).getByLabelText('Taille')).toHaveValue('178')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  test('a new weighing republishes the profile: its weight and need follow', async () => {
    vi.stubGlobal('fetch', vi.fn(() => json({ ...PUBLISHED })))
    renderPage()
    const weight = within(await screen.findByRole('region', { name: 'Profil' })).getByLabelText('Poids')
    expect(weight).toHaveValue('72')

    emitMercure('/api/health-profile/{id}', { ...PUBLISHED, weightKg: 74.2, bmrKcal: 1670, needKcal: 2000 })

    await waitFor(() => expect(weight).toHaveValue('74,2'))
    expect(screen.getByTestId('need-kcal').textContent).toMatch(/^2\s000 kcal$/)
  })

  test('the payload is substituted, not merged: a field it clears is cleared', async () => {
    vi.stubGlobal('fetch', vi.fn(() => json({ ...PUBLISHED })))
    renderPage()
    expect(await screen.findByTestId('need-kcal')).toBeInTheDocument()

    emitMercure('/api/health-profile/{id}', { ...PUBLISHED, heightCm: null, bmrKcal: null, needKcal: null, factor: null })

    await waitFor(() => expect(screen.getByTestId('need-kcal')).toHaveTextContent('— kcal'))
    expect(within(screen.getByRole('region', { name: 'Profil' })).getByLabelText('Taille')).toHaveValue('')
  })
})

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, localStorageStore, useTheme } from 'react-admin'
import { UserPreferenceSettings } from './UserPreferenceSettings'
import { ThemePreferenceSync } from '../../components/layout/ThemePreferenceSync'

/**
 * « Apparence » must drive the theme react-admin really paints with (MAG-315).
 * Nothing here mocks react-admin: the bug lived in the store key, which a mock
 * of `useStore` cannot see.
 */

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => {} }))

const PREFERENCES = {
  id: 'p1',
  theme: 'system',
  locale: 'fr',
  timezone: 'Europe/Paris',
  defaultCalendarView: 'month',
  enabledAgendaIds: [],
  notificationsEnabled: true,
}

const dataProvider = { getList: () => Promise.resolve({ data: [], total: 0 }) }

function stubApi(saved: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        return new Response(JSON.stringify({ ...PREFERENCES, ...JSON.parse(String(init.body)) }))
      }
      return new Response(JSON.stringify({ ...PREFERENCES, theme: saved }))
    }),
  )
}

class FakeOs {
  dark: boolean
  private listeners = new Set<() => void>()

  constructor(dark: boolean) {
    this.dark = dark
    vi.stubGlobal(
      'matchMedia',
      (query: string) => ({
        get matches() {
          return query.includes('dark') && os.dark
        },
        media: query,
        addEventListener: (_: string, listener: () => void) => os.listeners.add(listener),
        removeEventListener: (_: string, listener: () => void) => os.listeners.delete(listener),
      }),
    )
    // eslint-disable-next-line @typescript-eslint/no-this-alias
    const os = this
  }

  switchTo(dark: boolean) {
    this.dark = dark
    this.listeners.forEach((listener) => listener())
  }
}

const Painted = () => {
  const [mode] = useTheme()
  return <output data-testid="painted">{mode}</output>
}

const renderShell = (store = localStorageStore()) =>
  render(
    <AdminContext dataProvider={dataProvider as never} store={store}>
      <ThemePreferenceSync />
      <Painted />
      <UserPreferenceSettings />
    </AdminContext>,
  )

const painted = () => screen.getByTestId('painted').textContent

async function choose(label: string) {
  await userEvent.click(await screen.findByRole('combobox', { name: 'Thème' }))
  await userEvent.click(await screen.findByRole('option', { name: label }))
}

describe('Apparence — thème', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('choosing Sombre paints dark at once', async () => {
    new FakeOs(false)
    stubApi('system')
    renderShell()

    await choose('Sombre')

    await waitFor(() => expect(painted()).toBe('dark'))
  })

  test('choosing Clair keeps the interface light while the OS is dark', async () => {
    new FakeOs(true)
    stubApi('system')
    renderShell()

    await waitFor(() => expect(painted()).toBe('dark'))
    await choose('Clair')

    await waitFor(() => expect(painted()).toBe('light'))
  })

  test('Système follows the OS, including when it switches', async () => {
    const os = new FakeOs(false)
    stubApi('dark')
    renderShell()

    await waitFor(() => expect(painted()).toBe('dark'))
    await choose('Système')
    await waitFor(() => expect(painted()).toBe('light'))

    act(() => os.switchTo(true))
    await waitFor(() => expect(painted()).toBe('dark'))
  })

  test('the saved preference is restored when the shell opens', async () => {
    new FakeOs(false)
    stubApi('dark')
    renderShell()

    await waitFor(() => expect(painted()).toBe('dark'))
  })

  test('the choice survives a reload, before the server has answered', async () => {
    new FakeOs(false)
    stubApi('dark')
    const store = localStorageStore()
    const first = renderShell(store)
    await waitFor(() => expect(painted()).toBe('dark'))
    first.unmount()

    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    renderShell(store)

    await waitFor(() => expect(painted()).toBe('dark'))
  })
})

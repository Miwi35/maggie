import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AgentRendezvousCards } from './AgentRendezvousCards'
import { useUserPreferences } from '../../hooks/useUserPreferences'

const Harness = () => {
  const { preferences, updatePreference } = useUserPreferences()
  return <AgentRendezvousCards preferences={preferences} updatePreference={updatePreference} />
}

const notify = vi.fn()

vi.mock('react-admin', () => ({ useNotify: () => notify }))

const PREFERENCES = {
  id: 'p1',
  theme: 'system',
  locale: 'fr',
  timezone: 'Europe/Paris',
  defaultCalendarView: 'month',
  enabledAgendaIds: [],
  notificationsEnabled: true,
  morningDigestEnabled: true,
  morningDigestTime: '07:00',
  eveningReviewEnabled: true,
  eveningReviewTime: '21:30',
  workStartTime: '09:00',
  workEndTime: '18:00',
  workDays: [1, 2, 3, 4, 5],
}

function stubApi(initial: Record<string, unknown> = {}, patchStatus = 200) {
  const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'PATCH') {
      if (patchStatus !== 200) return new Response('{}', { status: patchStatus })
      return new Response(JSON.stringify({ ...PREFERENCES, ...initial, ...JSON.parse(String(init.body)) }))
    }
    return new Response(JSON.stringify({ ...PREFERENCES, ...initial }))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const patches = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([, init]) => init?.method === 'PATCH').map(([, init]) => JSON.parse(String(init?.body)))

describe('AgentRendezvousCards', () => {
  beforeEach(() => notify.mockClear())

  test('shows the saved state and hours', async () => {
    stubApi({ eveningReviewEnabled: false, eveningReviewTime: '22:15' })
    render(<Harness />)

    expect(await screen.findByLabelText('Digest du matin')).toBeChecked()
    expect(screen.getByLabelText('Heure — Digest du matin')).toHaveValue('07:00')
    expect(screen.getByLabelText('Bilan du soir')).not.toBeChecked()
    expect(screen.getByLabelText('Heure — Bilan du soir')).toHaveValue('22:15')
  })

  test('the switch sends the change and says it is saved', async () => {
    const fetchMock = stubApi()
    render(<Harness />)

    await userEvent.click(await screen.findByLabelText('Digest du matin'))

    await waitFor(() => expect(patches(fetchMock)).toEqual([{ morningDigestEnabled: false }]))
    await waitFor(() => expect(notify).toHaveBeenCalledWith('Enregistré', { type: 'success' }))
    expect(screen.getByLabelText('Heure — Digest du matin')).toBeDisabled()
  })

  test('the hour is greyed while the rendezvous is off', async () => {
    stubApi({ morningDigestEnabled: false })
    render(<Harness />)

    expect(await screen.findByLabelText('Heure — Digest du matin')).toBeDisabled()
    expect(screen.getByLabelText('Heure — Bilan du soir')).toBeEnabled()
  })

  test('a changed hour is sent', async () => {
    const fetchMock = stubApi()
    render(<Harness />)

    fireEvent.change(await screen.findByLabelText('Heure — Bilan du soir'), { target: { value: '22:15' } })

    await waitFor(() => expect(patches(fetchMock)).toEqual([{ eveningReviewTime: '22:15' }]))
  })

  test('a refusal shows an error and restores the saved hour', async () => {
    stubApi({}, 400)
    render(<Harness />)

    fireEvent.change(await screen.findByLabelText('Heure — Bilan du soir'), { target: { value: '22:15' } })

    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
    await waitFor(() => expect(screen.getByLabelText('Heure — Bilan du soir')).toHaveValue('21:30'))
    expect(notify).not.toHaveBeenCalledWith('Enregistré', expect.anything())
  })

  describe('Horaires de travail (MAG-790)', () => {
    test('shows the saved hours and days', async () => {
      stubApi({ workEndTime: '17:00', workDays: [1, 2, 3, 4] })
      render(<Harness />)

      expect(await screen.findByLabelText('Début du travail')).toHaveValue('09:00')
      expect(screen.getByLabelText('Fin du travail')).toHaveValue('17:00')
      expect(screen.getByRole('button', { name: 'jeudi' })).toHaveAttribute('aria-pressed', 'true')
      expect(screen.getByRole('button', { name: 'vendredi' })).toHaveAttribute('aria-pressed', 'false')
    })

    test('a changed end and a day taken off are sent and saved', async () => {
      const fetchMock = stubApi()
      render(<Harness />)

      fireEvent.change(await screen.findByLabelText('Fin du travail'), { target: { value: '17:00' } })
      await waitFor(() => expect(patches(fetchMock)).toEqual([{ workEndTime: '17:00' }]))
      await userEvent.click(screen.getByRole('button', { name: 'vendredi' }))

      await waitFor(() => expect(patches(fetchMock)).toEqual([{ workEndTime: '17:00' }, { workDays: [1, 2, 3, 4] }]))
      await waitFor(() => expect(screen.getByRole('button', { name: 'vendredi' })).toHaveAttribute('aria-pressed', 'false'))
      expect(notify).toHaveBeenCalledWith('Enregistré', { type: 'success' })
    })

    test('an end before the start is refused without a request', async () => {
      const fetchMock = stubApi()
      render(<Harness />)

      fireEvent.change(await screen.findByLabelText('Fin du travail'), { target: { value: '08:00' } })

      expect(notify).toHaveBeenCalledWith('La fin doit suivre le début', { type: 'error' })
      expect(patches(fetchMock)).toEqual([])
      expect(screen.getByLabelText('Fin du travail')).toHaveValue('18:00')
    })

    test('a refused day restores the saved days', async () => {
      stubApi({}, 400)
      render(<Harness />)

      await userEvent.click(await screen.findByRole('button', { name: 'lundi' }))

      await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.any(String), { type: 'error' }))
      await waitFor(() => expect(screen.getByRole('button', { name: 'lundi' })).toHaveAttribute('aria-pressed', 'true'))
    })
  })
})

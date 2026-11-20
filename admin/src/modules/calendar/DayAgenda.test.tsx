import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DayAgenda } from './DayAgenda'
import type { Trip } from './trips'

const at = (hour: number, minute = 0) => new Date(2037, 0, 15, hour, minute)
const entry = (id: string, title: string, start: Date, extra = {}) => ({
  id: `/api/events/${id}`,
  title,
  start: start.toISOString(),
  end: new Date(start.getTime() + 3_600_000).toISOString(),
  allDay: false,
  backgroundColor: '#3f51b5',
  calendarId: 'perso',
  extendedProps: { location: 'École Jean-Moulin' },
  ...extra,
})
const trip = (id: string, departAt: Date): Trip => ({
  id: `/api/trips/${id}`,
  event: `/api/events/${id}`,
  mode: 'drive',
  steps: [
    { kind: 'walk', minutes: 4 },
    { kind: 'drive', minutes: 14, label: 'voiture' },
  ],
  durationMinutes: 21,
  departAt: departAt.toISOString(),
  navigationUrl: 'https://waze.com/ul?navigate=yes',
  originLabel: 'la maison',
})

const setup = (entries: ReturnType<typeof entry>[], trips: Trip[], onOpen = vi.fn()) => {
  render(<DayAgenda entries={entries} trips={trips} agendaName={() => 'Perso'} onOpen={onOpen} />)

  return onOpen
}

describe('DayAgenda', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(at(10))
  })
  afterEach(() => vi.useRealTimers())

  test('an event row reads time, title and place · agenda', () => {
    setup([entry('a', 'Dentiste', at(14))], [])

    expect(screen.getByText('14:00')).toBeInTheDocument()
    expect(screen.getByText('Dentiste')).toBeInTheDocument()
    expect(screen.getByText('École Jean-Moulin · Perso')).toBeInTheDocument()
  })

  test('a trip line reads Partir à, depuis and one outlined chip per step', () => {
    setup([entry('a', 'Dentiste', at(14)), entry('b', 'Sport', at(18))], [trip('b', at(17, 30)), trip('a', at(13, 34))])

    const line = screen.getByLabelText('Trajet vers Sport')
    expect(line).toHaveTextContent('Partir à 17:30')
    expect(line).toHaveTextContent('depuis la maison')
    expect(line.querySelectorAll('.trip-chip')).toHaveLength(2)
    expect(line.querySelectorAll('svg')).toHaveLength(2)
    expect(line).not.toHaveTextContent(/[\u{1F300}-\u{1FAFF}]/u)
    expect(line).toHaveTextContent('4 min')
  })

  test('the next departure is a card with dans N min, door to door and Y aller', () => {
    setup([entry('a', 'Dentiste', at(14)), entry('b', 'Sport', at(18))], [trip('a', at(13, 34)), trip('b', at(17, 30))])

    const card = screen.getByLabelText('Trajet vers Dentiste')
    expect(card).toHaveTextContent('Partir à 13:34')
    expect(card).toHaveTextContent('dans 3 h 34')
    expect(card).toHaveTextContent('depuis la maison · 21 min porte à porte')
    expect(card).toHaveTextContent('voiture')
    expect(screen.getAllByRole('button', { name: 'Y aller' })).toHaveLength(1)
    expect(screen.getByLabelText('Trajet vers Sport')).not.toHaveTextContent('Y aller')
  })

  test('Y aller opens the navigation link in a new tab', async () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    setup([entry('a', 'Dentiste', at(14))], [trip('a', at(13, 34))])

    await userEvent.click(screen.getByRole('button', { name: 'Y aller' }), { advanceTimers: vi.advanceTimersByTime })

    expect(open).toHaveBeenCalledWith('https://waze.com/ul?navigate=yes', '_blank', 'noopener,noreferrer')
    open.mockRestore()
  })

  test('a click on an event row opens its sheet', async () => {
    const onOpen = setup([entry('a', 'Dentiste', at(14))], [])

    await userEvent.click(screen.getByText('Dentiste'))

    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ title: 'Dentiste' }), expect.any(HTMLElement))
  })

  test('a past trip reads Parti à and is dimmed', () => {
    setup([entry('a', 'Réunion', at(8, 30))], [trip('a', at(7, 50))])

    const line = screen.getByLabelText('Trajet vers Réunion')
    expect(line).toHaveTextContent('Parti à 07:50')
    expect(line).toHaveStyle({ opacity: '0.6' })
    expect(line).not.toHaveTextContent('Y aller')
    expect(screen.getByText('Réunion').closest('.day-row')).toHaveStyle({ opacity: '0.6' })
  })
})

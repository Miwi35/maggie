import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { EventListWidget } from './EventListWidget'
import type { DashboardEvent } from './EventListWidget'

const timed: DashboardEvent = {
  id: '/api/events/01DENTISTE',
  summary: 'Dentiste',
  allDay: false,
  startAt: new Date(2037, 0, 1, 14, 30).toISOString(),
  endAt: new Date(2037, 0, 1, 15, 30).toISOString(),
  startDate: null,
  endDate: null,
}

/** An all-day event is a pair of dates and has no instant (MAG-382). */
const day: DashboardEvent = {
  id: '/api/events/01NOUVELAN',
  summary: 'Nouvel an',
  allDay: true,
  startAt: null,
  endAt: null,
  startDate: '2037-01-01',
  endDate: '2037-01-02',
}

const rowOf = (summary: string) => screen.getByText(summary).closest('li') as HTMLElement

describe('EventListWidget', () => {
  test('labels an all-day event « Journée » and a timed one by its hour', () => {
    render(<EventListWidget events={[day, timed]} loading={false} />)

    expect(rowOf('Nouvel an')).toHaveTextContent('Journée')
    expect(rowOf('Dentiste')).toHaveTextContent('14:30')
  })

  test('dates an all-day event by its own date, never the eve', () => {
    render(<EventListWidget events={[day]} loading={false} showDate />)

    expect(rowOf('Nouvel an')).toHaveTextContent('1 janv.')
    expect(rowOf('Nouvel an')).not.toHaveTextContent('31 déc.')
  })

  test('says so when there is nothing', () => {
    render(<EventListWidget events={[]} loading={false} />)

    expect(screen.getByText('Aucun événement')).toBeInTheDocument()
  })

  test('shows placeholders while loading', () => {
    render(<EventListWidget events={[day]} loading />)

    expect(screen.queryByText('Nouvel an')).not.toBeInTheDocument()
  })
})

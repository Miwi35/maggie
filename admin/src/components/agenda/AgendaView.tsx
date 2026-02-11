import { useEffect, useState } from 'react'
import { useDataProvider } from 'react-admin'

interface Event {
  id: string
  summary: string
  startAt: string
  endAt: string
  allDay: boolean
  location?: string
  status: string
}

/**
 * Placeholder agenda view. Will be replaced with FullCalendar integration in Phase 2.
 */
export const AgendaView = () => {
  const dataProvider = useDataProvider()
  const [events, setEvents] = useState<Event[]>([])

  useEffect(() => {
    dataProvider
      .getList('events', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'startAt', order: 'ASC' },
        filter: {},
      })
      .then(({ data }) => setEvents(data as unknown as Event[]))
      .catch(console.error)
  }, [dataProvider])

  // Subscribe to Mercure for real-time event updates
  useEffect(() => {
    const mercureUrl = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'
    const url = new URL(mercureUrl)
    url.searchParams.append('topic', '/api/events/{id}')

    const eventSource = new EventSource(url.toString())
    eventSource.onmessage = () => {
      // Refresh events list on any change
      dataProvider
        .getList('events', {
          pagination: { page: 1, perPage: 50 },
          sort: { field: 'startAt', order: 'ASC' },
          filter: {},
        })
        .then(({ data }) => setEvents(data as unknown as Event[]))
        .catch(console.error)
    }

    return () => eventSource.close()
  }, [dataProvider])

  return (
    <div style={{ padding: 20 }}>
      <h2>Agenda</h2>
      <p style={{ color: '#666' }}>Calendar view coming in Phase 2 (FullCalendar integration)</p>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 16 }}>
        {events.map((event) => (
          <div
            key={event.id}
            style={{
              padding: '12px 16px',
              borderRadius: 8,
              border: '1px solid #e0e0e0',
              backgroundColor: event.allDay ? '#e8f5e9' : 'white',
            }}
          >
            <strong>{event.summary}</strong>
            <div style={{ fontSize: 14, color: '#666', marginTop: 4 }}>
              {event.allDay ? (
                <span>All day — {new Date(event.startAt).toLocaleDateString()}</span>
              ) : (
                <span>
                  {new Date(event.startAt).toLocaleString()} - {new Date(event.endAt).toLocaleTimeString()}
                </span>
              )}
            </div>
            {event.location && (
              <div style={{ fontSize: 13, color: '#999', marginTop: 2 }}>{event.location}</div>
            )}
          </div>
        ))}

        {events.length === 0 && (
          <p style={{ color: '#999', fontStyle: 'italic' }}>No upcoming events.</p>
        )}
      </div>
    </div>
  )
}

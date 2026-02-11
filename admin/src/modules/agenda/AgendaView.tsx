import { useCallback, useEffect, useRef, useState } from 'react'
import { useDataProvider } from 'react-admin'
import FullCalendar from '@fullcalendar/react'
import dayGridPlugin from '@fullcalendar/daygrid'
import type { DatesSetArg, EventInput } from '@fullcalendar/core'

interface AgendaEvent {
  id: string
  summary: string
  startAt: string
  endAt: string
  allDay: boolean
}

export const AgendaView = () => {
  const dataProvider = useDataProvider()
  const [events, setEvents] = useState<EventInput[]>([])
  const dateRangeRef = useRef<{ start: string; end: string } | null>(null)

  const fetchEvents = useCallback(
    (start: string, end: string) => {
      dataProvider
        .getList('events', {
          pagination: { page: 1, perPage: 200 },
          sort: { field: 'startAt', order: 'ASC' },
          filter: { 'startAt[after]': start, 'startAt[before]': end },
        })
        .then(({ data }) => {
          setEvents(
            (data as unknown as AgendaEvent[]).map((e) => ({
              id: e.id,
              title: e.summary,
              start: e.startAt,
              end: e.endAt,
              allDay: e.allDay,
            })),
          )
        })
        .catch(console.error)
    },
    [dataProvider],
  )

  const handleDatesSet = useCallback(
    (arg: DatesSetArg) => {
      const start = arg.start.toISOString()
      const end = arg.end.toISOString()
      dateRangeRef.current = { start, end }
      fetchEvents(start, end)
    },
    [fetchEvents],
  )

  // Subscribe to Mercure for real-time event updates
  useEffect(() => {
    const mercureUrl =
      import.meta.env.VITE_MERCURE_PUBLIC_URL ||
      'http://maggie.local/.well-known/mercure'
    const url = new URL(mercureUrl)
    url.searchParams.append('topic', '/api/events/{id}')

    const eventSource = new EventSource(url.toString())
    eventSource.onmessage = () => {
      if (dateRangeRef.current) {
        fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
      }
    }

    return () => eventSource.close()
  }, [fetchEvents])

  return (
    <div style={{ padding: 20 }}>
      <FullCalendar
        plugins={[dayGridPlugin]}
        initialView="dayGridMonth"
        events={events}
        datesSet={handleDatesSet}
        headerToolbar={{
          left: 'prev,next today',
          center: 'title',
          right: '',
        }}
        height="auto"
      />
    </div>
  )
}

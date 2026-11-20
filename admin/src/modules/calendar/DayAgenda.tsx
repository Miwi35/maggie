import { useEffect, useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import type { EventInput } from '@fullcalendar/core'
import { dayRows } from './dayAgenda'
import { TripLine } from './TripLine'
import { hourOf, type Trip } from './trips'

export type DayEntryInput = EventInput & { calendarId?: string }

interface DayAgendaProps {
  entries: DayEntryInput[]
  trips: Trip[]
  /** The name of the agenda an entry belongs to, drawn under its title. */
  agendaName: (entry: DayEntryInput) => string
  onOpen: (entry: DayEntryInput, anchor: HTMLElement) => void
}

/** The day as the « Admin-Trajets » mockup draws it: the entries by hour, the trips at their departure (MAG-858). */
export const DayAgenda = ({ entries, trips, agendaName, onOpen }: DayAgendaProps) => {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 30_000)

    return () => clearInterval(timer)
  }, [])
  const rows = useMemo(() => dayRows(entries, trips, now), [entries, trips, now])

  if (rows.length === 0) {
    return (
      <Box className="day-agenda" sx={{ p: 3, color: 'text.secondary' }}>
        Rien de prévu ce jour.
      </Box>
    )
  }

  return (
    <Box className="day-agenda" sx={{ height: '100%', overflowY: 'auto', py: 1 }}>
      {rows.map((row) => {
        if (row.kind === 'trip') {
          return (
            <TripLine
              key={`trip-${row.trip.id}`}
              trip={row.trip}
              title={String(row.entry.title ?? '')}
              next={row.next}
              past={row.past}
              now={now}
            />
          )
        }
        const { entry } = row
        const classNames = Array.isArray(entry.classNames) ? entry.classNames : []
        const place = [(entry.extendedProps?.location as string | undefined) || '', agendaName(entry)].filter(Boolean).join(' · ')
        const emphasised = row.kind === 'event' && row.highlighted

        return (
          <Box
            key={String(entry.id)}
            className={['day-row', ...classNames].join(' ')}
            role="button"
            tabIndex={0}
            onClick={(event) => onOpen(entry, event.currentTarget)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault()
                onOpen(entry, event.currentTarget)
              }
            }}
            sx={{
              display: 'grid',
              gridTemplateColumns: '64px 4px 1fr',
              columnGap: '14px',
              alignItems: 'center',
              minHeight: 56,
              m: '6px 14px',
              borderRadius: '14px',
              cursor: 'pointer',
              opacity: row.past ? 0.6 : 1,
              '&:hover': { bgcolor: 'action.hover' },
              '&.event-tentative .day-row__title': { fontStyle: 'italic' },
            }}
          >
            <Box
              className="day-row__time"
              sx={{
                fontVariantNumeric: 'tabular-nums',
                fontWeight: emphasised ? 500 : 400,
                color: row.past ? 'text.secondary' : 'text.primary',
                textAlign: 'right',
              }}
            >
              {row.kind === 'allDay' ? 'Journée' : hourOf(row.at.toISOString())}
            </Box>
            <Box sx={{ width: 4, height: 30, borderRadius: '2px', bgcolor: String(entry.backgroundColor ?? 'primary.main') }} />
            <Box sx={{ minWidth: 0 }}>
              <Box className="day-row__title" sx={{ fontWeight: emphasised ? 500 : 400 }}>
                {entry.title}
              </Box>
              {place && <Box sx={{ fontSize: 13, color: 'text.secondary' }}>{place}</Box>}
            </Box>
          </Box>
        )
      })}
    </Box>
  )
}

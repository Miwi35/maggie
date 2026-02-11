import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useDataProvider } from 'react-admin'
import { useTheme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Grid from '@mui/material/Grid'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import CircleIcon from '@mui/icons-material/Circle'
import EventIcon from '@mui/icons-material/Event'
import FullCalendar from '@fullcalendar/react'
import dayGridPlugin from '@fullcalendar/daygrid'
import frLocale from '@fullcalendar/core/locales/fr'
import type { DatesSetArg, EventInput } from '@fullcalendar/core'

interface AgendaEvent {
  id: string
  summary: string
  startAt: string
  endAt: string
  allDay: boolean
}

export const AgendaView = () => {
  const theme = useTheme()
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

  const upcomingEvents = useMemo(() => {
    const now = new Date()
    return events
      .filter((e) => new Date(e.start as string) >= now)
      .sort((a, b) => new Date(a.start as string).getTime() - new Date(b.start as string).getTime())
      .slice(0, 8)
  }, [events])

  const formatEventDate = (date: string) => {
    return new Date(date).toLocaleDateString('fr-FR', {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    })
  }

  const calendarThemeSx = {
    fontFamily: theme.typography.fontFamily,
    '& .fc': {
      '--fc-border-color': theme.palette.divider,
      '--fc-button-bg-color': theme.palette.primary.main,
      '--fc-button-border-color': theme.palette.primary.main,
      '--fc-button-hover-bg-color': theme.palette.primary.dark,
      '--fc-button-hover-border-color': theme.palette.primary.dark,
      '--fc-button-active-bg-color': theme.palette.primary.dark,
      '--fc-button-active-border-color': theme.palette.primary.dark,
      '--fc-event-bg-color': theme.palette.primary.main,
      '--fc-event-border-color': theme.palette.primary.main,
      '--fc-event-text-color': theme.palette.primary.contrastText,
      '--fc-today-bg-color': theme.palette.action.selected,
      '--fc-page-bg-color': 'transparent',
      '--fc-neutral-bg-color': theme.palette.action.hover,
    },
    '& .fc .fc-toolbar-title': {
      fontFamily: theme.typography.fontFamily,
      fontSize: theme.typography.h5.fontSize,
      fontWeight: theme.typography.h5.fontWeight,
      color: theme.palette.text.primary,
    },
    '& .fc .fc-col-header-cell-cushion, & .fc .fc-daygrid-day-number': {
      color: theme.palette.text.secondary,
      fontFamily: theme.typography.fontFamily,
      textDecoration: 'none',
    },
    '& .fc .fc-button': {
      fontFamily: theme.typography.fontFamily,
      borderRadius: theme.shape.borderRadius + 'px',
      textTransform: 'none',
    },
    '& .fc .fc-daygrid-event': {
      borderRadius: theme.shape.borderRadius + 'px',
    },
  }

  return (
    <Box sx={{ p: { xs: 1.5, sm: 2.5 }, maxWidth: 1400, mx: 'auto' }}>
      <Grid container spacing={2.5}>
        {/* Calendar — main panel */}
        <Grid size={{ xs: 12, lg: 8 }}>
          <Card variant="outlined">
            <CardContent sx={calendarThemeSx}>
              <FullCalendar
                plugins={[dayGridPlugin]}
                initialView="dayGridMonth"
                locale={frLocale}
                events={events}
                datesSet={handleDatesSet}
                headerToolbar={{
                  left: 'prev,next today',
                  center: 'title',
                  right: '',
                }}
                height="auto"
              />
            </CardContent>
          </Card>
        </Grid>

        {/* Sidebar — upcoming events */}
        <Grid size={{ xs: 12, lg: 4 }}>
          <Card variant="outlined">
            <CardContent>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                <EventIcon color="primary" />
                <Typography variant="h6">À venir</Typography>
              </Box>
              {upcomingEvents.length === 0 ? (
                <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                  Aucun événement à venir
                </Typography>
              ) : (
                <List dense disablePadding>
                  {upcomingEvents.map((event, i) => (
                    <ListItem key={event.id || i} disableGutters>
                      <ListItemIcon sx={{ minWidth: 28 }}>
                        <CircleIcon sx={{ fontSize: 10, color: 'primary.main' }} />
                      </ListItemIcon>
                      <ListItemText
                        primary={event.title}
                        secondary={formatEventDate(event.start as string)}
                        primaryTypographyProps={{ variant: 'body2', noWrap: true }}
                        secondaryTypographyProps={{ variant: 'caption' }}
                      />
                    </ListItem>
                  ))}
                </List>
              )}
            </CardContent>
          </Card>

          {/* Stats card */}
          <Card variant="outlined" sx={{ mt: 2.5 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Résumé
              </Typography>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Typography variant="body2" color="text.secondary">Événements ce mois</Typography>
                <Typography variant="body2" fontWeight={600}>{events.length}</Typography>
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">À venir</Typography>
                <Typography variant="body2" fontWeight={600}>{upcomingEvents.length}</Typography>
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  )
}

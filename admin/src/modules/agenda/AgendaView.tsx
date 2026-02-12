import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import { useTheme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonGroup from '@mui/material/ButtonGroup'
import Checkbox from '@mui/material/Checkbox'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import FullCalendar from '@fullcalendar/react'
import dayGridPlugin from '@fullcalendar/daygrid'
import timeGridPlugin from '@fullcalendar/timegrid'
import interactionPlugin from '@fullcalendar/interaction'
import type { DateClickArg } from '@fullcalendar/interaction'
import type { EventClickArg } from '@fullcalendar/core'
import frLocale from '@fullcalendar/core/locales/fr'
import type { DatesSetArg, EventInput } from '@fullcalendar/core'
import { EventCreateDialog } from './EventCreateDialog'
import { EventDetailPopover } from './EventDetailPopover'
import type { PopoverEvent } from './EventDetailPopover'
import { getCalendarThemeSx } from './calendarTheme'

const SIDEBAR_WIDTH = 230
const DAY_LABELS = ['L', 'M', 'M', 'J', 'V', 'S', 'D']

type CalendarView = 'dayGridMonth' | 'timeGridWeek' | 'timeGridDay'

const VIEW_BUTTONS: { label: string; view: CalendarView }[] = [
  { label: 'Mois', view: 'dayGridMonth' },
  { label: 'Semaine', view: 'timeGridWeek' },
  { label: 'Jour', view: 'timeGridDay' },
]

interface AgendaEvent {
  id: string
  summary: string
  description?: string
  location?: string
  status?: string
  startAt: string
  endAt: string
  allDay: boolean
  calendar: string
}

interface CalendarData {
  id: string
  name: string
  color: string | null
  isDefault: boolean
}

// ---------------------------------------------------------------------------
// Mini Calendar
// ---------------------------------------------------------------------------
const MiniCalendar = ({
  viewDate,
  onDateClick,
  onMonthChange,
}: {
  viewDate: Date
  onDateClick: (date: Date) => void
  onMonthChange: (date: Date) => void
}) => {
  const year = viewDate.getFullYear()
  const month = viewDate.getMonth()

  const firstOfMonth = new Date(year, month, 1)
  const dow = firstOfMonth.getDay()
  const mondayOffset = dow === 0 ? 6 : dow - 1
  const gridStart = new Date(year, month, 1 - mondayOffset)

  const days: Date[] = []
  for (let i = 0; i < 42; i++) {
    days.push(new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + i))
  }

  const today = new Date()

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 0.5, px: 0.5 }}>
        <Typography variant="body2" fontWeight={500} sx={{ textTransform: 'capitalize' }}>
          {viewDate.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })}
        </Typography>
        <Box>
          <IconButton size="small" onClick={() => onMonthChange(new Date(year, month - 1, 1))}>
            <ChevronLeftIcon sx={{ fontSize: 16 }} />
          </IconButton>
          <IconButton size="small" onClick={() => onMonthChange(new Date(year, month + 1, 1))}>
            <ChevronRightIcon sx={{ fontSize: 16 }} />
          </IconButton>
        </Box>
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', textAlign: 'center' }}>
        {DAY_LABELS.map((d, i) => (
          <Typography key={i} variant="caption" color="text.secondary" sx={{ py: 0.25, fontSize: '0.65rem' }}>
            {d}
          </Typography>
        ))}
        {days.map((d, i) => {
          const isToday = d.toDateString() === today.toDateString()
          const isCurMonth = d.getMonth() === month
          return (
            <Box
              key={i}
              onClick={() => onDateClick(d)}
              sx={{
                width: 26,
                height: 26,
                mx: 'auto',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                borderRadius: '50%',
                cursor: 'pointer',
                fontSize: '0.7rem',
                fontWeight: isToday ? 600 : 400,
                color: isToday ? '#fff' : isCurMonth ? 'text.primary' : 'text.disabled',
                bgcolor: isToday ? 'primary.main' : 'transparent',
                '&:hover': { bgcolor: isToday ? 'primary.dark' : 'action.hover' },
              }}
            >
              {d.getDate()}
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}

// ---------------------------------------------------------------------------
// Toolbar title helper
// ---------------------------------------------------------------------------
const getToolbarTitle = (date: Date, view: CalendarView): string => {
  if (view === 'dayGridMonth') {
    return date.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
  }
  if (view === 'timeGridDay') {
    return date.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  }
  // timeGridWeek — show "week range" e.g. "10 – 16 févr. 2026"
  const start = new Date(date)
  const end = new Date(start)
  end.setDate(end.getDate() + 6)
  const startDay = start.getDate()
  const endDay = end.getDate()
  const endMonth = end.toLocaleDateString('fr-FR', { month: 'short', year: 'numeric' })
  if (start.getMonth() === end.getMonth()) {
    return `${startDay} – ${endDay} ${endMonth}`
  }
  const startMonth = start.toLocaleDateString('fr-FR', { month: 'short' })
  return `${startDay} ${startMonth} – ${endDay} ${endMonth}`
}

// ---------------------------------------------------------------------------
// Agenda View
// ---------------------------------------------------------------------------
export const AgendaView = () => {
  const theme = useTheme()
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const calendarRef = useRef<FullCalendar>(null)
  const dateRangeRef = useRef<{ start: string; end: string } | null>(null)

  const [rawEvents, setRawEvents] = useState<AgendaEvent[]>([])
  const [calendars, setCalendars] = useState<CalendarData[]>([])
  const [enabledCalendars, setEnabledCalendars] = useState<Set<string> | null>(null)
  const [viewDate, setViewDate] = useState(new Date())
  const [miniCalDate, setMiniCalDate] = useState(new Date())
  const [currentView, setCurrentView] = useState<CalendarView>('dayGridMonth')
  const [dialogOpen, setDialogOpen] = useState(false)
  const [dialogDefaultDate, setDialogDefaultDate] = useState<string | undefined>()

  // Popover state
  const [popoverEvent, setPopoverEvent] = useState<PopoverEvent | null>(null)
  const [popoverAnchorEl, setPopoverAnchorEl] = useState<HTMLElement | null>(null)

  // --- Fetch calendars once ---
  useEffect(() => {
    dataProvider
      .getList('calendars', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      .then(({ data }) => {
        const cals = data as unknown as CalendarData[]
        setCalendars(cals)
        setEnabledCalendars(new Set(cals.map((c) => c.id)))
      })
      .catch(console.error)
  }, [dataProvider])

  // --- Calendar maps ---
  const calendarColorMap = useMemo(() => {
    const map = new Map<string, string>()
    calendars.forEach((cal) => {
      map.set(cal.id, cal.color || theme.palette.primary.main)
    })
    return map
  }, [calendars, theme.palette.primary.main])

  const calendarNameMap = useMemo(() => {
    const map = new Map<string, string>()
    calendars.forEach((cal) => {
      map.set(cal.id, cal.name)
    })
    return map
  }, [calendars])

  // --- Fetch events ---
  const fetchEvents = useCallback(
    (start: string, end: string) => {
      dataProvider
        .getList('events', {
          pagination: { page: 1, perPage: 200 },
          sort: { field: 'startAt', order: 'ASC' },
          filter: { 'startAt[after]': start, 'startAt[before]': end },
        })
        .then(({ data }) => setRawEvents(data as unknown as AgendaEvent[]))
        .catch(console.error)
    },
    [dataProvider],
  )

  // --- Map raw events → FullCalendar events with calendar colours ---
  const coloredEvents: (EventInput & { calendarId: string })[] = useMemo(
    () =>
      rawEvents.map((e) => {
        const calId = typeof e.calendar === 'string' ? e.calendar : ''
        const color = calendarColorMap.get(calId)
        return {
          id: e.id,
          title: e.summary,
          start: e.startAt,
          end: e.endAt,
          allDay: e.allDay,
          calendarId: calId,
          backgroundColor: color,
          borderColor: color,
          extendedProps: {
            description: e.description,
            location: e.location,
            status: e.status,
            calendarId: calId,
          },
        }
      }),
    [rawEvents, calendarColorMap],
  )

  // --- Filter by enabled calendars ---
  const filteredEvents = useMemo(() => {
    if (!enabledCalendars) return coloredEvents
    return coloredEvents.filter((e) => enabledCalendars.has(e.calendarId))
  }, [coloredEvents, enabledCalendars])

  // --- FullCalendar callbacks ---
  const handleDatesSet = useCallback(
    (arg: DatesSetArg) => {
      const start = arg.start.toISOString()
      const end = arg.end.toISOString()
      dateRangeRef.current = { start, end }
      setViewDate(arg.view.currentStart)
      setMiniCalDate(arg.view.currentStart)
      setCurrentView(arg.view.type as CalendarView)
      fetchEvents(start, end)
    },
    [fetchEvents],
  )

  const handleDateClick = useCallback((arg: DateClickArg) => {
    setDialogDefaultDate(arg.dateStr)
    setDialogOpen(true)
  }, [])

  const handleEventClick = useCallback(
    (arg: EventClickArg) => {
      arg.jsEvent.preventDefault()
      const fcEvent = arg.event
      const calId = fcEvent.extendedProps.calendarId || ''
      const color = calendarColorMap.get(calId) || theme.palette.primary.main

      setPopoverEvent({
        id: fcEvent.id,
        title: fcEvent.title,
        start: fcEvent.start?.toISOString() || '',
        end: fcEvent.end?.toISOString() || fcEvent.start?.toISOString() || '',
        allDay: fcEvent.allDay,
        color,
        calendarName: calendarNameMap.get(calId) || '',
        description: fcEvent.extendedProps.description,
        location: fcEvent.extendedProps.location,
        status: fcEvent.extendedProps.status,
      })
      setPopoverAnchorEl(arg.el)
    },
    [calendarColorMap, calendarNameMap, theme.palette.primary.main],
  )

  const handleDeleteEvent = useCallback(
    (eventId: string) => {
      setPopoverAnchorEl(null)
      setPopoverEvent(null)
      dataProvider
        .delete('events', { id: eventId, previousData: { id: eventId } })
        .then(() => {
          notify('Événement supprimé', { type: 'success' })
          if (dateRangeRef.current) {
            fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
          }
        })
        .catch((error: Error) => {
          notify(`Erreur: ${error.message}`, { type: 'error' })
        })
    },
    [dataProvider, fetchEvents, notify],
  )

  const handleCreated = useCallback(() => {
    if (dateRangeRef.current) {
      fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
    }
  }, [fetchEvents])

  // --- Mercure live updates ---
  useEffect(() => {
    const mercureUrl =
      import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'
    const url = new URL(mercureUrl)
    url.searchParams.append('topic', '/api/events/{id}')
    const es = new EventSource(url.toString())
    es.onmessage = () => {
      if (dateRangeRef.current) fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
    }
    return () => es.close()
  }, [fetchEvents])

  // --- Navigation ---
  const handleToday = useCallback(() => calendarRef.current?.getApi().today(), [])
  const handlePrev = useCallback(() => calendarRef.current?.getApi().prev(), [])
  const handleNext = useCallback(() => calendarRef.current?.getApi().next(), [])

  const handleViewChange = useCallback((view: CalendarView) => {
    calendarRef.current?.getApi().changeView(view)
  }, [])

  const handleMiniDateClick = useCallback((date: Date) => {
    calendarRef.current?.getApi().changeView('timeGridDay', date)
  }, [])

  const toggleCalendar = useCallback((calId: string) => {
    setEnabledCalendars((prev) => {
      if (!prev) return prev
      const next = new Set(prev)
      if (next.has(calId)) next.delete(calId)
      else next.add(calId)
      return next
    })
  }, [])

  // --- Toolbar title ---
  const title = useMemo(() => getToolbarTitle(viewDate, currentView), [viewDate, currentView])

  // --- Navigation aria labels ---
  const navAriaLabel = currentView === 'dayGridMonth' ? 'Mois' : currentView === 'timeGridWeek' ? 'Semaine' : 'Jour'

  // --- FullCalendar theme overrides ---
  const calendarThemeSx = useMemo(() => getCalendarThemeSx(theme), [theme])

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 48px)' }}>
      {/* ---- Top toolbar ---- */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          px: 2,
          py: 1,
          borderBottom: 1,
          borderColor: 'divider',
          gap: 0.5,
          flexShrink: 0,
        }}
      >
        <Button
          variant="outlined"
          size="small"
          onClick={handleToday}
          sx={{ textTransform: 'none', borderRadius: 2, mr: 0.5 }}
        >
          Aujourd&apos;hui
        </Button>
        <IconButton size="small" onClick={handlePrev} aria-label={`${navAriaLabel} précédent(e)`}>
          <ChevronLeftIcon />
        </IconButton>
        <IconButton size="small" onClick={handleNext} aria-label={`${navAriaLabel} suivant(e)`}>
          <ChevronRightIcon />
        </IconButton>
        <Typography variant="h6" sx={{ ml: 1, textTransform: 'capitalize', fontWeight: 400 }}>
          {title}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <ButtonGroup size="small" variant="outlined" sx={{ '& .MuiButton-root': { textTransform: 'none', borderRadius: 2 } }}>
          {VIEW_BUTTONS.map(({ label, view }) => (
            <Button
              key={view}
              variant={currentView === view ? 'contained' : 'outlined'}
              onClick={() => handleViewChange(view)}
            >
              {label}
            </Button>
          ))}
        </ButtonGroup>
      </Box>

      {/* ---- Body: sidebar + calendar ---- */}
      <Box sx={{ display: 'flex', flex: 1, minHeight: 0 }}>
        {/* Left sidebar */}
        <Box
          sx={{
            width: SIDEBAR_WIDTH,
            flexShrink: 0,
            borderRight: 1,
            borderColor: 'divider',
            overflowY: 'auto',
            p: 1.5,
            display: { xs: 'none', md: 'block' },
          }}
        >
          {/* + Créer */}
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            onClick={() => {
              setDialogDefaultDate(undefined)
              setDialogOpen(true)
            }}
            sx={{
              textTransform: 'none',
              borderRadius: 6,
              mb: 2.5,
              px: 3,
              boxShadow: 2,
            }}
          >
            Créer
          </Button>

          {/* Mini calendar */}
          <MiniCalendar
            viewDate={miniCalDate}
            onDateClick={handleMiniDateClick}
            onMonthChange={setMiniCalDate}
          />

          {/* Calendar list */}
          {calendars.length > 0 && (
            <Box sx={{ mt: 3 }}>
              <Typography
                variant="caption"
                fontWeight={500}
                color="text.secondary"
                sx={{ px: 0.5, mb: 0.5, display: 'block', letterSpacing: 0.5 }}
              >
                Mes agendas
              </Typography>
              {calendars.map((cal) => (
                <Box
                  key={cal.id}
                  onClick={() => toggleCalendar(cal.id)}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    py: 0.25,
                    px: 0.5,
                    cursor: 'pointer',
                    borderRadius: 1,
                    '&:hover': { bgcolor: 'action.hover' },
                  }}
                >
                  <Checkbox
                    size="small"
                    checked={enabledCalendars?.has(cal.id) ?? true}
                    tabIndex={-1}
                    disableRipple
                    sx={{
                      p: 0.25,
                      color: cal.color || 'primary.main',
                      '&.Mui-checked': { color: cal.color || 'primary.main' },
                    }}
                  />
                  <Typography variant="body2" sx={{ ml: 0.5 }}>
                    {cal.name}
                  </Typography>
                </Box>
              ))}
            </Box>
          )}
        </Box>

        {/* Main calendar */}
        <Box sx={calendarThemeSx}>
          <FullCalendar
            ref={calendarRef}
            plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
            initialView="dayGridMonth"
            locale={frLocale}
            events={filteredEvents}
            datesSet={handleDatesSet}
            dateClick={handleDateClick}
            eventClick={handleEventClick}
            headerToolbar={false}
            height="100%"
            dayMaxEvents={3}
            moreLinkText={(n) => `+${n} autre${n > 1 ? 's' : ''}`}
            eventTimeFormat={{ hour: '2-digit', minute: '2-digit', hour12: false }}
            fixedWeekCount={false}
            nowIndicator={true}
          />
        </Box>
      </Box>

      <EventCreateDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onCreated={handleCreated}
        defaultDate={dialogDefaultDate}
      />

      <EventDetailPopover
        event={popoverEvent}
        anchorEl={popoverAnchorEl}
        onClose={() => {
          setPopoverAnchorEl(null)
          setPopoverEvent(null)
        }}
        onDelete={handleDeleteEvent}
      />
    </Box>
  )
}

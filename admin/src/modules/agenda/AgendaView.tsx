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
import type { EventClickArg } from '@fullcalendar/core'
import frLocale from '@fullcalendar/core/locales/fr'
import type { DatesSetArg, EventInput } from '@fullcalendar/core'
import { EventCreateDialog } from './EventCreateDialog'
import { EventDetailPopover } from './EventDetailPopover'
import type { PopoverEvent } from './EventDetailPopover'
import { getCalendarThemeSx } from './calendarTheme'

const SIDEBAR_WIDTH = 230
const DAY_LABELS = ['L', 'M', 'M', 'J', 'V', 'S', 'D']

type CalendarView = 'dayGridMonth' | 'timeGridWeek' | 'timeGridDay' | 'timeGrid' | 'dayGrid'

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
const toDateKey = (d: Date) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`

/** Snap a date back to Monday of its week. */
const toMonday = (d: Date): Date => {
  const day = d.getDay()
  const off = day === 0 ? 6 : day - 1
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() - off)
}

/** Snap a date forward to the next Monday (exclusive week end). */
const toNextMonday = (d: Date): Date => {
  const day = d.getDay()
  const off = day === 0 ? 1 : day === 1 ? 7 : 8 - day
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + off)
}

/**
 * Given a raw drag range (both inclusive), return the effective selection.
 * - 1-7 days: exact days, end is exclusive (+1 day)
 * - >7 days: snap to full weeks (Monday boundaries)
 */
const snapRange = (a: Date, b: Date): { start: Date; end: Date } => {
  const s = a <= b ? a : b
  const e = a <= b ? b : a
  const days = Math.round((e.getTime() - s.getTime()) / 86_400_000) + 1
  if (days <= 7) {
    return { start: s, end: new Date(e.getFullYear(), e.getMonth(), e.getDate() + 1) }
  }
  return { start: toMonday(s), end: toNextMonday(e) }
}

const MiniCalendar = ({
  viewDate,
  activeStart,
  activeEnd,
  onRangeSelect,
  onMonthChange,
}: {
  viewDate: Date
  activeStart: Date | null
  activeEnd: Date | null
  onRangeSelect: (start: Date, end: Date) => void
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

  // --- Drag selection state ---
  const [dragStart, setDragStart] = useState<Date | null>(null)
  const [dragEnd, setDragEnd] = useState<Date | null>(null)
  const dragging = useRef(false)

  const handleMouseDown = useCallback((d: Date) => {
    dragging.current = true
    setDragStart(d)
    setDragEnd(d)
  }, [])

  const handleMouseEnter = useCallback((d: Date) => {
    if (dragging.current) setDragEnd(d)
  }, [])

  const handleMouseUp = useCallback(() => {
    if (!dragging.current || !dragStart || !dragEnd) {
      dragging.current = false
      return
    }
    dragging.current = false
    const { start, end } = snapRange(dragStart, dragEnd)
    onRangeSelect(start, end)
    setDragStart(null)
    setDragEnd(null)
  }, [dragStart, dragEnd, onRangeSelect])

  // Cancel drag if mouse leaves the grid
  const handleMouseLeave = useCallback(() => {
    if (dragging.current) {
      dragging.current = false
      setDragStart(null)
      setDragEnd(null)
    }
  }, [])

  // Compute highlight range: use snapped drag preview if dragging, otherwise the main view's active range
  const { highlightStart, highlightEnd } = useMemo(() => {
    if (dragStart && dragEnd) {
      const snapped = snapRange(dragStart, dragEnd)
      return { highlightStart: snapped.start, highlightEnd: snapped.end }
    }
    const hs = activeStart ? new Date(activeStart.getFullYear(), activeStart.getMonth(), activeStart.getDate()) : null
    const he = activeEnd ? new Date(activeEnd.getFullYear(), activeEnd.getMonth(), activeEnd.getDate()) : null
    return { highlightStart: hs, highlightEnd: he }
  }, [dragStart, dragEnd, activeStart, activeEnd])

  const isInRange = useCallback(
    (d: Date): boolean => {
      if (!highlightStart || !highlightEnd) return false
      const t = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
      return t >= highlightStart.getTime() && t < highlightEnd.getTime()
    },
    [highlightStart, highlightEnd],
  )

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
      <Box
        sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', textAlign: 'center', userSelect: 'none' }}
        onMouseLeave={handleMouseLeave}
        onMouseUp={handleMouseUp}
      >
        {DAY_LABELS.map((d, i) => (
          <Typography key={i} variant="caption" color="text.secondary" sx={{ py: 0.25, fontSize: '0.65rem' }}>
            {d}
          </Typography>
        ))}
        {days.map((d, idx) => {
          const isToday = d.toDateString() === today.toDateString()
          const isCurMonth = d.getMonth() === month
          const active = isInRange(d)
          const col = idx % 7
          const prevActive = active && (col === 0 || (idx > 0 && isInRange(days[idx - 1])))
          const nextActive = active && (col === 6 || (idx < days.length - 1 && isInRange(days[idx + 1])))
          const roundL = active && !prevActive
          const roundR = active && !nextActive
          return (
            <Box
              key={toDateKey(d)}
              onMouseDown={(e) => { e.preventDefault(); handleMouseDown(d) }}
              onMouseEnter={() => handleMouseEnter(d)}
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                height: 26,
                cursor: 'pointer',
                bgcolor: active ? 'action.selected' : 'transparent',
                borderTopLeftRadius: roundL ? 13 : 0,
                borderBottomLeftRadius: roundL ? 13 : 0,
                borderTopRightRadius: roundR ? 13 : 0,
                borderBottomRightRadius: roundR ? 13 : 0,
              }}
            >
              <Box
                sx={{
                  width: 26,
                  height: 26,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderRadius: '50%',
                  fontSize: '0.7rem',
                  fontWeight: isToday ? 600 : 400,
                  color: isToday ? '#fff' : isCurMonth ? 'text.primary' : 'text.disabled',
                  bgcolor: isToday ? 'primary.main' : 'transparent',
                  '&:hover': { bgcolor: isToday ? 'primary.dark' : active ? undefined : 'action.hover' },
                }}
              >
                {d.getDate()}
              </Box>
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
const formatDateRange = (start: Date, end: Date): string => {
  // end is exclusive, show up to the day before
  const last = new Date(end)
  last.setDate(last.getDate() - 1)
  if (start.toDateString() === last.toDateString()) {
    return start.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  }
  const startDay = start.getDate()
  const endDay = last.getDate()
  const endMonth = last.toLocaleDateString('fr-FR', { month: 'short', year: 'numeric' })
  if (start.getMonth() === last.getMonth() && start.getFullYear() === last.getFullYear()) {
    return `${startDay} – ${endDay} ${endMonth}`
  }
  const startMonth = start.toLocaleDateString('fr-FR', { month: 'short' })
  return `${startDay} ${startMonth} – ${endDay} ${endMonth}`
}

const getToolbarTitle = (view: CalendarView, range: { start: Date; end: Date } | null): string => {
  if (!range) return ''
  if (view === 'dayGridMonth') {
    return range.start.toLocaleDateString('fr-FR', { month: 'long', year: 'numeric' })
  }
  if (view === 'timeGridDay') {
    return range.start.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
  }
  return formatDateRange(range.start, range.end)
}

// ---------------------------------------------------------------------------
// Agenda View
// ---------------------------------------------------------------------------
export const AgendaView = () => {
  const theme = useTheme()
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const calendarRef = useRef<FullCalendar>(null)
  const calendarBoxRef = useRef<HTMLDivElement>(null)
  const dateRangeRef = useRef<{ start: string; end: string } | null>(null)

  const [rawEvents, setRawEvents] = useState<AgendaEvent[]>([])
  const [calendars, setCalendars] = useState<CalendarData[]>([])
  const [enabledCalendars, setEnabledCalendars] = useState<Set<string> | null>(null)
  const [miniCalDate, setMiniCalDate] = useState(new Date())
  const [currentView, setCurrentView] = useState<CalendarView>('dayGridMonth')
  const [activeRange, setActiveRange] = useState<{ start: Date; end: Date } | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [dialogStart, setDialogStart] = useState<Date | undefined>()
  const [dialogEnd, setDialogEnd] = useState<Date | undefined>()
  const [dialogAllDay, setDialogAllDay] = useState(false)

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
      setMiniCalDate(arg.view.currentStart)
      setCurrentView(arg.view.type as CalendarView)
      setActiveRange({ start: arg.view.currentStart, end: arg.view.currentEnd })
      fetchEvents(start, end)
    },
    [fetchEvents],
  )

  const handleSelect = useCallback((arg: { start: Date; end: Date; allDay: boolean }) => {
    setDialogStart(arg.start)
    setDialogEnd(arg.end)
    setDialogAllDay(arg.allDay)
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

  // --- Wheel navigation (scroll up → prev, scroll down → next) ---
  useEffect(() => {
    const el = calendarBoxRef.current
    if (!el) return
    let cooldown = false
    const handleWheel = (e: WheelEvent) => {
      if (cooldown || Math.abs(e.deltaY) < 30) return
      e.preventDefault()
      cooldown = true
      const api = calendarRef.current?.getApi()
      if (api) {
        if (e.deltaY < 0) api.prev()
        else api.next()
      }
      setTimeout(() => { cooldown = false }, 400)
    }
    el.addEventListener('wheel', handleWheel, { passive: false })
    return () => el.removeEventListener('wheel', handleWheel)
  }, [])

  // --- Navigation ---
  const handleToday = useCallback(() => calendarRef.current?.getApi().today(), [])
  const handlePrev = useCallback(() => calendarRef.current?.getApi().prev(), [])
  const handleNext = useCallback(() => calendarRef.current?.getApi().next(), [])

  const handleViewChange = useCallback((view: CalendarView) => {
    calendarRef.current?.getApi().changeView(view)
  }, [])

  const handleMiniRangeSelect = useCallback((start: Date, end: Date) => {
    const api = calendarRef.current?.getApi()
    if (!api) return
    const days = Math.round((end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24))
    if (days <= 7) {
      // 1-7 days → timegrid
      api.changeView('timeGrid', { start, end })
    } else if (days <= 28) {
      // 8-28 days → daygrid
      api.changeView('dayGrid', { start, end })
    } else {
      // >28 days → standard month view
      api.changeView('dayGridMonth', start)
    }
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
  const title = useMemo(() => getToolbarTitle(currentView, activeRange), [currentView, activeRange])

  // --- Navigation aria labels ---
  const navAriaLabel = currentView === 'dayGridMonth' || currentView === 'dayGrid' ? 'Mois' : currentView === 'timeGridWeek' || currentView === 'timeGrid' ? 'Semaine' : 'Jour'

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
              setDialogStart(undefined)
              setDialogEnd(undefined)
              setDialogAllDay(false)
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
            activeStart={currentView === 'dayGridMonth' ? null : (activeRange?.start ?? null)}
            activeEnd={currentView === 'dayGridMonth' ? null : (activeRange?.end ?? null)}
            onRangeSelect={handleMiniRangeSelect}
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
        <Box ref={calendarBoxRef} sx={calendarThemeSx}>
          <FullCalendar
            ref={calendarRef}
            plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
            initialView="dayGridMonth"
            locale={frLocale}
            events={filteredEvents}
            datesSet={handleDatesSet}
            selectable={true}
            select={handleSelect}
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
        defaultStart={dialogStart}
        defaultEnd={dialogEnd}
        defaultAllDay={dialogAllDay}
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

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import { useSearchParams } from 'react-router-dom'
import { useTheme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import { localDay } from '../../dates'
import { useMercure } from '../../hooks/useMercure'
import { useUserPreferences } from '../../hooks/useUserPreferences'
import Button from '@mui/material/Button'
import ButtonGroup from '@mui/material/ButtonGroup'
import Checkbox from '@mui/material/Checkbox'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import Grow from '@mui/material/Grow'
import IconButton from '@mui/material/IconButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import MenuItem from '@mui/material/MenuItem'
import MenuList from '@mui/material/MenuList'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ArrowDropDownIcon from '@mui/icons-material/ArrowDropDown'
import ChecklistIcon from '@mui/icons-material/Checklist'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import CloudUploadIcon from '@mui/icons-material/CloudUpload'
import DeleteIcon from '@mui/icons-material/Delete'
import EventIcon from '@mui/icons-material/Event'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import RestaurantIcon from '@mui/icons-material/Restaurant'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import SyncIcon from '@mui/icons-material/Sync'
import CircularProgress from '@mui/material/CircularProgress'
import Menu from '@mui/material/Menu'
import TextField from '@mui/material/TextField'
import FullCalendar from '@fullcalendar/react'
import dayGridPlugin from '@fullcalendar/daygrid'
import timeGridPlugin from '@fullcalendar/timegrid'
import interactionPlugin from '@fullcalendar/interaction'
import type { EventClickArg, EventDropArg } from '@fullcalendar/core'
import type { EventResizeDoneArg } from '@fullcalendar/interaction'
import frLocale from '@fullcalendar/core/locales/fr'
import type { DatesSetArg, EventInput } from '@fullcalendar/core'
import { EventCreateDialog } from './EventCreateDialog'
import { TaskCreateDialog } from './TaskCreateDialog'
import { TaskEditDialog } from './TaskEditDialog'
import { EventDetailPopover } from './EventDetailPopover'
import { EventEditDialog } from './EventEditDialog'
import type { EventEditValues } from './EventEditDialog'
import type { PopoverEvent } from './EventDetailPopover'
import { getCalendarThemeSx, getEventTextColor } from './calendarTheme'
import { addUntilToRrule, expandRrule } from './recurrenceUtils'

const apiUrl = import.meta.env.VITE_API_URL || 'http://localhost/api'

function authFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(`${apiUrl}${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

// react-admin's Hydra provider puts the IRI in `record.id` (`/api/events/01M3…`)
const bareId = (id: string) => id.split('/').pop() ?? id
const eventIri = (id: string) => (id.startsWith('/') ? id : `/api/events/${id}`)
const mealIri = (id: string) => (id.startsWith('/') ? id : `/api/meals/${id}`)

const MEAL_COLOR = '#FF6B35'
const TASK_DONE_COLOR = '#9E9E9E'
const MEAL_SLOT_LABELS: Record<string, string> = { lunch: 'Déj', dinner: 'Dîner' }
const mealTitle = (m: CalendarMeal) => {
  const label = MEAL_SLOT_LABELS[m.slot] || m.slot
  const recipeName = m.recipes?.length ? m.recipes.map((r) => r.name).join(', ') : m.summary
  return `${label}: ${recipeName}`
}

interface GoogleCalendar {
  id: string
  /** The name the agenda will carry — "Défaut" for the primary calendar (MAG-148). */
  name: string
  summary: string
  description?: string
  primary: boolean
  backgroundColor?: string
}

const CALENDAR_TOPICS = ['/api/events/{id}', '/api/tasks/{id}']
const AGENDA_TOPICS = ['/api/agendas/{id}']
const MERCURE_REFETCH_DELAYS_MS = [1500, 5000]
const SIDEBAR_WIDTH = 230
const DAY_LABELS = ['L', 'M', 'M', 'J', 'V', 'S', 'D']

type CalendarView = 'dayGridMonth' | 'timeGridWeek' | 'timeGridDay' | 'timeGrid' | 'dayGrid'

const PREFERRED_VIEWS: Record<string, CalendarView> = {
  month: 'dayGridMonth',
  week: 'timeGridWeek',
  day: 'timeGridDay',
}

const VIEW_BUTTONS: { label: string; view: CalendarView }[] = [
  { label: 'Mois', view: 'dayGridMonth' },
  { label: 'Semaine', view: 'timeGridWeek' },
  { label: 'Jour', view: 'timeGridDay' },
]

interface CalendarEvent {
  id: string
  summary: string
  description?: string
  location?: string
  startAt: string
  endAt: string
  allDay: boolean
  agenda: string
  timeZone?: string
  rrule?: string
  recurringEvent?: string
  originalStartAt?: string
  status?: string
}

interface CalendarTask {
  id: string
  title: string
  description?: string
  priority: string
  criticality: string
  dueDate?: string
  completedAt?: string
}

interface CalendarMeal {
  id: string
  /** The day, `YYYY-MM-DD`. A meal has no time (MAG-251). */
  date: string
  slot: string
  summary: string
  recipes: { id: string; name: string }[]
}

const TASK_CRITICALITY_COLORS: Record<string, string> = {
  low: '#4CAF50',
  medium: '#FF9800',
  high: '#F44336',
  critical: '#9C27B0',
}

type RecurrenceAction = 'this' | 'thisAndFollowing' | 'all'

interface RecurrenceConfirm {
  type: 'update' | 'delete'
  eventId: string
  masterEventId: string
  occurrenceStart: string
  calendarIri: string
  summary: string
  rrule: string
  allDay: boolean
  timeZone: string
  // For updates only:
  newStart?: string
  newEnd?: string
  newAllDay?: boolean
  // Set when the change comes from the edit dialog (a drag changes times only)
  edit?: { summary: string; description: string | null; location: string | null }
}

interface EditingEvent {
  eventId: string
  masterEventId: string
  occurrenceStart: string
  calendarIri: string
  rrule: string
  recurring: boolean
  event: {
    summary: string
    start: string
    end: string
    allDay: boolean
    description?: string
    location?: string
  }
}

interface CalendarData {
  id: string
  name: string
  color: string | null
  default: boolean
  googleCalendarId?: string
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
export const CalendarView = () => {
  const theme = useTheme()
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const [searchParams, setSearchParams] = useSearchParams()
  const calendarRef = useRef<FullCalendar>(null)
  const calendarBoxRef = useRef<HTMLDivElement>(null)
  const dateRangeRef = useRef<{ start: string; end: string } | null>(null)
  const lastMouseYRef = useRef(0)
  const selectFiredRef = useRef(false)
  const lastSelectionRef = useRef<{ start: Date; end: Date } | null>(null)

  const [rawEvents, setRawEvents] = useState<CalendarEvent[]>([])
  const [rawTasks, setRawTasks] = useState<CalendarTask[]>([])
  const [rawMeals, setRawMeals] = useState<CalendarMeal[]>([])
  const [calendars, setCalendars] = useState<CalendarData[]>([])
  const [enabledCalendars, setEnabledCalendars] = useState<Set<string> | null>(null)
  const [miniCalDate, setMiniCalDate] = useState(new Date())
  const [currentView, setCurrentView] = useState<CalendarView>('dayGridMonth')
  const [activeRange, setActiveRange] = useState<{ start: Date; end: Date } | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [dialogStart, setDialogStart] = useState<Date | undefined>()
  const [dialogEnd, setDialogEnd] = useState<Date | undefined>()
  const [dialogAllDay, setDialogAllDay] = useState(false)
  const [taskDialogOpen, setTaskDialogOpen] = useState(false)
  const [editingTask, setEditingTask] = useState<CalendarTask | null>(null)
  const [editingEvent, setEditingEvent] = useState<EditingEvent | null>(null)
  const [createMenuOpen, setCreateMenuOpen] = useState(false)
  const createMenuAnchorRef = useRef<HTMLDivElement>(null)

  // Popover state
  const [popoverEvent, setPopoverEvent] = useState<PopoverEvent | null>(null)
  const [popoverAnchorEl, setPopoverAnchorEl] = useState<HTMLElement | null>(null)

  // Recurrence confirmation dialog state
  const [recurrenceConfirm, setRecurrenceConfirm] = useState<RecurrenceConfirm | null>(null)
  const [recurrenceAction, setRecurrenceAction] = useState<RecurrenceAction>('this')

  // Agenda sidebar state
  const [agendaMenuAnchor, setAgendaMenuAnchor] = useState<HTMLElement | null>(null)
  const [agendaMenuTarget, setAgendaMenuTarget] = useState<CalendarData | null>(null)
  const [deleteDialogAgenda, setDeleteDialogAgenda] = useState<CalendarData | null>(null)
  const [addMenuAnchor, setAddMenuAnchor] = useState<HTMLElement | null>(null)
  const [createAgendaDialogOpen, setCreateAgendaDialogOpen] = useState(false)
  const [newAgendaName, setNewAgendaName] = useState('')
  const [newAgendaColor, setNewAgendaColor] = useState('#1976d2')
  const [importDialogOpen, setImportDialogOpen] = useState(false)
  const [googleCalendars, setGoogleCalendars] = useState<GoogleCalendar[]>([])
  const [importLoading, setImportLoading] = useState(false)
  const [exportLoading, setExportLoading] = useState(false)
  const [deleteLoading, setDeleteLoading] = useState(false)
  const [createAgendaLoading, setCreateAgendaLoading] = useState(false)

  // --- Fetch calendars ---
  const knownCalendarIds = useRef(new Set<string>())
  const loadCalendars = useCallback(() => {
    dataProvider
      .getList('agendas', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'name', order: 'ASC' },
        filter: {},
      })
      .then(({ data }) => {
        const cals = data as unknown as CalendarData[]
        setCalendars(cals)
        const known = knownCalendarIds.current
        const fresh = cals.filter((c) => !known.has(c.id))
        for (const c of fresh) known.add(c.id)
        setEnabledCalendars((prev) => {
          if (prev === null) return new Set([...cals.map((c) => c.id), '__tasks__', '__meals__'])
          // Keep existing toggles (a hidden agenda stays hidden), show the agendas never seen before
          const next = new Set(prev)
          for (const c of fresh) next.add(c.id)
          return next
        })
      })
      .catch(console.error)
  }, [dataProvider])

  useEffect(() => { loadCalendars() }, [loadCalendars])

  // --- Saved preferences: the view the grid opens on and the agendas it shows ---
  const { preferences } = useUserPreferences()
  const preferencesApplied = useRef({ view: false, agendas: false })

  useEffect(() => {
    const view = preferences ? PREFERRED_VIEWS[preferences.defaultCalendarView] : undefined
    if (!view || preferencesApplied.current.view) return
    preferencesApplied.current.view = true
    calendarRef.current?.getApi().changeView(view)
  }, [preferences])

  useEffect(() => {
    const saved = preferences?.enabledAgendaIds
    if (!Array.isArray(saved) || 0 === saved.length || 0 === calendars.length || preferencesApplied.current.agendas) return
    preferencesApplied.current.agendas = true
    const savedIds = new Set(saved.map(bareId))
    const shown = calendars.filter((c) => savedIds.has(bareId(c.id)))
    // Every saved agenda deleted since: an empty screen would look broken, not filtered.
    if (0 === shown.length) return
    setEnabledCalendars(new Set([...shown.map((c) => c.id), '__tasks__', '__meals__']))
  }, [preferences, calendars])

  // --- Agenda sidebar handlers ---
  const handleAgendaMenuOpen = useCallback((e: React.MouseEvent<HTMLElement>, cal: CalendarData) => {
    e.stopPropagation()
    setAgendaMenuAnchor(e.currentTarget)
    setAgendaMenuTarget(cal)
  }, [])

  const handleAgendaMenuClose = useCallback(() => {
    setAgendaMenuAnchor(null)
    setAgendaMenuTarget(null)
  }, [])

  const handleExportToGoogle = useCallback(async () => {
    if (!agendaMenuTarget) return
    handleAgendaMenuClose()
    setExportLoading(true)
    try {
      const res = await authFetch('/calendar/google/export', {
        method: 'POST',
        body: JSON.stringify({ agendaId: bareId(agendaMenuTarget.id) }),
      })
      if (res.ok) {
        notify('Agenda exporté vers Google Calendar', { type: 'success' })
        loadCalendars()
      } else {
        const data = await res.json()
        notify(data.error || "Erreur lors de l'export", { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setExportLoading(false)
    }
  }, [agendaMenuTarget, handleAgendaMenuClose, notify, loadCalendars])

  const handleSetDefaultAgenda = useCallback(async () => {
    if (!agendaMenuTarget) return
    const target = agendaMenuTarget
    handleAgendaMenuClose()
    try {
      await dataProvider.update('agendas', {
        id: target.id,
        data: { isDefault: true },
        previousData: { id: target.id },
      })
      notify(`« ${target.name} » est maintenant l'agenda par défaut`, { type: 'success' })
      loadCalendars()
    } catch (error) {
      notify(`Erreur: ${(error as Error).message}`, { type: 'error' })
    }
  }, [agendaMenuTarget, handleAgendaMenuClose, dataProvider, notify, loadCalendars])

  const handleCreateAgenda = useCallback(async () => {
    if (!newAgendaName.trim()) return
    setCreateAgendaLoading(true)
    try {
      await dataProvider.create('agendas', {
        data: { name: newAgendaName.trim(), color: newAgendaColor },
      })
      notify('Agenda créé', { type: 'success' })
      setCreateAgendaDialogOpen(false)
      setNewAgendaName('')
      setNewAgendaColor('#1976d2')
      loadCalendars()
    } catch (error) {
      notify(`Erreur: ${(error as Error).message}`, { type: 'error' })
    } finally {
      setCreateAgendaLoading(false)
    }
  }, [newAgendaName, newAgendaColor, dataProvider, notify, loadCalendars])

  const handleOpenImportDialog = useCallback(async () => {
    setAddMenuAnchor(null)
    setImportDialogOpen(true)
    setImportLoading(true)
    try {
      const res = await authFetch('/calendar/google/calendars')
      if (res.ok) {
        setGoogleCalendars(await res.json())
      } else {
        notify('Impossible de charger les calendriers Google', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setImportLoading(false)
    }
  }, [notify])

  const handleImportCalendar = useCallback(async (googleCalendarId: string) => {
    setImportLoading(true)
    try {
      const res = await authFetch('/calendar/google/import', {
        method: 'POST',
        body: JSON.stringify({ googleCalendarId }),
      })
      if (res.ok) {
        notify('Calendrier importé avec succès', { type: 'success' })
        setImportDialogOpen(false)
        loadCalendars()
      } else {
        const data = await res.json()
        notify(data.error || "Erreur lors de l'import", { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setImportLoading(false)
    }
  }, [notify, loadCalendars])

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

  // --- Fetch events + tasks ---
  const fetchEvents = useCallback(
    (start: string, end: string) => {
      const rangeEvents = dataProvider.getList('events', {
        pagination: { page: 1, perPage: 200 },
        sort: { field: 'startAt', order: 'ASC' },
        filter: { 'startAt[after]': start, 'startAt[before]': end },
      })

      // Recurring events may have started before the visible range
      const recurringEvents = dataProvider.getList('events', {
        pagination: { page: 1, perPage: 200 },
        sort: { field: 'startAt', order: 'ASC' },
        filter: { 'exists[rrule]': true, 'startAt[strictly_before]': start },
      })

      // A multi-day event that started before the range still covers its first days
      const overlappingEvents = dataProvider.getList('events', {
        pagination: { page: 1, perPage: 200 },
        sort: { field: 'startAt', order: 'ASC' },
        filter: { 'endAt[after]': start, 'startAt[strictly_before]': start },
      })

      // Fetch tasks with due dates in visible range
      const rangeTasks = dataProvider.getList('tasks', {
        pagination: { page: 1, perPage: 200 },
        sort: { field: 'dueDate', order: 'ASC' },
        filter: { 'dueDate[after]': start, 'dueDate[before]': end },
      })

      // Fetch meals in visible range. By day, not by instant: a meal is a day
      // and a slot, and `date` is a day-typed field — an instant would not even
      // parse against it (MAG-251).
      const rangeMeals = dataProvider.getList('meals', {
        pagination: { page: 1, perPage: 200 },
        sort: { field: 'date', order: 'ASC' },
        filter: { 'date[after]': localDay(new Date(start)), 'date[before]': localDay(new Date(end)) },
      })

      Promise.all([rangeEvents, recurringEvents, overlappingEvents, rangeTasks, rangeMeals])
        .then(([rangeResult, recurringResult, overlappingResult, tasksResult, mealsResult]) => {
          const seen = new Set<string>()
          const merged: CalendarEvent[] = []
          for (const e of [...rangeResult.data, ...recurringResult.data, ...overlappingResult.data] as unknown as CalendarEvent[]) {
            if (!seen.has(e.id)) {
              seen.add(e.id)
              merged.push(e)
            }
          }
          setRawEvents(merged)
          setRawTasks(tasksResult.data as unknown as CalendarTask[])
          setRawMeals(mealsResult.data as unknown as CalendarMeal[])
        })
        .catch(console.error)
    },
    [dataProvider],
  )

  const handleDeleteAgenda = useCallback(async (deleteGoogleCalendar: boolean) => {
    if (!deleteDialogAgenda) return
    setDeleteLoading(true)
    try {
      const qs = deleteGoogleCalendar ? '?deleteGoogleCalendar=true' : ''
      const res = await authFetch(`/agendas/${bareId(deleteDialogAgenda.id)}${qs}`, {
        method: 'DELETE',
      })
      if (res.ok || res.status === 204) {
        notify('Agenda supprimé', { type: 'success' })
        setDeleteDialogAgenda(null)
        loadCalendars()
        if (dateRangeRef.current) {
          fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
        }
      } else {
        const data = await res.json().catch(() => ({ error: 'Erreur' }))
        notify(data.error || 'Erreur lors de la suppression', { type: 'error' })
      }
    } catch {
      notify('Erreur réseau', { type: 'error' })
    } finally {
      setDeleteLoading(false)
    }
  }, [deleteDialogAgenda, notify, loadCalendars, fetchEvents])

  // --- Map raw events → FullCalendar events with calendar colours + recurrence expansion ---
  const coloredEvents: (EventInput & { calendarId: string })[] = useMemo(() => {
    const result: (EventInput & { calendarId: string })[] = []
    const rangeStart = activeRange?.start
    const rangeEnd = activeRange?.end

    // First pass: build exception map  masterIRI → originalStartAt(epoch ms) → exception event
    const exceptionMap = new Map<string, Map<number, CalendarEvent>>()
    for (const e of rawEvents) {
      if (e.recurringEvent && e.originalStartAt) {
        const key = e.recurringEvent
        if (!exceptionMap.has(key)) exceptionMap.set(key, new Map())
        exceptionMap.get(key)!.set(new Date(e.originalStartAt).getTime(), e)
      }
    }

    // Second pass: expand events
    for (const e of rawEvents) {
      // Skip exception instances — they are rendered inline during master expansion
      if (e.recurringEvent) continue

      const calId = typeof e.agenda === 'string' ? e.agenda : ''
      const color = calendarColorMap.get(calId)
      const masterIri = eventIri(e.id)

      if (e.rrule && rangeStart && rangeEnd) {
        const dtstart = new Date(e.startAt)
        const duration = new Date(e.endAt).getTime() - dtstart.getTime()
        const occurrences = expandRrule(e.rrule, dtstart, rangeStart, rangeEnd, e.timeZone)
        const exceptions = exceptionMap.get(masterIri)

        for (const occ of occurrences) {
          const occTime = occ.getTime()
          const exception = exceptions?.get(occTime)

          if (exception) {
            if (exception.status === 'cancelled') {
              // Cancelled exception → skip this occurrence
              continue
            }
            // Modified exception → render the exception as a real event
            const excCalId = typeof exception.agenda === 'string' ? exception.agenda : calId
            const excColor = calendarColorMap.get(excCalId) || color
            result.push({
              id: exception.id,
              title: exception.summary,
              start: exception.startAt,
              end: exception.endAt,
              allDay: exception.allDay,
              calendarId: excCalId,
              backgroundColor: excColor,
              borderColor: excColor,
              textColor: getEventTextColor(theme, excColor),
              extendedProps: {
                description: exception.description,
                location: exception.location,
                calendarId: excCalId,
                calendarIri: exception.agenda,
                isException: true,
                masterEventId: e.id,
              },
            })
          } else {
            // No exception → render as virtual occurrence
            const occEnd = new Date(occTime + duration)
            const isoDate = occ.toISOString().slice(0, 10)
            result.push({
              id: `${e.id}__${isoDate}`,
              title: e.summary,
              start: occ.toISOString(),
              end: occEnd.toISOString(),
              allDay: e.allDay,
              calendarId: calId,
              backgroundColor: color,
              borderColor: color,
              textColor: getEventTextColor(theme, color),
              extendedProps: {
                description: e.description,
                location: e.location,
                calendarId: calId,
                calendarIri: e.agenda,
                rrule: e.rrule,
                masterEventId: e.id,
                masterSummary: e.summary,
                masterAllDay: e.allDay,
                masterTimeZone: e.agenda,
                isVirtualOccurrence: true,
              },
            })
          }
        }
      } else {
        // Non-recurring event
        result.push({
          id: e.id,
          title: e.summary,
          start: e.startAt,
          end: e.endAt,
          allDay: e.allDay,
          calendarId: calId,
          backgroundColor: color,
          borderColor: color,
          textColor: getEventTextColor(theme, color),
          extendedProps: {
            description: e.description,
            location: e.location,
            calendarId: calId,
            calendarIri: e.agenda,
          },
        })
      }
    }

    return result
  }, [rawEvents, calendarColorMap, activeRange, theme])

  // --- Map tasks → FullCalendar all-day events with criticality colors ---
  const taskEvents: (EventInput & { calendarId: string })[] = useMemo(() => {
    return rawTasks
      .filter((t) => t.dueDate)
      .map((t) => {
        const color = TASK_CRITICALITY_COLORS[t.criticality] || TASK_CRITICALITY_COLORS.low
        const isDone = t.completedAt != null
        const background = isDone ? TASK_DONE_COLOR : color
        return {
          id: `task-${t.id}`,
          title: `${isDone ? '\u2713 ' : ''}${t.title}`,
          start: t.dueDate!,
          allDay: true,
          calendarId: '__tasks__',
          backgroundColor: background,
          borderColor: background,
          textColor: getEventTextColor(theme, background),
          extendedProps: {
            description: t.description,
            isTask: true,
            priority: t.priority,
            criticality: t.criticality,
            isDone,
          },
        }
      })
  }, [rawTasks, theme])

  // --- Map meals → FullCalendar all-day events ---
  const mealEvents: (EventInput & { calendarId: string })[] = useMemo(() => {
    return rawMeals.map((m) => {
      return {
        id: `meal-${m.id}`,
        title: mealTitle(m),
        // A plain day: FullCalendar places it on that day, with no offset to
        // get wrong (MAG-251).
        start: m.date,
        allDay: true,
        calendarId: '__meals__',
        backgroundColor: MEAL_COLOR,
        borderColor: MEAL_COLOR,
        textColor: getEventTextColor(theme, MEAL_COLOR),
        extendedProps: { isMeal: true },
      }
    })
  }, [rawMeals, theme])

  // --- Filter by enabled calendars ---
  const filteredEvents = useMemo(() => {
    if (!enabledCalendars) return [...coloredEvents, ...taskEvents, ...mealEvents]
    const calFiltered = coloredEvents.filter((e) => enabledCalendars.has(e.calendarId))
    const tasksVisible = enabledCalendars.has('__tasks__')
    const mealsVisible = enabledCalendars.has('__meals__')
    return [
      ...calFiltered,
      ...(tasksVisible ? taskEvents : []),
      ...(mealsVisible ? mealEvents : []),
    ]
  }, [coloredEvents, enabledCalendars, taskEvents, mealEvents])

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

  const handleSelectAllow = useCallback((info: { start: Date; end: Date }) => {
    lastSelectionRef.current = { start: info.start, end: info.end }
    return true
  }, [])

  const handleSelect = useCallback((arg: { start: Date; end: Date; allDay: boolean }) => {
    selectFiredRef.current = true
    lastSelectionRef.current = null
    const { start } = arg
    let { end } = arg

    // Extend past calendar bottom into next day
    if (!arg.allDay) {
      const calEl = calendarBoxRef.current
      if (calEl) {
        const rect = calEl.getBoundingClientRect()
        if (lastMouseYRef.current > rect.bottom + 10) {
          const pixelsBelow = lastMouseYRef.current - rect.bottom
          const extraMinutes = Math.ceil(pixelsBelow / 28) * 30
          end = new Date(end.getTime() + extraMinutes * 60_000)
        }
      }
    }

    setDialogStart(start)
    setDialogEnd(end)
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
        rrule: fcEvent.extendedProps.rrule,
        masterEventId: fcEvent.extendedProps.masterEventId,
        isVirtualOccurrence: fcEvent.extendedProps.isVirtualOccurrence,
        calendarIri: fcEvent.extendedProps.calendarIri,
      })
      setPopoverAnchorEl(arg.el)
    },
    [calendarColorMap, calendarNameMap, theme.palette.primary.main],
  )

  const refreshEvents = useCallback(() => {
    if (dateRangeRef.current) {
      fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
    }
  }, [fetchEvents])

  const handleDeleteEvent = useCallback(
    (eventId: string) => {
      // Task deletion
      if (eventId.startsWith('task-')) {
        const taskId = eventId.replace('task-', '')
        setPopoverAnchorEl(null)
        setPopoverEvent(null)
        dataProvider
          .delete('tasks', { id: taskId, previousData: { id: taskId } })
          .then(() => {
            notify('Tâche supprimée', { type: 'success' })
            refreshEvents()
          })
          .catch((error: Error) => {
            notify(`Erreur: ${error.message}`, { type: 'error' })
          })
        return
      }

      // Virtual occurrence → open recurrence confirmation dialog
      if (eventId.includes('__')) {
        if (!popoverEvent) return
        const masterEventId = popoverEvent.masterEventId || eventId.split('__')[0]
        setPopoverAnchorEl(null)
        setPopoverEvent(null)
        setRecurrenceAction('this')
        setRecurrenceConfirm({
          type: 'delete',
          eventId,
          masterEventId,
          occurrenceStart: popoverEvent.start,
          calendarIri: popoverEvent.calendarIri || '',
          summary: popoverEvent.title,
          rrule: popoverEvent.rrule || '',
          allDay: popoverEvent.allDay,
          timeZone: 'Europe/Paris',
        })
        return
      }

      // Check if this is a master recurring event
      const rawEvent = rawEvents.find((e) => e.id === eventId)
      if (rawEvent?.rrule) {
        setPopoverAnchorEl(null)
        setPopoverEvent(null)
        setRecurrenceAction('all')
        setRecurrenceConfirm({
          type: 'delete',
          eventId,
          masterEventId: eventId,
          occurrenceStart: rawEvent.startAt,
          calendarIri: rawEvent.agenda,
          summary: rawEvent.summary,
          rrule: rawEvent.rrule,
          allDay: rawEvent.allDay,
          timeZone: 'Europe/Paris',
        })
        return
      }

      // Regular non-recurring event → delete directly
      setPopoverAnchorEl(null)
      setPopoverEvent(null)
      dataProvider
        .delete('events', { id: eventId, previousData: { id: eventId } })
        .then(() => {
          notify('Événement supprimé', { type: 'success' })
          refreshEvents()
        })
        .catch((error: Error) => {
          notify(`Erreur: ${error.message}`, { type: 'error' })
        })
    },
    [dataProvider, refreshEvents, notify, popoverEvent, rawEvents],
  )

  const handleEditEvent = useCallback(
    (eventId: string) => {
      const current = popoverEvent
      setPopoverAnchorEl(null)
      setPopoverEvent(null)

      // Task IDs are prefixed with "task-"
      if (eventId.startsWith('task-')) {
        const taskId = eventId.replace('task-', '')
        const task = rawTasks.find((t) => t.id === taskId)
        if (task) {
          setEditingTask(task)
        }
        return
      }

      if (!current || eventId.startsWith('meal-')) return

      const masterEventId = current.masterEventId || eventId.split('__')[0]
      const master = rawEvents.find((e) => e.id === masterEventId)
      setEditingEvent({
        eventId,
        masterEventId,
        occurrenceStart: current.start,
        calendarIri: current.calendarIri || master?.agenda || '',
        rrule: current.rrule || master?.rrule || '',
        // A modified occurrence is a real event of its own: edited like any other
        recurring: eventId.includes('__'),
        event: {
          summary: current.title,
          start: current.start,
          end: current.end,
          allDay: current.allDay,
          description: current.description,
          location: current.location,
        },
      })
    },
    [popoverEvent, rawTasks, rawEvents],
  )

  const handleEventEditSubmit = useCallback(
    (values: EventEditValues) => {
      if (!editingEvent) return
      const { eventId, masterEventId, occurrenceStart, calendarIri, rrule, recurring } = editingEvent
      setEditingEvent(null)

      if (recurring) {
        setRecurrenceAction('this')
        setRecurrenceConfirm({
          type: 'update',
          eventId,
          masterEventId,
          occurrenceStart,
          calendarIri,
          summary: values.summary,
          rrule,
          allDay: values.allDay,
          timeZone: 'Europe/Paris',
          newStart: values.startAt,
          newEnd: values.endAt,
          newAllDay: values.allDay,
          edit: { summary: values.summary, description: values.description, location: values.location },
        })
        return
      }

      dataProvider
        .update('events', {
          id: eventId,
          data: values,
          previousData: { id: eventId },
        })
        .then(() => {
          notify('Événement modifié', { type: 'success' })
          refreshEvents()
        })
        .catch((error: Error) => {
          notify(`Erreur: ${error.message}`, { type: 'error' })
        })
    },
    [editingEvent, dataProvider, notify, refreshEvents],
  )

  const handleEventUpdate = useCallback(
    (arg: EventDropArg | EventResizeDoneArg) => {
      const { event } = arg
      const isVirtual = event.extendedProps.isVirtualOccurrence

      if (isVirtual) {
        // Revert the visual drag, then open the confirmation dialog
        const newStart = event.start?.toISOString() || ''
        const newEnd = event.end?.toISOString() || newStart
        const newAllDay = event.allDay
        const oldStart = 'oldEvent' in arg ? (arg as EventDropArg).oldEvent.start?.toISOString() || '' : ''
        arg.revert()

        const masterEventId = event.extendedProps.masterEventId || event.id.split('__')[0]
        setRecurrenceAction('this')
        setRecurrenceConfirm({
          type: 'update',
          eventId: event.id,
          masterEventId,
          occurrenceStart: oldStart || newStart,
          calendarIri: event.extendedProps.calendarIri || '',
          summary: event.title,
          rrule: event.extendedProps.rrule || '',
          allDay: event.extendedProps.masterAllDay ?? event.allDay,
          timeZone: 'Europe/Paris',
          newStart,
          newEnd,
          newAllDay,
        })
        return
      }

      const startAt = event.start?.toISOString()
      const endAt = event.end?.toISOString() || startAt
      dataProvider
        .update('events', {
          id: event.id,
          data: { startAt, endAt, allDay: event.allDay },
          previousData: { id: event.id },
        })
        .then(() => {
          notify('Événement modifié', { type: 'success' })
        })
        .catch((error: Error) => {
          arg.revert()
          notify(`Erreur: ${error.message}`, { type: 'error' })
        })
    },
    [dataProvider, notify],
  )

  const handleRecurrenceConfirm = useCallback(async () => {
    if (!recurrenceConfirm) return
    const { type, masterEventId, occurrenceStart, calendarIri, summary, rrule, allDay, timeZone } = recurrenceConfirm
    const action = recurrenceAction

    try {
      if (type === 'delete') {
        if (action === 'this') {
          // Create cancelled exception
          await dataProvider.create('events', {
            data: {
              summary,
              startAt: occurrenceStart,
              endAt: occurrenceStart,
              allDay,
              timeZone,
              agenda: calendarIri,
              recurringEvent: eventIri(masterEventId),
              originalStartAt: occurrenceStart,
              status: 'cancelled',
            },
          })
          notify('Occurrence supprimée', { type: 'success' })
        } else if (action === 'thisAndFollowing') {
          // Truncate master rrule with UNTIL before this occurrence
          const newRrule = addUntilToRrule(rrule, new Date(occurrenceStart))
          await dataProvider.update('events', {
            id: masterEventId,
            data: { rrule: newRrule },
            previousData: { id: masterEventId },
          })
          notify('Occurrences futures supprimées', { type: 'success' })
        } else {
          // Delete master event (cascade deletes exceptions)
          await dataProvider.delete('events', {
            id: masterEventId,
            previousData: { id: masterEventId },
          })
          notify('Événement récurrent supprimé', { type: 'success' })
        }
      } else {
        // type === 'update'
        const { newStart, newEnd, newAllDay, edit } = recurrenceConfirm
        const editFields = edit ? { description: edit.description, location: edit.location } : {}

        if (action === 'this') {
          // Create exception instance with new times
          await dataProvider.create('events', {
            data: {
              summary,
              startAt: newStart,
              endAt: newEnd,
              allDay: newAllDay ?? allDay,
              timeZone,
              agenda: calendarIri,
              recurringEvent: eventIri(masterEventId),
              originalStartAt: occurrenceStart,
              status: 'confirmed',
              ...editFields,
            },
          })
          notify('Occurrence modifiée', { type: 'success' })
        } else if (action === 'thisAndFollowing') {
          // Truncate master rrule, then create a new recurring event from this point
          const newRrule = addUntilToRrule(rrule, new Date(occurrenceStart))
          await dataProvider.update('events', {
            id: masterEventId,
            data: { rrule: newRrule },
            previousData: { id: masterEventId },
          })
          // Create new recurring event starting at the new time
          await dataProvider.create('events', {
            data: {
              summary,
              startAt: newStart,
              endAt: newEnd,
              allDay: newAllDay ?? allDay,
              timeZone,
              agenda: calendarIri,
              rrule,
              ...editFields,
            },
          })
          notify('Série modifiée', { type: 'success' })
        } else {
          // Shift the master by the amount the occurrence moved, so every
          // occurrence moves with it (not just land on the edited one's date)
          const master = rawEvents.find((e) => e.id === masterEventId)
          let startAt = newStart
          let endAt = newEnd
          if (master && newStart && newEnd && occurrenceStart) {
            const shift = new Date(newStart).getTime() - new Date(occurrenceStart).getTime()
            const duration = new Date(newEnd).getTime() - new Date(newStart).getTime()
            const masterStart = new Date(master.startAt).getTime() + shift
            startAt = new Date(masterStart).toISOString()
            endAt = new Date(masterStart + duration).toISOString()
          }
          await dataProvider.update('events', {
            id: masterEventId,
            data: {
              startAt,
              endAt,
              allDay: newAllDay ?? allDay,
              ...(edit ? { summary: edit.summary, ...editFields } : {}),
            },
            previousData: { id: masterEventId },
          })
          notify('Événement récurrent modifié', { type: 'success' })
        }
      }

      refreshEvents()
      setRecurrenceConfirm(null)
    } catch (error) {
      notify(`Erreur: ${(error as Error).message}`, { type: 'error' })
    }
  }, [recurrenceConfirm, recurrenceAction, dataProvider, notify, refreshEvents, rawEvents])

  const handleCreated = useCallback(() => {
    if (dateRangeRef.current) {
      fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
    }
  }, [fetchEvents])

  // --- Mercure live updates ---
  // The update is published before the worker has indexed the row, and the list is served
  // from Elasticsearch: the first refetch can miss the change, so two more follow.
  const mercureRefetchTimers = useRef<ReturnType<typeof setTimeout>[]>([])
  const mercureCallback = useCallback(() => {
    const refetch = () => {
      if (dateRangeRef.current) fetchEvents(dateRangeRef.current.start, dateRangeRef.current.end)
    }
    refetch()
    mercureRefetchTimers.current.forEach(clearTimeout)
    mercureRefetchTimers.current = MERCURE_REFETCH_DELAYS_MS.map((delay) => setTimeout(refetch, delay))
  }, [fetchEvents])
  useEffect(
    () => () => {
      mercureRefetchTimers.current.forEach(clearTimeout)
    },
    [],
  )
  useMercure(CALENDAR_TOPICS, mercureCallback)

  // Another window or the mobile app can change which agenda is the default.
  const agendaRefetchTimers = useRef<ReturnType<typeof setTimeout>[]>([])
  const agendaMercureCallback = useCallback(() => {
    loadCalendars()
    agendaRefetchTimers.current.forEach(clearTimeout)
    agendaRefetchTimers.current = MERCURE_REFETCH_DELAYS_MS.map((delay) => setTimeout(loadCalendars, delay))
  }, [loadCalendars])
  useEffect(
    () => () => {
      agendaRefetchTimers.current.forEach(clearTimeout)
    },
    [],
  )
  useMercure(AGENDA_TOPICS, agendaMercureCallback)

  // --- Deep-link: open an event or a meal from search (?eventId=… / ?mealId=…) ---
  const deepLinkEventId = searchParams.get('eventId')
  const deepLinkMealId = searchParams.get('mealId')
  useEffect(() => {
    if (!deepLinkEventId && !deepLinkMealId) return

    // Clear the URL params so they don't re-trigger
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev)
      next.delete('eventId')
      next.delete('mealId')
      return next
    }, { replace: true })

    // Fetch the record and navigate the calendar to its date
    const request = deepLinkEventId
      ? dataProvider.getOne('events', { id: eventIri(deepLinkEventId) })
      : dataProvider.getOne('meals', { id: mealIri(deepLinkMealId as string) })

    request
      .then(({ data }) => {
        const api = calendarRef.current?.getApi()
        const startAt = (data as { startAt?: string }).startAt
        if (api && startAt) {
          api.gotoDate(startAt)
        }

        // Open the popover after a short delay to let the calendar render
        setTimeout(() => {
          if (deepLinkEventId) {
            const event = data as unknown as CalendarEvent
            const calId = typeof event.agenda === 'string' ? event.agenda : ''
            const color = calendarColorMap.get(calId) || theme.palette.primary.main
            setPopoverEvent({
              id: event.id,
              title: event.summary,
              start: event.startAt,
              end: event.endAt,
              allDay: event.allDay,
              color,
              calendarName: calendarNameMap.get(calId) || '',
              description: event.description,
              location: event.location,
              rrule: event.rrule,
              calendarIri: event.agenda,
            })
          } else {
            const meal = data as unknown as CalendarMeal
            setPopoverEvent({
              id: `meal-${meal.id}`,
              title: mealTitle(meal),
              start: meal.date,
              end: meal.date,
              allDay: true,
              color: MEAL_COLOR,
              calendarName: '',
            })
          }
          // Anchor to the calendar container
          setPopoverAnchorEl(calendarBoxRef.current)
        }, 300)
      })
      .catch(() => {
        notify(deepLinkEventId ? 'Événement introuvable' : 'Repas introuvable', { type: 'warning' })
      })
  }, [deepLinkEventId, deepLinkMealId]) // eslint-disable-line react-hooks/exhaustive-deps

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

  // --- Track mouse Y + cross-day drag extension ---
  // selectAllow continuously updates lastSelectionRef during drag.
  // If mouse is released outside the calendar (FullCalendar won't fire select),
  // we use the last known selection + mouse Y to compute the extended end time.
  useEffect(() => {
    const el = calendarBoxRef.current
    if (!el) return

    const onMouseMove = (e: MouseEvent) => { lastMouseYRef.current = e.clientY }

    const onMouseDown = (e: MouseEvent) => {
      const target = e.target as HTMLElement
      if (target.closest('.fc-timegrid-slots') || target.closest('.fc-timegrid-col-events') || target.closest('.fc-daygrid-body')) {
        selectFiredRef.current = false
      }
    }

    const onDocMouseUp = () => {
      const sel = lastSelectionRef.current
      if (!sel) return

      // Defer to let FullCalendar's select fire first (synchronous in same event loop)
      setTimeout(() => {
        if (selectFiredRef.current) {
          lastSelectionRef.current = null
          return
        }

        // FullCalendar didn't fire select — mouse was likely outside the grid
        const rect = el.getBoundingClientRect()
        if (lastMouseYRef.current > rect.bottom) {
          const pixelsBelow = lastMouseYRef.current - rect.bottom
          const extraMinutes = Math.ceil(pixelsBelow / 28) * 30 // 28px = 30min slot
          const end = new Date(sel.end.getTime() + extraMinutes * 60_000)
          setDialogStart(sel.start)
          setDialogEnd(end)
          setDialogAllDay(false)
          setDialogOpen(true)
        }
        lastSelectionRef.current = null
      }, 0)
    }

    document.addEventListener('mousemove', onMouseMove)
    el.addEventListener('mousedown', onMouseDown)
    document.addEventListener('mouseup', onDocMouseUp)
    return () => {
      document.removeEventListener('mousemove', onMouseMove)
      el.removeEventListener('mousedown', onMouseDown)
      document.removeEventListener('mouseup', onDocMouseUp)
    }
  }, [])

  // --- Navigation ---
  const handleToday = useCallback(() => calendarRef.current?.getApi().today(), [])
  const handlePrev = useCallback(() => calendarRef.current?.getApi().prev(), [])
  const handleNext = useCallback(() => calendarRef.current?.getApi().next(), [])

  const handleViewChange = useCallback((view: CalendarView) => {
    // A view picked by hand beats the saved one, even if the preferences land afterwards.
    preferencesApplied.current.view = true
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
          {/* + Créer (split button) */}
          <ButtonGroup
            variant="contained"
            ref={createMenuAnchorRef}
            sx={{ mb: 2.5, borderRadius: 6, boxShadow: 2 }}
          >
            <Button
              startIcon={<AddIcon />}
              onClick={() => {
                setDialogStart(undefined)
                setDialogEnd(undefined)
                setDialogAllDay(false)
                setDialogOpen(true)
              }}
              sx={{ textTransform: 'none', borderRadius: '24px 0 0 24px', px: 3 }}
            >
              Créer
            </Button>
            <Button
              size="small"
              onClick={() => setCreateMenuOpen((prev) => !prev)}
              sx={{ borderRadius: '0 24px 24px 0', px: 0.5, minWidth: 32 }}
              aria-label="Options de création"
            >
              <ArrowDropDownIcon />
            </Button>
          </ButtonGroup>
          <Popper
            open={createMenuOpen}
            anchorEl={createMenuAnchorRef.current}
            transition
            disablePortal
            placement="bottom-start"
            sx={{ zIndex: 1300 }}
          >
            {({ TransitionProps }) => (
              <Grow {...TransitionProps}>
                <Paper elevation={4}>
                  <ClickAwayListener onClickAway={() => setCreateMenuOpen(false)}>
                    <MenuList dense>
                      <MenuItem
                        onClick={() => {
                          setCreateMenuOpen(false)
                          setDialogStart(undefined)
                          setDialogEnd(undefined)
                          setDialogAllDay(false)
                          setDialogOpen(true)
                        }}
                      >
                        <ListItemIcon><EventIcon fontSize="small" /></ListItemIcon>
                        <ListItemText>Événement</ListItemText>
                      </MenuItem>
                      <MenuItem
                        onClick={() => {
                          setCreateMenuOpen(false)
                          setTaskDialogOpen(true)
                        }}
                      >
                        <ListItemIcon><ChecklistIcon fontSize="small" /></ListItemIcon>
                        <ListItemText>Tâche</ListItemText>
                      </MenuItem>
                    </MenuList>
                  </ClickAwayListener>
                </Paper>
              </Grow>
            )}
          </Popper>

          {/* Mini calendar */}
          <MiniCalendar
            viewDate={miniCalDate}
            activeStart={currentView === 'dayGridMonth' ? null : (activeRange?.start ?? null)}
            activeEnd={currentView === 'dayGridMonth' ? null : (activeRange?.end ?? null)}
            onRangeSelect={handleMiniRangeSelect}
            onMonthChange={setMiniCalDate}
          />

          {/* Calendar list */}
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
                data-testid="agenda-row"
                onClick={() => toggleCalendar(cal.id)}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  py: 0.25,
                  px: 0.5,
                  cursor: 'pointer',
                  borderRadius: 1,
                  '&:hover': { bgcolor: 'action.hover' },
                  '&:hover .agenda-menu-btn': { opacity: 1 },
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
                <Typography variant="body2" data-testid="agenda-name" sx={{ ml: 0.5, flex: 1 }}>
                  {cal.name}
                </Typography>
                {cal.default && (
                  // Named by attributes, not by an SVG <title>: that would put the words
                  // into the row's text, which the agenda journeys match on.
                  <Box
                    component="span"
                    role="img"
                    aria-label="Agenda par défaut"
                    title="Agenda par défaut"
                    data-testid="agenda-default-badge"
                    sx={{ display: 'inline-flex', mr: 0.25 }}
                  >
                    <StarIcon sx={{ fontSize: 14, color: 'warning.main' }} />
                  </Box>
                )}
                {cal.googleCalendarId && (
                  <SyncIcon
                    // A bare icon says nothing to a screen reader, and MUI only
                    // puts a `data-testid` on its icons outside production — so
                    // a journey running against the built bundle needs a handle
                    // of our own (MAG-148).
                    titleAccess="Synchronisé avec Google"
                    data-testid="agenda-sync-badge"
                    sx={{ fontSize: 14, color: 'text.secondary', mr: 0.25 }}
                  />
                )}
                <IconButton
                  className="agenda-menu-btn"
                  size="small"
                  aria-label={`Options de l'agenda ${cal.name}`}
                  onClick={(e) => handleAgendaMenuOpen(e, cal)}
                  sx={{ opacity: 0, p: 0.25 }}
                >
                  <MoreVertIcon sx={{ fontSize: 16 }} />
                </IconButton>
              </Box>
            ))}
            {/* Agenda context menu */}
            <Menu
              anchorEl={agendaMenuAnchor}
              open={Boolean(agendaMenuAnchor)}
              onClose={handleAgendaMenuClose}
              anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
              transformOrigin={{ vertical: 'top', horizontal: 'right' }}
            >
              {agendaMenuTarget && !agendaMenuTarget.default && (
                <MenuItem onClick={handleSetDefaultAgenda}>
                  <ListItemIcon><StarBorderIcon fontSize="small" /></ListItemIcon>
                  <ListItemText>Définir comme agenda par défaut</ListItemText>
                </MenuItem>
              )}
              {agendaMenuTarget && !agendaMenuTarget.googleCalendarId && (
                <MenuItem onClick={() => {
                  handleAgendaMenuClose()
                  handleExportToGoogle()
                }} disabled={exportLoading}>
                  <ListItemIcon><CloudUploadIcon fontSize="small" /></ListItemIcon>
                  <ListItemText>Exporter vers Google</ListItemText>
                </MenuItem>
              )}
              <MenuItem onClick={() => {
                const target = agendaMenuTarget
                handleAgendaMenuClose()
                if (target) setDeleteDialogAgenda(target)
              }}>
                <ListItemIcon><DeleteIcon fontSize="small" color="error" /></ListItemIcon>
                <ListItemText sx={{ color: 'error.main' }}>Supprimer</ListItemText>
              </MenuItem>
            </Menu>

            {/* + Ajouter button */}
            <Button
              size="small"
              startIcon={<AddIcon />}
              onClick={(e) => setAddMenuAnchor(e.currentTarget)}
              sx={{ mt: 1, ml: 0.5, textTransform: 'none' }}
            >
              Ajouter
            </Button>
            <Menu
              anchorEl={addMenuAnchor}
              open={Boolean(addMenuAnchor)}
              onClose={() => setAddMenuAnchor(null)}
            >
              <MenuItem onClick={() => {
                setAddMenuAnchor(null)
                setCreateAgendaDialogOpen(true)
              }}>
                <ListItemIcon><EventIcon fontSize="small" /></ListItemIcon>
                <ListItemText>Créer un agenda</ListItemText>
              </MenuItem>
              <MenuItem onClick={handleOpenImportDialog}>
                <ListItemIcon><SyncIcon fontSize="small" /></ListItemIcon>
                <ListItemText>Importer depuis Google</ListItemText>
              </MenuItem>
            </Menu>

            {/* Tasks pseudo-agenda */}
            <Typography
              variant="caption"
              fontWeight={500}
              color="text.secondary"
              sx={{ px: 0.5, mt: 2, mb: 0.5, display: 'block', letterSpacing: 0.5 }}
            >
              Autres agendas
            </Typography>
            <Box
              onClick={() => toggleCalendar('__meals__')}
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
                checked={enabledCalendars?.has('__meals__') ?? true}
                tabIndex={-1}
                disableRipple
                sx={{
                  p: 0.25,
                  color: '#FF6B35',
                  '&.Mui-checked': { color: '#FF6B35' },
                }}
              />
              <RestaurantIcon sx={{ fontSize: 16, color: 'text.secondary', ml: 0.5, mr: 0.5 }} />
              <Typography variant="body2">
                Repas
              </Typography>
            </Box>
            <Box
              onClick={() => toggleCalendar('__tasks__')}
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
                checked={enabledCalendars?.has('__tasks__') ?? true}
                tabIndex={-1}
                disableRipple
                sx={{
                  p: 0.25,
                  color: '#1976d2',
                  '&.Mui-checked': { color: '#1976d2' },
                }}
              />
              <ChecklistIcon sx={{ fontSize: 16, color: 'text.secondary', ml: 0.5, mr: 0.5 }} />
              <Typography variant="body2">
                Tâches
              </Typography>
            </Box>
          </Box>
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
            selectMirror={true}
            selectAllow={handleSelectAllow}
            select={handleSelect}
            eventClick={handleEventClick}
            editable={true}
            eventDrop={handleEventUpdate}
            eventResize={handleEventUpdate}
            headerToolbar={false}
            height="100%"
            dayMaxEvents={3}
            moreLinkText={(n) => `+${n} autre${n > 1 ? 's' : ''}`}
            eventTimeFormat={{ hour: '2-digit', minute: '2-digit', hour12: false }}
            fixedWeekCount={false}
            nowIndicator={true}
            scrollTime="07:00:00"
            slotMaxTime="24:00:00"
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

      <TaskCreateDialog
        open={taskDialogOpen}
        onClose={() => setTaskDialogOpen(false)}
        onCreated={handleCreated}
        defaultDueDate={dialogStart}
      />

      <EventDetailPopover
        event={popoverEvent}
        anchorEl={popoverAnchorEl}
        onClose={() => {
          setPopoverAnchorEl(null)
          setPopoverEvent(null)
        }}
        onEdit={handleEditEvent}
        onDelete={handleDeleteEvent}
      />

      <EventEditDialog
        open={editingEvent !== null}
        event={editingEvent?.event ?? null}
        onClose={() => setEditingEvent(null)}
        onSubmit={handleEventEditSubmit}
      />

      <TaskEditDialog
        open={editingTask !== null}
        task={editingTask}
        onClose={() => setEditingTask(null)}
        onUpdated={handleCreated}
      />

      {/* Recurrence confirmation dialog */}
      <Dialog
        open={recurrenceConfirm !== null}
        onClose={() => setRecurrenceConfirm(null)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>
          {recurrenceConfirm?.type === 'delete'
            ? "Supprimer l'événement récurrent"
            : "Modifier l'événement récurrent"}
        </DialogTitle>
        <DialogContent>
          <RadioGroup
            value={recurrenceAction}
            onChange={(e) => setRecurrenceAction(e.target.value as RecurrenceAction)}
          >
            <FormControlLabel value="this" control={<Radio />} label="Cet événement" />
            <FormControlLabel value="thisAndFollowing" control={<Radio />} label="Cet événement et tous les suivants" />
            <FormControlLabel value="all" control={<Radio />} label="Tous les événements" />
          </RadioGroup>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRecurrenceConfirm(null)}>Annuler</Button>
          <Button onClick={handleRecurrenceConfirm} variant="contained">
            OK
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete agenda dialog */}
      <Dialog
        open={deleteDialogAgenda !== null}
        onClose={() => !deleteLoading && setDeleteDialogAgenda(null)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>Supprimer l&apos;agenda</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {deleteDialogAgenda?.googleCalendarId
              ? `L'agenda "${deleteDialogAgenda?.name}" est synchronisé avec Google Calendar. Que souhaitez-vous faire ?`
              : `Supprimer l'agenda "${deleteDialogAgenda?.name}" et tous ses événements ?`}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteDialogAgenda(null)} disabled={deleteLoading}>
            Annuler
          </Button>
          {deleteDialogAgenda?.googleCalendarId ? (
            <>
              <Button
                onClick={() => handleDeleteAgenda(false)}
                disabled={deleteLoading}
                color="error"
              >
                {deleteLoading ? <CircularProgress size={20} /> : 'Supprimer uniquement Maggie'}
              </Button>
              <Button
                onClick={() => handleDeleteAgenda(true)}
                disabled={deleteLoading}
                color="error"
                variant="contained"
              >
                {deleteLoading ? <CircularProgress size={20} /> : 'Supprimer aussi sur Google'}
              </Button>
            </>
          ) : (
            <Button
              onClick={() => handleDeleteAgenda(false)}
              disabled={deleteLoading}
              color="error"
              variant="contained"
            >
              {deleteLoading ? <CircularProgress size={20} /> : 'Supprimer'}
            </Button>
          )}
        </DialogActions>
      </Dialog>

      {/* Create agenda dialog */}
      <Dialog
        open={createAgendaDialogOpen}
        onClose={() => !createAgendaLoading && setCreateAgendaDialogOpen(false)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>Créer un agenda</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            label="Nom"
            fullWidth
            value={newAgendaName}
            onChange={(e) => setNewAgendaName(e.target.value)}
            sx={{ mt: 1 }}
            onKeyDown={(e) => { if (e.key === 'Enter') handleCreateAgenda() }}
          />
          <Box sx={{ mt: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="body2">Couleur :</Typography>
            <input
              type="color"
              value={newAgendaColor}
              onChange={(e) => setNewAgendaColor(e.target.value)}
              style={{ width: 36, height: 28, border: 'none', cursor: 'pointer', background: 'transparent' }}
            />
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCreateAgendaDialogOpen(false)} disabled={createAgendaLoading}>
            Annuler
          </Button>
          <Button
            onClick={handleCreateAgenda}
            variant="contained"
            disabled={createAgendaLoading || !newAgendaName.trim()}
          >
            {createAgendaLoading ? <CircularProgress size={20} /> : 'Créer'}
          </Button>
        </DialogActions>
      </Dialog>

      {/* Import from Google dialog */}
      <Dialog
        open={importDialogOpen}
        onClose={() => !importLoading && setImportDialogOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Importer depuis Google Calendar</DialogTitle>
        <DialogContent>
          {importLoading && googleCalendars.length === 0 ? (
            <Box sx={{ display: 'flex', justifyContent: 'center', py: 3 }}>
              <CircularProgress />
            </Box>
          ) : (
            (() => {
              const connectedIds = new Set(calendars.filter((c) => c.googleCalendarId).map((c) => c.googleCalendarId))
              const available = googleCalendars.filter((gc) => !connectedIds.has(gc.id))
              if (available.length === 0) {
                return (
                  <Typography variant="body2" color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>
                    Tous les calendriers Google sont déjà importés.
                  </Typography>
                )
              }
              return available.map((gc) => (
                <Box
                  key={gc.id}
                  sx={{
                    display: 'flex',
                    alignItems: 'center',
                    py: 1,
                    borderBottom: 1,
                    borderColor: 'divider',
                    '&:last-child': { borderBottom: 0 },
                  }}
                >
                  {gc.backgroundColor && (
                    <Box
                      sx={{
                        width: 12,
                        height: 12,
                        borderRadius: '50%',
                        bgcolor: gc.backgroundColor,
                        flexShrink: 0,
                        mr: 1,
                      }}
                    />
                  )}
                  <Typography variant="body2" sx={{ flex: 1 }}>
                    {gc.name}
                    {gc.primary && ' (principal)'}
                  </Typography>
                  <Button
                    size="small"
                    onClick={() => handleImportCalendar(gc.id)}
                    disabled={importLoading}
                  >
                    {importLoading ? <CircularProgress size={16} /> : 'Importer'}
                  </Button>
                </Box>
              ))
            })()
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setImportDialogOpen(false)} disabled={importLoading}>
            Fermer
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import { useMercure } from '../../hooks/useMercure'
import Typography from '@mui/material/Typography'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import DateRangeIcon from '@mui/icons-material/DateRange'
import { DailyDigestRow } from './DailyDigestRow'
import { DigestSection } from './DigestSection'
import { getThisMonth, getThisWeek, getToday, getTomorrow } from './dateUtils'
import { addDays, daysBetween, localDay, parseDay } from '../../dates'
import { expandRrule, expandRruleDays } from '../calendar/recurrenceUtils'
import type { DashboardEvent } from './EventListWidget'
import type { DashboardTask } from './TaskListWidget'

const DASHBOARD_TOPICS = ['/api/events/{id}', '/api/tasks/{id}']

/** The timing fields of a row, as the widget reads them. */
const timingOf = (e: RawEvent): Pick<DashboardEvent, 'startAt' | 'endAt' | 'startDate' | 'endDate' | 'allDay'> =>
  e.allDay
    ? { allDay: true, startAt: null, endAt: null, startDate: e.startDate ?? null, endDate: e.endDate ?? e.startDate ?? null }
    : { allDay: false, startAt: e.startAt, endAt: e.endAt, startDate: null, endDate: null }

/**
 * When an event begins, to place it in a section: a timed one at its instant, an
 * all-day one at the local midnight of its first date — a date has no instant of
 * its own, and its UTC midnight would be the eve in any zone west of Greenwich.
 */
const beginningOf = (e: Pick<DashboardEvent, 'allDay' | 'startAt' | 'startDate'>): Date =>
  e.allDay && e.startDate ? parseDay(e.startDate) : new Date(e.startAt ?? '')

interface RawEvent {
  id: string
  summary: string
  description?: string
  location?: string
  startAt: string | null
  endAt: string | null
  /** `YYYY-MM-DD` on an all-day event, the end included (MAG-382). */
  startDate?: string | null
  endDate?: string | null
  allDay: boolean
  agenda: string
  timeZone?: string
  rrule?: string
  recurringEvent?: string
  originalStartAt?: string
  status?: string
}

interface RawTask {
  id: string
  title: string
  description?: string
  priority: string
  criticality: string
  dueDate?: string
  completedAt?: string
}

export const Dashboard = () => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [rawEvents, setRawEvents] = useState<RawEvent[]>([])
  const [rawTasks, setRawTasks] = useState<RawTask[]>([])
  const [undueTasks, setUndueTasks] = useState<RawTask[]>([])
  const [loading, setLoading] = useState(true)

  const ranges = useMemo(
    () => ({
      today: getToday(),
      tomorrow: getTomorrow(),
      week: getThisWeek(),
      month: getThisMonth(),
    }),
    [],
  )

  const fetchData = useCallback(() => {
    setLoading(true)
    const { month } = ranges

    Promise.all([
      dataProvider.getList('events', {
        pagination: { page: 1, perPage: 200 },
        sort: { field: 'startAt', order: 'ASC' },
        filter: { 'startAt[after]': month.start, 'startAt[before]': month.end },
      }),
      dataProvider.getList('events', {
        pagination: { page: 1, perPage: 200 },
        sort: { field: 'startAt', order: 'ASC' },
        filter: { 'exists[rrule]': true, 'startAt[strictly_before]': month.start },
      }),
      dataProvider.getList('tasks', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'dueDate', order: 'ASC' },
        filter: { 'exists[completedAt]': false, 'dueDate[before]': month.end },
      }),
      dataProvider.getList('tasks', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'criticality', order: 'DESC' },
        filter: { 'exists[completedAt]': false, 'exists[dueDate]': false },
      }),
    ])
      .then(([rangeEvents, recurringEvents, rangeTasks, undueTasksResult]) => {
        const seen = new Set<string>()
        const merged: RawEvent[] = []
        for (const e of [...rangeEvents.data, ...recurringEvents.data] as unknown as RawEvent[]) {
          if (!seen.has(e.id)) {
            seen.add(e.id)
            merged.push(e)
          }
        }
        setRawEvents(merged)
        setRawTasks(rangeTasks.data as unknown as RawTask[])
        setUndueTasks(undueTasksResult.data as unknown as RawTask[])
      })
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [dataProvider, ranges])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // Mercure live updates
  useMercure(DASHBOARD_TOPICS, fetchData)

  // Expand events for a range with RRULE expansion (daily & weekly)
  const expandEventsForRange = useCallback(
    (rangeStart: string, rangeEnd: string): DashboardEvent[] => {
      const start = new Date(rangeStart)
      const end = new Date(rangeEnd)
      const result: DashboardEvent[] = []

      // Build exception map: masterIRI → originalStartAt(epoch ms) → exception event
      const exceptionMap = new Map<string, Map<number, RawEvent>>()
      for (const e of rawEvents) {
        if (e.recurringEvent && e.originalStartAt) {
          const key = e.recurringEvent
          if (!exceptionMap.has(key)) exceptionMap.set(key, new Map())
          exceptionMap.get(key)!.set(new Date(e.originalStartAt).getTime(), e)
        }
      }

      for (const e of rawEvents) {
        // Skip exception instances — handled during master expansion
        if (e.recurringEvent) continue

        if (e.rrule && e.allDay && e.startDate) {
          // An all-day series is expanded on its dates (MAG-382); an occurrence is
          // keyed, against its exceptions, by its date at midnight UTC.
          const length = daysBetween(e.startDate, e.endDate ?? e.startDate)
          const exceptions = exceptionMap.get(`/api/events/${e.id}`)
          for (const day of expandRruleDays(e.rrule, e.startDate, 0, localDay(start), localDay(end))) {
            const exception = exceptions?.get(new Date(`${day}T00:00:00Z`).getTime())
            if (exception) {
              if (exception.status === 'cancelled') continue
              result.push({ id: exception.id, summary: exception.summary, ...timingOf(exception), location: exception.location })
            } else {
              result.push({
                id: `${e.id}__${day}`,
                summary: e.summary,
                allDay: true,
                startAt: null,
                endAt: null,
                startDate: day,
                endDate: addDays(day, length),
                location: e.location,
              })
            }
          }
        } else if (e.rrule) {
          const dtstart = new Date(e.startAt ?? '')
          const duration = new Date(e.endAt ?? '').getTime() - dtstart.getTime()
          const occurrences = expandRrule(e.rrule, dtstart, start, end, e.timeZone)
          const eventIri = `/api/events/${e.id}`
          const exceptions = exceptionMap.get(eventIri)

          for (const occ of occurrences) {
            const occTime = occ.getTime()
            const exception = exceptions?.get(occTime)

            if (exception) {
              if (exception.status === 'cancelled') continue
              result.push({
                id: exception.id,
                summary: exception.summary,
                ...timingOf(exception),
                location: exception.location,
              })
            } else {
              const occEnd = new Date(occTime + duration)
              result.push({
                id: `${e.id}__${occ.toISOString().slice(0, 10)}`,
                summary: e.summary,
                startAt: occ.toISOString(),
                endAt: occEnd.toISOString(),
                startDate: null,
                endDate: null,
                allDay: false,
                location: e.location,
              })
            }
          }
        } else {
          // Non-recurring: include if it begins within range
          const eventStart = beginningOf({ allDay: e.allDay, startAt: e.startAt, startDate: e.startDate ?? null })
          if (eventStart >= start && eventStart < end) {
            result.push({
              id: e.id,
              summary: e.summary,
              ...timingOf(e),
              location: e.location,
            })
          }
        }
      }

      result.sort((a, b) => {
        if (a.allDay !== b.allDay) return a.allDay ? -1 : 1
        return a.allDay
          ? (a.startDate ?? '').localeCompare(b.startDate ?? '')
          : (a.startAt ?? '').localeCompare(b.startAt ?? '')
      })

      return result
    },
    [rawEvents],
  )

  // Check if a date falls within a range
  const isInRange = (e: DashboardEvent, rangeStart: string, rangeEnd: string): boolean => {
    const d = beginningOf(e).getTime()
    return d >= new Date(rangeStart).getTime() && d < new Date(rangeEnd).getTime()
  }

  // Filter tasks for a date range (excludes done tasks)
  const getTasksForRange = useCallback(
    (rangeStart: string, rangeEnd: string): DashboardTask[] => {
      const start = new Date(rangeStart)
      const end = new Date(rangeEnd)
      return rawTasks
        .filter((t) => {
          if (t.completedAt) return false
          if (!t.dueDate) return false
          const due = new Date(t.dueDate)
          return due >= start && due < end
        })
        .map((t) => ({
          id: t.id,
          title: t.title,
          criticality: t.criticality,
          dueDate: t.dueDate,
          completedAt: t.completedAt,
        }))
    },
    [rawTasks],
  )

  // Computed section data
  const todayEvents = useMemo(
    () => expandEventsForRange(ranges.today.start, ranges.today.end),
    [expandEventsForRange, ranges],
  )
  const tomorrowEvents = useMemo(
    () => expandEventsForRange(ranges.tomorrow.start, ranges.tomorrow.end),
    [expandEventsForRange, ranges],
  )
  const weekEvents = useMemo(() => {
    const all = expandEventsForRange(ranges.week.start, ranges.week.end)
    return all.filter(
      (e) =>
        !isInRange(e, ranges.today.start, ranges.today.end) &&
        !isInRange(e, ranges.tomorrow.start, ranges.tomorrow.end),
    )
  }, [expandEventsForRange, ranges])
  const monthEvents = useMemo(() => {
    const all = expandEventsForRange(ranges.month.start, ranges.month.end)
    return all.filter(
      (e) =>
        !isInRange(e, ranges.week.start, ranges.week.end) &&
        !isInRange(e, ranges.tomorrow.start, ranges.tomorrow.end),
    )
  }, [expandEventsForRange, ranges])

  // Today: due today or late (overdue) + undone tasks without due date
  const todayTasks = useMemo(() => {
    const todayEnd = new Date(ranges.today.end)
    const overdueAndToday: DashboardTask[] = rawTasks
      .filter((t) => {
        if (t.completedAt) return false
        if (!t.dueDate) return false
        return new Date(t.dueDate) < todayEnd
      })
      .map((t) => ({
        id: t.id,
        title: t.title,
        criticality: t.criticality,
        dueDate: t.dueDate,
        completedAt: t.completedAt,
      }))
    const undue: DashboardTask[] = undueTasks
      .filter((t) => !t.dueDate)
      .map((t) => ({
        id: t.id,
        title: t.title,
        criticality: t.criticality,
        dueDate: t.dueDate,
        completedAt: t.completedAt,
      }))
    return [...overdueAndToday, ...undue]
  }, [rawTasks, ranges, undueTasks])
  // Tomorrow: only tasks due tomorrow
  const tomorrowTasks = useMemo(
    () => getTasksForRange(ranges.tomorrow.start, ranges.tomorrow.end),
    [getTasksForRange, ranges],
  )
  // Week: due in [day after tomorrow, today+7)
  const weekTasks = useMemo(
    () => getTasksForRange(ranges.tomorrow.end, ranges.week.end),
    [getTasksForRange, ranges],
  )
  // Month: due in [today+7, same day next month)
  const monthTasks = useMemo(
    () => getTasksForRange(ranges.week.end, ranges.month.end),
    [getTasksForRange, ranges],
  )

  // Only show loading skeletons on initial load, not on Mercure background refreshes
  const initialLoading = loading && rawEvents.length === 0 && rawTasks.length === 0 && undueTasks.length === 0

  // Toggle task done/undone
  const handleToggleDone = useCallback(
    (taskId: string, done: boolean) => {
      const now = new Date().toISOString()
      dataProvider
        .update('tasks', {
          id: taskId,
          data: { completedAt: done ? now : null },
          previousData: { id: taskId },
        })
        .then(() => {
          notify(done ? 'Tâche terminée' : 'Tâche rouverte', { type: 'success' })
          fetchData()
        })
        .catch((error: Error) => {
          notify(`Erreur: ${error.message}`, { type: 'error' })
        })
    },
    [dataProvider, notify, fetchData],
  )

  return (
    <Box sx={{ p: 3, maxWidth: 1200, mx: 'auto' }}>
      <Typography variant="h5" sx={{ mb: 3 }}>
        Tableau de bord
      </Typography>

      <DailyDigestRow
        todayEvents={todayEvents}
        tomorrowEvents={tomorrowEvents}
        todayTasks={todayTasks}
        tomorrowTasks={tomorrowTasks}
        loading={initialLoading}
        onToggleDone={handleToggleDone}
      />

      <Box sx={{ mt: 4 }}>
        <DigestSection
          title="Cette semaine"
          icon={<DateRangeIcon color="action" />}
          events={weekEvents}
          tasks={weekTasks}
          loading={initialLoading}
          onToggleDone={handleToggleDone}
        />
      </Box>

      <Box sx={{ mt: 4 }}>
        <DigestSection
          title="Ce mois"
          icon={<CalendarMonthIcon color="action" />}
          events={monthEvents}
          tasks={monthTasks}
          loading={initialLoading}
          onToggleDone={handleToggleDone}
        />
      </Box>
    </Box>
  )
}

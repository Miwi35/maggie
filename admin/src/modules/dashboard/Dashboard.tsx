import { useCallback, useEffect, useMemo, useState } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import DateRangeIcon from '@mui/icons-material/DateRange'
import { DailyDigestRow } from './DailyDigestRow'
import { DigestSection } from './DigestSection'
import { getThisMonth, getThisWeek, getToday, getTomorrow } from './dateUtils'
import { expandRrule } from '../calendar/recurrenceUtils'
import type { DashboardEvent } from './EventListWidget'
import type { DashboardTask } from './TaskListWidget'

interface RawEvent {
  id: string
  summary: string
  description?: string
  location?: string
  startAt: string
  endAt: string
  allDay: boolean
  agenda: string
  rrule?: string
  recurringEvent?: string
  originalStartAt?: string
  status?: string
}

interface RawTask {
  id: string
  name: string
  description?: string
  priority: string
  criticality: string
  dueDate?: string
  doneDate?: string
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
        filter: { 'exists[doneDate]': false, 'dueDate[before]': month.end },
      }),
      dataProvider.getList('tasks', {
        pagination: { page: 1, perPage: 50 },
        sort: { field: 'criticality', order: 'DESC' },
        filter: { 'exists[doneDate]': false, 'exists[dueDate]': false },
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
  useEffect(() => {
    const mercureUrl =
      import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'
    const url = new URL(mercureUrl)
    url.searchParams.append('topic', '/api/events/{id}')
    url.searchParams.append('topic', '/api/tasks/{id}')
    const es = new EventSource(url.toString())
    es.onmessage = () => fetchData()
    return () => es.close()
  }, [fetchData])

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

        if (e.rrule) {
          const dtstart = new Date(e.startAt)
          const duration = new Date(e.endAt).getTime() - dtstart.getTime()
          const occurrences = expandRrule(e.rrule, dtstart, start, end)
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
                startAt: exception.startAt,
                endAt: exception.endAt,
                allDay: exception.allDay,
                location: exception.location,
              })
            } else {
              const occEnd = new Date(occTime + duration)
              result.push({
                id: `${e.id}__${occ.toISOString().slice(0, 10)}`,
                summary: e.summary,
                startAt: occ.toISOString(),
                endAt: occEnd.toISOString(),
                allDay: e.allDay,
                location: e.location,
              })
            }
          }
        } else {
          // Non-recurring: include if startAt falls within range
          const eventStart = new Date(e.startAt)
          if (eventStart >= start && eventStart < end) {
            result.push({
              id: e.id,
              summary: e.summary,
              startAt: e.startAt,
              endAt: e.endAt,
              allDay: e.allDay,
              location: e.location,
            })
          }
        }
      }

      result.sort((a, b) => {
        if (a.allDay !== b.allDay) return a.allDay ? -1 : 1
        return a.startAt.localeCompare(b.startAt)
      })

      return result
    },
    [rawEvents],
  )

  // Monthly events: no RRULE expansion, just raw events in range
  const getMonthlyEvents = useCallback(
    (rangeStart: string, rangeEnd: string): DashboardEvent[] => {
      const start = new Date(rangeStart)
      const end = new Date(rangeEnd)
      return rawEvents
        .filter((e) => {
          if (e.recurringEvent) return false
          const eventStart = new Date(e.startAt)
          return eventStart >= start && eventStart < end
        })
        .map((e) => ({
          id: e.id,
          summary: e.summary,
          startAt: e.startAt,
          endAt: e.endAt,
          allDay: e.allDay,
          location: e.location,
        }))
        .sort((a, b) => {
          if (a.allDay !== b.allDay) return a.allDay ? -1 : 1
          return a.startAt.localeCompare(b.startAt)
        })
    },
    [rawEvents],
  )

  // Check if a date falls within a range
  const isInRange = (dateStr: string, rangeStart: string, rangeEnd: string): boolean => {
    const d = new Date(dateStr).getTime()
    return d >= new Date(rangeStart).getTime() && d < new Date(rangeEnd).getTime()
  }

  // Filter tasks for a date range (excludes done tasks)
  const getTasksForRange = useCallback(
    (rangeStart: string, rangeEnd: string): DashboardTask[] => {
      const start = new Date(rangeStart)
      const end = new Date(rangeEnd)
      return rawTasks
        .filter((t) => {
          if (t.doneDate) return false
          if (!t.dueDate) return false
          const due = new Date(t.dueDate)
          return due >= start && due < end
        })
        .map((t) => ({
          id: t.id,
          name: t.name,
          criticality: t.criticality,
          dueDate: t.dueDate,
          doneDate: t.doneDate,
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
        !isInRange(e.startAt, ranges.today.start, ranges.today.end) &&
        !isInRange(e.startAt, ranges.tomorrow.start, ranges.tomorrow.end),
    )
  }, [expandEventsForRange, ranges])
  const monthEvents = useMemo(() => {
    const all = getMonthlyEvents(ranges.month.start, ranges.month.end)
    return all.filter(
      (e) =>
        !isInRange(e.startAt, ranges.week.start, ranges.week.end) &&
        !isInRange(e.startAt, ranges.tomorrow.start, ranges.tomorrow.end),
    )
  }, [getMonthlyEvents, ranges])

  // Today: due today or late (overdue) + undone tasks without due date
  const todayTasks = useMemo(() => {
    const todayEnd = new Date(ranges.today.end)
    const overdueAndToday: DashboardTask[] = rawTasks
      .filter((t) => {
        if (t.doneDate) return false
        if (!t.dueDate) return false
        return new Date(t.dueDate) < todayEnd
      })
      .map((t) => ({
        id: t.id,
        name: t.name,
        criticality: t.criticality,
        dueDate: t.dueDate,
        doneDate: t.doneDate,
      }))
    const undue: DashboardTask[] = undueTasks
      .filter((t) => !t.dueDate)
      .map((t) => ({
        id: t.id,
        name: t.name,
        criticality: t.criticality,
        dueDate: t.dueDate,
        doneDate: t.doneDate,
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

  // Toggle task done/undone
  const handleToggleDone = useCallback(
    (taskId: string, done: boolean) => {
      const now = new Date().toISOString()
      dataProvider
        .update('tasks', {
          id: taskId,
          data: { doneDate: done ? now : null },
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
        loading={loading}
        onToggleDone={handleToggleDone}
      />

      <Box sx={{ mt: 4 }}>
        <DigestSection
          title="Cette semaine"
          icon={<DateRangeIcon color="action" />}
          events={weekEvents}
          tasks={weekTasks}
          loading={loading}
          onToggleDone={handleToggleDone}
        />
      </Box>

      <Box sx={{ mt: 4 }}>
        <DigestSection
          title="Ce mois"
          icon={<CalendarMonthIcon color="action" />}
          events={monthEvents}
          tasks={monthTasks}
          loading={loading}
          onToggleDone={handleToggleDone}
        />
      </Box>
    </Box>
  )
}

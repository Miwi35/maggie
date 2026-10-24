import { describe, expect, test } from 'vitest'
import { composeTasksState, localMidnightIso } from './tasksCardState'
import type { DayMoment } from '../../calendar/routinesApi'
import type { ViewTask } from '../../calendar/taskViews'

const task = (id: string, extra: Partial<ViewTask> = {}) =>
  ({ id, title: `Tâche ${id}`, priority: 'normal', criticality: 'normal', ...extra }) as ViewTask

const tasks = (n: number, prefix = 'O') => Array.from({ length: n }, (_, i) => task(`${prefix}${i}`))

const moment = (time: string, ...titles: string[]): DayMoment => ({
  anchor: 'time',
  time,
  routines: titles.map((title) => ({
    id: `R-${title}`,
    title,
    anchor: 'time',
    anchorTime: time,
    time,
    remind: false,
    recurrence: { kind: 'daily' },
    checked: title === 'Cochée',
  })),
})

const none = { date: 'd', moments: [] }

describe('composeTasksState — today', () => {
  test('lists the open tasks then the done ones, the ring counting both', () => {
    const state = composeTasksState('today', { open: tasks(2), done: [task('D0'), task('D1')], routines: none }, '10:00')

    expect(state.tasks.map((t) => [t.id, t.done])).toEqual([['O0', false], ['O1', false], ['D0', true], ['D1', true]])
    expect(state).toMatchObject({ module: 'tasks', day: 'today', editable: true, done: 2, total: 4, more: 0, saga: null })
  })

  test('caps the lines at five, the done ones claiming their place first', () => {
    const state = composeTasksState('today', { open: tasks(8), done: tasks(7, 'D'), routines: none }, '10:00')

    expect(state.tasks).toHaveLength(5)
    expect(state.tasks.filter((t) => t.done).map((t) => t.id)).toEqual(['D0', 'D1', 'D2', 'D3', 'D4'])
    expect(state).toMatchObject({ done: 5, total: 5, more: 8 })
  })

  test('counts what the card leaves out as « more »', () => {
    const state = composeTasksState('today', { open: tasks(7), done: [task('D0')], routines: none }, '10:00')

    expect(state.tasks).toHaveLength(5)
    expect(state).toMatchObject({ done: 1, total: 5, more: 3 })
  })

  test('counts every late open task, listed or not, and drops the lateness of a done one', () => {
    const open = [...tasks(5).map((t) => ({ ...t, overdueDays: 1 })), task('L', { overdueDays: 3 }), task('N', { overdueDays: 0 })]
    const state = composeTasksState('today', { open, done: [task('D0', { overdueDays: 4 })], routines: none }, '10:00')

    expect(state.overdue).toBe(6)
    expect(state.tasks.find((t) => t.id === 'D0')?.overdueDays).toBeNull()
    expect(state.tasks.find((t) => t.id === 'O0')?.overdueDays).toBe(1)
  })

  test('reads the parent of a subtask from its IRI', () => {
    const state = composeTasksState('today', { open: [task('A'), task('B', { parent: '/api/tasks/A' })], done: [task('C', { parent: '/api/tasks/A' })], routines: none }, '10:00')

    expect(state.tasks.map((t) => t.parentId)).toEqual([null, 'A', 'A'])
  })

  test('keeps the order of the view', () => {
    const state = composeTasksState('today', { open: [task('Z', { orderScore: 0 }), task('A', { orderScore: 9 })], done: [], routines: none }, '10:00')

    expect(state.tasks.map((t) => t.id)).toEqual(['Z', 'A'])
  })

  describe('routines', () => {
    const day = { date: 'd', moments: [moment('07:00', 'Réveil'), moment('12:30', 'Midi', 'Cochée'), moment('21:00', 'Soir')] }
    const titles = (now: string) => composeTasksState('today', { open: [], done: [], routines: day }, now).routines.map((r) => r.title)

    test('shows the moment whose hour has passed last', () => {
      expect(titles('13:00')).toEqual(['Midi', 'Cochée'])
      expect(titles('12:30')).toEqual(['Midi', 'Cochée'])
      expect(titles('23:59')).toEqual(['Soir'])
    })

    test('shows the first moment before the day starts', () => {
      expect(titles('05:00')).toEqual(['Réveil'])
    })

    test('maps a routine to a row', () => {
      const state = composeTasksState('today', { open: [], done: [], routines: day }, '13:00')

      expect(state.routines[1]).toEqual({ id: 'R-Cochée', title: 'Cochée', anchor: 'time', time: '12:30', checked: true })
    })

    test('has none without a moment', () => {
      expect(composeTasksState('today', { open: [], done: [], routines: none }, '13:00').routines).toEqual([])
    })
  })
})

describe('composeTasksState — tomorrow', () => {
  test('is read only, with no done line and no ring', () => {
    const state = composeTasksState('tomorrow', { open: tasks(2), done: [task('D0')], routines: none }, '10:00')

    expect(state).toMatchObject({ day: 'tomorrow', editable: false, done: 0, total: 2, more: 0 })
    expect(state.tasks.every((t) => !t.done)).toBe(true)
  })

  test('sorts by urgency, then the dated before the undated', () => {
    const open = [
      task('C', { orderScore: 5, dueDate: '2026-10-25' }),
      task('B', { orderScore: 5, dueDate: '2026-10-24' }),
      task('A', { orderScore: 9 }),
      task('G', { orderScore: 5 }),
    ]
    const state = composeTasksState('tomorrow', { open, done: [], routines: none }, '10:00')

    expect(state.tasks.map((t) => t.id)).toEqual(['A', 'B', 'C', 'G'])
  })

  test('orders equals by position, then by id', () => {
    const open = [task('E', { position: 'b' }), task('D', { position: 'a' }), task('F', { position: 'a' }), task('A')]
    const state = composeTasksState('tomorrow', { open, done: [], routines: none }, '10:00')

    expect(state.tasks.map((t) => t.id)).toEqual(['A', 'D', 'F', 'E'])
  })

  test('caps at five lines and counts the rest and the late ones', () => {
    const open = [...tasks(6), task('L', { overdueDays: 2 })]
    const state = composeTasksState('tomorrow', { open, done: [], routines: none }, '10:00')

    expect(state.tasks).toHaveLength(5)
    expect(state).toMatchObject({ total: 5, more: 2, overdue: 1 })
  })

  test('lists the routines of every moment', () => {
    const routines = { date: 'd', moments: [moment('07:00', 'A'), moment('21:00', 'B', 'C')] }

    expect(composeTasksState('tomorrow', { open: [], done: [], routines }, '23:00').routines.map((r) => r.title)).toEqual(['A', 'B', 'C'])
  })
})

describe('localMidnightIso', () => {
  test('is midnight of the day, with the local offset', () => {
    const iso = localMidnightIso('2026-10-24')

    expect(iso).toMatch(/^2026-10-24T00:00:00[+-]\d\d:\d\d$/)
    expect(new Date(iso).getTime()).toBe(new Date(2026, 9, 24).getTime())
  })
})

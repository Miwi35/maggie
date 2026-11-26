import { describe, expect, test } from 'vitest'
import { doneLabel, groupStock } from './taskStock'
import type { ViewTask } from './taskViews'

// Wednesday: this Sunday is 2026-11-29.
const TODAY = '2026-11-25'

const task = (id: string, extra: Partial<ViewTask> = {}): ViewTask => ({
  id,
  title: id,
  priority: 'medium',
  criticality: 'low',
  urgency: 'normal',
  ...extra,
})

const ids = (tasks: { id: string }[]) => tasks.map((t) => t.id)

describe('groupStock', () => {
  test('a task planned or due by Sunday is this week', () => {
    const groups = groupStock(
      [task('due', { dueDate: '2026-11-29' }), task('planned', { scheduledDate: '2026-11-26', dueDate: '2026-12-10' })],
      [],
      TODAY,
    )

    expect(groups).toHaveLength(1)
    expect(groups[0].label).toBe('Cette semaine')
    expect(ids(groups[0].tasks)).toEqual(['due', 'planned'])
  })

  test('a task after Sunday is later', () => {
    const groups = groupStock([task('next', { dueDate: '2026-11-30' })], [], TODAY)

    expect(groups.map((g) => g.label)).toEqual(['Plus tard'])
  })

  test('someday tasks and undated upcoming ones are without a date', () => {
    const groups = groupStock([task('open')], [task('someday', { dueDate: '2026-11-26' })], TODAY)

    expect(groups.map((g) => g.label)).toEqual(['Sans date'])
    expect(ids(groups[0].tasks)).toEqual(['open', 'someday'])
  })

  test('critical then high then the rest, the API order kept between equals', () => {
    const groups = groupStock(
      [
        task('a', { dueDate: TODAY }),
        task('b', { dueDate: TODAY, urgency: 'high' }),
        task('c', { dueDate: TODAY }),
        task('d', { dueDate: TODAY, urgency: 'critical' }),
        task('e', { dueDate: TODAY, urgency: 'high' }),
      ],
      [],
      TODAY,
    )

    expect(ids(groups[0].tasks)).toEqual(['d', 'b', 'e', 'a', 'c'])
  })

  test('a subtask stays right after its task', () => {
    const groups = groupStock(
      [
        task('a', { dueDate: TODAY }),
        task('a1', { dueDate: TODAY, parent: '/api/tasks/a' }),
        task('b', { dueDate: TODAY, urgency: 'critical' }),
      ],
      [],
      TODAY,
    )

    expect(ids(groups[0].tasks)).toEqual(['b', 'a', 'a1'])
  })

  test('an empty group is not returned', () => {
    expect(groupStock([], [], TODAY)).toEqual([])
  })

  test('the three groups come in order', () => {
    const groups = groupStock([task('l', { dueDate: '2026-12-24' }), task('w', { dueDate: TODAY })], [task('s')], TODAY)

    expect(groups.map((g) => g.label)).toEqual(['Cette semaine', 'Plus tard', 'Sans date'])
  })
})

describe('doneLabel (MAG-1033)', () => {
  test('says the next day in French (« Fait · prochaine le 8 déc. »)', () => {
    expect(doneLabel('2026-12-08')).toBe('Fait · prochaine le 8 déc.')
    expect(doneLabel('2026-12-08T00:00:00+00:00')).toBe('Fait · prochaine le 8 déc.')
  })

  test('without a next day says Fait', () => {
    expect(doneLabel(null)).toBe('Fait')
  })
})

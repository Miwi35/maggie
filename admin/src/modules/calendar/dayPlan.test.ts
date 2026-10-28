import { describe, test, expect } from 'vitest'
import { claimEvening, claimMorning, dayTasks, deferOnDay, eveningDue, formatMinutes, morningDue, openOnDay, resetMorningOffer, swapProposal } from './dayPlan'
import type { DayPlan, DayProposal, ProposalEntry } from './dayPlan'

const entry = (id: string, minutes: number, reason: ProposalEntry['reason'] = 'whenever'): ProposalEntry => ({
  reason,
  minutes,
  task: {
    id,
    title: id,
    estimatedMinutes: minutes,
    firstStep: null,
    completedAt: null,
    dueDate: null,
    postponedSince: null,
    urgency: 'normal',
    criticality: 'low',
  },
})

describe('swapProposal — one gesture swaps a slot (MAG-498)', () => {
  const proposal: DayProposal = {
    date: '2026-10-16',
    slots: [
      { ...entry('A', 10, 'postponed'), position: 1, type: 'task' },
      { ...entry('B', 20, 'dueToday'), position: 2, type: 'task' },
    ],
    reserveRanked: [entry('R1', 30, 'quick'), entry('R2', 5)],
    totalMinutes: 30,
    freeMinutes: 180,
  }

  test('the first of the reserve takes the slot, the old task goes to the end of the reserve', () => {
    const next = swapProposal(proposal, 1)

    expect(next.slots.map((s) => [s.position, s.task.id, s.reason])).toEqual([
      [1, 'A', 'postponed'],
      [2, 'R1', 'quick'],
    ])
    expect(next.reserveRanked.map((e) => e.task.id)).toEqual(['R2', 'B'])
    expect(next.totalMinutes).toBe(40)
  })

  test('swapping again walks the reserve without losing a task', () => {
    const next = swapProposal(swapProposal(proposal, 1), 1)

    expect(next.slots[1].task.id).toBe('R2')
    expect(next.reserveRanked.map((e) => e.task.id)).toEqual(['B', 'R1'])
  })

  test('without a reserve nothing changes', () => {
    const alone = { ...proposal, reserveRanked: [] }

    expect(swapProposal(alone, 0)).toBe(alone)
  })

  test('the proposal given is not modified', () => {
    swapProposal(proposal, 0)

    expect(proposal.slots[0].task.id).toBe('A')
    expect(proposal.reserveRanked).toHaveLength(2)
  })
})

describe('formatMinutes', () => {
  test.each([
    [55, '55 min'],
    [60, '1 h'],
    [150, '2 h 30'],
    [185, '3 h 05'],
  ])('%i minutes reads %s', (minutes, label) => {
    expect(formatMinutes(minutes)).toBe(label)
  })
})

describe('morningDue — when the morning opens by itself (MAG-498)', () => {
  const at = (h: number, m: number) => new Date(2026, 9, 16, h, m)

  test('after the morning time while the day is not confirmed', () => {
    expect(morningDue(at(8, 30), '08:00', null)).toBe(true)
    expect(morningDue(at(8, 0), '08:00', null)).toBe(true)
  })

  test('not before the morning time', () => {
    expect(morningDue(at(7, 59), '08:00', null)).toBe(false)
  })

  test('not once the day is confirmed', () => {
    expect(morningDue(at(9, 0), '08:00', '2026-10-16T08:40:00+00:00')).toBe(false)
  })

  test('not when the morning time is unknown', () => {
    expect(morningDue(at(9, 0), null, null)).toBe(false)
  })

  test('it is claimed once until reset', () => {
    resetMorningOffer()

    expect(claimMorning()).toBe(true)
    expect(claimMorning()).toBe(false)

    resetMorningOffer()
    expect(claimMorning()).toBe(true)
    resetMorningOffer()
  })
})

describe('deferOnDay — « Plus tard, sans souci » (MAG-499)', () => {
  const slot = (id: string, done = false) => ({ id, done })

  test('the task goes last of the slots not done', () => {
    expect(deferOnDay([slot('A'), slot('B'), slot('C')], 'A')).toEqual(['B', 'C', 'A'])
    expect(deferOnDay([slot('A'), slot('B'), slot('C')], 'B')).toEqual(['A', 'C', 'B'])
  })

  test('the done slots keep their place', () => {
    expect(deferOnDay([slot('A', true), slot('B'), slot('C', true), slot('D')], 'B')).toEqual(['A', 'D', 'C', 'B'])
    expect(deferOnDay([slot('A'), slot('B', true), slot('C')], 'A')).toEqual(['C', 'B', 'A'])
  })

  test('nothing changes when it is already the last one left, done, or not in the day', () => {
    expect(deferOnDay([slot('A', true), slot('B')], 'B')).toBeNull()
    expect(deferOnDay([slot('A', true), slot('B')], 'A')).toBeNull()
    expect(deferOnDay([slot('A')], 'A')).toBeNull()
    expect(deferOnDay([slot('A'), slot('B')], 'Z')).toBeNull()
  })
})

describe('eveningDue — when the evening opens by itself (MAG-500)', () => {
  const at = (h: number, m: number) => new Date(2026, 9, 16, h, m)

  test('after the evening time on a day that holds tasks, until « Bonne nuit »', () => {
    expect(eveningDue(at(21, 5), '21:00', 5, null)).toBe(true)
    expect(eveningDue(at(21, 0), '21:00', 1, null)).toBe(true)
  })

  test('not before the evening time', () => {
    expect(eveningDue(at(20, 59), '21:00', 5, null)).toBe(false)
  })

  test('not once the review is done', () => {
    expect(eveningDue(at(22, 0), '21:00', 5, '2026-10-16T19:10:00+00:00')).toBe(false)
  })

  test('not on an empty day, nor when the evening time is unknown', () => {
    expect(eveningDue(at(22, 0), '21:00', 0, null)).toBe(false)
    expect(eveningDue(at(22, 0), null, 5, null)).toBe(false)
  })

  test('it is claimed once until reset', () => {
    resetMorningOffer()

    expect(claimEvening()).toBe(true)
    expect(claimEvening()).toBe(false)

    resetMorningOffer()
    expect(claimEvening()).toBe(true)
    resetMorningOffer()
  })
})

describe('dayTasks, openOnDay — what « Aujourd\'hui » lists and counts (MAG-644)', () => {
  const task = (id: string, extra: Partial<ProposalEntry['task']> = {}) => ({ ...entry(id, 10).task, ...extra })
  const day = (slots: ProposalEntry['task'][], reserve: ProposalEntry['task'][]): DayPlan => ({
    id: 'D',
    date: '2026-10-15',
    confirmedAt: null,
    slots: slots.map((t, i) => ({ position: i + 1, type: 'task', task: t })),
    now: null,
    reserve,
    done: slots.filter((t) => t.completedAt != null).length,
    total: slots.length,
  })

  test('keeps the slots whatever their day, and the reserve tasks due today or before', () => {
    const plan = day(
      [task('S1'), task('S2', { dueDate: '2026-10-20' })],
      [task('LATE', { dueDate: '2026-10-10' }), task('TODAY', { dueDate: '2026-10-15' }), task('TOMORROW', { dueDate: '2026-10-16' }), task('NONE')],
    )

    expect(dayTasks(plan, '2026-10-15').map((t) => t.id)).toEqual(['S1', 'S2', 'LATE', 'TODAY'])
  })

  test('reads a due day sent with its time', () => {
    const plan = day([], [task('TODAY', { dueDate: '2026-10-15T00:00:00+00:00' }), task('TOMORROW', { dueDate: '2026-10-16T00:00:00+00:00' })])

    expect(dayTasks(plan, '2026-10-15').map((t) => t.id)).toEqual(['TODAY'])
  })

  test('counts what is left: the open slots and the open tasks of the reserve listed', () => {
    const plan = day(
      [task('S1', { completedAt: '2026-10-15T07:00:00+00:00' }), task('S2')],
      [task('TODAY', { dueDate: '2026-10-15' }), task('TOMORROW', { dueDate: '2026-10-16' }), task('NONE')],
    )

    expect(openOnDay(plan, '2026-10-15')).toBe(2)
  })
})

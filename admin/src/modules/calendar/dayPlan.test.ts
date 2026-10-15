import { describe, test, expect } from 'vitest'
import { claimMorning, formatMinutes, morningDue, placeOnDay, resetMorningOffer, swapProposal } from './dayPlan'
import type { DayProposal, ProposalEntry } from './dayPlan'

describe('placeOnDay — where a task lands in the day (MAG-497)', () => {
  const day = ['A', 'B', 'C']

  test('a reserve task fills the free room after the last slot', () => {
    expect(placeOnDay(day, 'R', 'free')).toEqual(['A', 'B', 'C', 'R'])
  })

  test('a reserve task dropped past the last slot fills the free room too', () => {
    expect(placeOnDay(day, 'R', 4)).toEqual(['A', 'B', 'C', 'R'])
  })

  test('a reserve task dropped on a slot takes it: that slot goes back to the reserve', () => {
    expect(placeOnDay(day, 'R', 1)).toEqual(['A', 'R', 'C'])
  })

  test('with the five taken there is no free room', () => {
    expect(placeOnDay(['A', 'B', 'C', 'D', 'E'], 'R', 'free')).toBeNull()
    expect(placeOnDay(['A', 'B', 'C', 'D', 'E'], 'R', 4)).toEqual(['A', 'B', 'C', 'D', 'R'])
  })

  test('a task of the day moves to the slot it is dropped on, the others shift', () => {
    expect(placeOnDay(day, 'A', 2)).toEqual(['B', 'C', 'A'])
    expect(placeOnDay(day, 'C', 0)).toEqual(['C', 'A', 'B'])
    expect(placeOnDay(day, 'A', 1)).toEqual(['B', 'A', 'C'])
  })

  test('a task of the day dropped on the free room goes last', () => {
    expect(placeOnDay(day, 'A', 'free')).toEqual(['B', 'C', 'A'])
  })

  test('a task dropped where it already is changes nothing', () => {
    expect(placeOnDay(day, 'B', 1)).toBeNull()
    expect(placeOnDay(day, 'C', 'free')).toBeNull()
  })
})

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

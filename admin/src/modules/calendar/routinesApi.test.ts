import { describe, expect, test } from 'vitest'
import { bandAt, dayPartOf, momentLabel } from './routinesApi'
import type { DayMoment, DayRoutine, RoutinesDay } from './routinesApi'

const routine = (id: string, checked = false): DayRoutine => ({
  id,
  title: id,
  anchor: 'wake',
  anchorTime: null,
  time: '07:00',
  remind: false,
  recurrence: { kind: 'daily' },
  checked,
})

const moment = (anchor: DayMoment['anchor'], time: string, routines: DayRoutine[]): DayMoment => ({ anchor, time, routines })

const day = (moments: DayMoment[]): RoutinesDay => ({ date: '2026-10-15', moments })

const ids = (band: ReturnType<typeof bandAt>) => band?.routines.map((r) => r.id)

describe('dayPartOf', () => {
  test.each([
    ['07:00', 'matin'],
    ['11:59', 'matin'],
    ['12:00', 'après-midi'],
    ['17:59', 'après-midi'],
    ['18:00', 'soir'],
    ['22:30', 'soir'],
  ])('%s is %s', (time, part) => {
    expect(dayPartOf(time)).toBe(part)
  })
})

describe('bandAt (MAG-512)', () => {
  const full = day([
    moment('wake', '07:00', [routine('water', true), routine('vitamins')]),
    moment('afterLunch', '13:00', [routine('walk')]),
    moment('time', '22:30', [routine('read')]),
  ])

  test('shows the current moment whole and the earlier ones still to tick', () => {
    const band = bandAt(full, '13:30')

    expect(band?.part).toBe('après-midi')
    expect(ids(band)).toEqual(['vitamins', 'walk'])
  })

  test('before the first moment, the first moment is the current one', () => {
    const band = bandAt(full, '05:00')

    expect(band?.part).toBe('matin')
    expect(ids(band)).toEqual(['water', 'vitamins'])
  })

  test('a moment is current from its hour', () => {
    expect(ids(bandAt(full, '22:30'))).toEqual(['vitamins', 'walk', 'read'])
    expect(ids(bandAt(full, '22:29'))).toEqual(['vitamins', 'walk'])
  })

  test('keeps the ticked routines of the current moment, drops those of earlier ones', () => {
    const ticked = day([moment('wake', '07:00', [routine('water', true)]), moment('afterLunch', '13:00', [routine('walk', true)])])

    expect(ids(bandAt(ticked, '14:00'))).toEqual(['walk'])
  })

  test('is absent when nothing is due, or when the earlier ones are done and the current moment is empty', () => {
    expect(bandAt(day([]), '10:00')).toBeNull()
    expect(bandAt(day([moment('wake', '07:00', [routine('water', true)]), moment('afterLunch', '13:00', [])]), '14:00')).toBeNull()
  })
})

describe('momentLabel', () => {
  test('names a moment of the day and its hour; a moment of its own hour is just the hour', () => {
    expect(momentLabel({ anchor: 'wake', time: '07:00' })).toBe('Au réveil · 7 h')
    expect(momentLabel({ anchor: 'afterDinner', time: '20:30' })).toBe('Après le dîner · 20 h 30')
    expect(momentLabel({ anchor: 'time', time: '22:30' })).toBe('22 h 30')
  })
})

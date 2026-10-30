import { describe, expect, test } from 'vitest'
import {
  feelingLabel,
  formatDistance,
  formatDuration,
  formatLongDate,
  formatShortDate,
  sourceLabel,
  typeLabel,
  workoutUlid,
} from './workoutFormat'

describe('workout labels', () => {
  test('the types, in the plan’s wording', () => {
    expect(['run', 'walk', 'bike', 'swim', 'strength', 'climbing', 'other'].map(typeLabel)).toEqual([
      'Course à pied',
      'Marche',
      'Vélo',
      'Natation',
      'Musculation',
      'Escalade',
      'Autre',
    ])
  })

  test('the feelings and the sources', () => {
    expect(['very_good', 'good', 'hard', 'very_hard'].map(feelingLabel)).toEqual(['Très bien', 'Bien', 'Dur', 'Très dur'])
    expect(['manual', 'watch', 'activity', 'estimated', 'planned'].map(sourceLabel)).toEqual([
      'Saisie',
      'Montre',
      'Activité Sport',
      'Estimée',
      'Prévue',
    ])
  })

  test('an unknown value is shown as it came', () => {
    expect(typeLabel('yoga')).toBe('yoga')
  })
})

describe('workout formats', () => {
  test('a duration under an hour is in minutes, above it in hours and padded minutes', () => {
    expect(formatDuration(45)).toBe('45 min')
    expect(formatDuration(65)).toBe('1 h 05')
    expect(formatDuration(90)).toBe('1 h 30')
    expect(formatDuration(60)).toBe('1 h 00')
  })

  test('a distance has one decimal and a comma', () => {
    expect(formatDistance(5400)).toBe('5,4 km')
    expect(formatDistance(22000)).toBe('22,0 km')
    expect(formatDistance(0)).toBe('0,0 km')
  })

  test('dates are short French dates, the 1st being « 1er »', () => {
    expect(formatShortDate(new Date(2026, 9, 20))).toBe('20 oct.')
    expect(formatShortDate(new Date(2026, 8, 1))).toBe('1er sept.')
    expect(formatShortDate(new Date(2026, 7, 4))).toBe('4 août')
    expect(formatLongDate(new Date(2026, 9, 20))).toBe('Mar. 20 oct.')
    expect(formatLongDate(new Date(2026, 9, 19))).toBe('Lun. 19 oct.')
  })

  test('the ULID is the end of the IRI', () => {
    expect(workoutUlid('/api/workouts/01JABC')).toBe('01JABC')
    expect(workoutUlid('01JABC')).toBe('01JABC')
  })
})

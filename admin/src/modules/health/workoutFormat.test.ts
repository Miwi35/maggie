import { describe, expect, test } from 'vitest'
import {
  exerciseFeelingLabel,
  feelingLabel,
  formatDistance,
  formatDose,
  formatDuration,
  formatLongDate,
  formatShortDate,
  sourceLabel,
  typeLabel,
  workoutUlid,
} from './workoutFormat'
import type { WorkoutExercise } from './workoutFormat'

const exercise = (overrides: Partial<WorkoutExercise>): WorkoutExercise => ({
  exercise: 'pull-up-negative',
  name: 'Tractions négatives',
  unit: 'reps',
  step: 1,
  sets: 3,
  target: 5,
  targetMin: 3,
  targetMax: 6,
  loadKg: null,
  restSeconds: 120,
  tempo: true,
  targetRir: null,
  cue: null,
  feeling: null,
  done: [],
  ...overrides,
})

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

  test('a prescribed dose: sets × target, the unit, the load if any', () => {
    expect(formatDose(exercise({}))).toBe('3 × 5')
    expect(formatDose(exercise({ exercise: 'goblet-squat', target: 10, loadKg: 10 }))).toBe('3 × 10 · 10 kg')
    expect(formatDose(exercise({ exercise: 'plank', unit: 'seconds', target: 30 }))).toBe('3 × 30 s')
    expect(formatDose(exercise({ unit: 'meters', sets: 2, target: 200 }))).toBe('2 × 200 m')
    expect(formatDose(exercise({ loadKg: 12.5 }))).toBe('3 × 5 · 12,5 kg')
  })

  test('a done dose: equal sets with the load they share, unequal ones one by one', () => {
    const nine = { reps: 9, loadKg: 10 }
    expect(formatDose(exercise({ target: 10, loadKg: 10, done: [nine, nine, nine] }))).toBe('3 × 9 · 10 kg')
    expect(formatDose(exercise({ loadKg: 10, done: [{ reps: 9 }, { reps: 9 }] }))).toBe('2 × 9 · 10 kg')
    expect(formatDose(exercise({ done: [{ reps: 9 }, { reps: 9 }] }))).toBe('2 × 9')
    expect(formatDose(exercise({ done: [{ reps: 8 }, { reps: 8 }, { reps: 7 }] }))).toBe('8 · 8 · 7')
    expect(formatDose(exercise({ unit: 'seconds', loadKg: 5, done: [{ seconds: 30 }, { seconds: 25 }] }))).toBe('30 s · 25 s')
  })

  test('the exercise feelings', () => {
    expect(['too_easy', 'right', 'too_hard'].map(exerciseFeelingLabel)).toEqual(['Trop facile', 'Juste bien', 'Trop dur'])
  })
})

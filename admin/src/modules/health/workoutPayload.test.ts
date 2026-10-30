import { describe, expect, test } from 'vitest'
import { emptyValues, kmToMeters, toLocalInput, toOffsetIso, toPayload, validate, valuesOf } from './workoutPayload'
import type { WorkoutValues } from './workoutPayload'

const filled: WorkoutValues = {
  type: 'bike',
  title: ' Vélo ',
  startedAt: '2026-10-18T07:10',
  durationMinutes: '65',
  distanceKm: '22',
  kcal: '',
  avgHeartRate: '',
  feeling: '',
  notes: '',
}

describe('workout payload', () => {
  test('kilometres become whole metres, with a comma or a dot', () => {
    expect(kmToMeters('22')).toBe(22000)
    expect(kmToMeters('5,4')).toBe(5400)
    expect(kmToMeters('5.4')).toBe(5400)
    expect(kmToMeters('0,0004')).toBe(0)
  })

  test('the start is an ISO 8601 instant with the local offset', () => {
    const iso = toOffsetIso('2026-10-18T07:10')

    expect(iso).toMatch(/^2026-10-18T07:10:00[+-]\d{2}:\d{2}$/)
    expect(new Date(iso).getTime()).toBe(new Date(2026, 9, 18, 7, 10).getTime())
  })

  test('the payload carries metres, null for what is blank and a trimmed title', () => {
    const payload = toPayload(filled)

    expect(payload).toMatchObject({
      type: 'bike',
      title: 'Vélo',
      durationMinutes: 65,
      distanceMeters: 22000,
      kcal: null,
      avgHeartRate: null,
      feeling: null,
      notes: null,
    })
    expect(payload.startedAt).toMatch(/^2026-10-18T07:10:00[+-]\d{2}:\d{2}$/)
  })

  test('editing a workout shows its distance in kilometres', () => {
    const values = valuesOf({
      id: '/api/workouts/01J',
      type: 'run',
      title: 'Course',
      startedAt: new Date(2026, 9, 20, 7, 10).toISOString(),
      durationMinutes: 32,
      distanceMeters: 5400,
      kcal: 386,
      avgHeartRate: 152,
      feeling: 'good',
      notes: null,
      source: 'watch',
    })

    expect(values).toMatchObject({ startedAt: '2026-10-20T07:10', distanceKm: '5,4', kcal: '386', avgHeartRate: '152', feeling: 'good', notes: '' })
    expect(toLocalInput(new Date(2026, 9, 20, 7, 10).toISOString())).toBe('2026-10-20T07:10')
  })

  test('the type, the title, the start and the duration are required', () => {
    const errors = validate({ ...emptyValues(), startedAt: '' })

    expect(Object.keys(errors).sort()).toEqual(['durationMinutes', 'startedAt', 'title', 'type'])
    expect(errors.durationMinutes).toBe('Indiquez la durée en minutes.')
    expect(validate(filled)).toEqual({})
  })

  test('the figures keep to the API’s bounds', () => {
    expect(validate({ ...filled, durationMinutes: '0' }).durationMinutes).toBeDefined()
    expect(validate({ ...filled, durationMinutes: '1441' }).durationMinutes).toBeDefined()
    expect(validate({ ...filled, durationMinutes: '12,5' }).durationMinutes).toBeDefined()
    expect(validate({ ...filled, distanceKm: '1001' }).distanceKm).toBeDefined()
    expect(validate({ ...filled, distanceKm: 'beaucoup' }).distanceKm).toBeDefined()
    expect(validate({ ...filled, kcal: '20001' }).kcal).toBeDefined()
    expect(validate({ ...filled, avgHeartRate: '19' }).avgHeartRate).toBeDefined()
    expect(validate({ ...filled, avgHeartRate: '251' }).avgHeartRate).toBeDefined()
    expect(validate({ ...filled, distanceKm: '1000', kcal: '0', avgHeartRate: '250', durationMinutes: '1440' })).toEqual({})
  })
})

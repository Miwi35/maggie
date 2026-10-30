import type { BodyMeasurement } from './bodyMeasurementsApi'
import { toLocalInput, toOffsetIso } from './workoutPayload'

/** What the form holds: strings, as typed. */
export interface MeasurementValues {
  /** `datetime-local`: `2026-10-21T07:12`, local time. */
  measuredAt: string
  weightKg: string
  bodyFatPercent: string
  waistCm: string
}

export type MeasurementErrors = Partial<Record<keyof MeasurementValues, string>>

export interface MeasurementPayload {
  measuredAt: string
  weightKg: number | null
  bodyFatPercent: number | null
  waistCm: number | null
}

export const AT_LEAST_ONE = 'Indiquez au moins le poids, la masse grasse ou le tour de taille.'

const isBlank = (value: string): boolean => value.trim() === ''

/** « 72,4 » or « 72.4 » → 72.4; NaN when it is not a number. */
export const decimal = (value: string): number => (/^\d+([.,]\d+)?$/.test(value.trim()) ? Number(value.trim().replace(',', '.')) : Number.NaN)

const asText = (value: number | null | undefined): string => (value == null ? '' : String(value).replace('.', ','))

export function emptyValues(now: Date = new Date()): MeasurementValues {
  return { measuredAt: toLocalInput(now.toISOString()), weightKg: '', bodyFatPercent: '', waistCm: '' }
}

export function valuesOf(measurement: BodyMeasurement): MeasurementValues {
  return {
    measuredAt: toLocalInput(measurement.measuredAt),
    weightKg: asText(measurement.weightKg),
    bodyFatPercent: asText(measurement.bodyFatPercent),
    waistCm: asText(measurement.waistCm),
  }
}

/** The bounds of the API (plan « Forme JSON »): weight 0 < x ≤ 500, body fat 0 < x < 100, waist 0 < x ≤ 300. */
export function validate(values: MeasurementValues): MeasurementErrors {
  const errors: MeasurementErrors = {}
  if (isBlank(values.measuredAt) || Number.isNaN(new Date(values.measuredAt).getTime())) {
    errors.measuredAt = 'Indiquez la date et l’heure de la pesée.'
  }
  if (isBlank(values.weightKg) && isBlank(values.bodyFatPercent) && isBlank(values.waistCm)) {
    errors.weightKg = AT_LEAST_ONE

    return errors
  }
  if (!isBlank(values.weightKg)) {
    const weight = decimal(values.weightKg)
    if (Number.isNaN(weight) || weight <= 0 || weight > 500) errors.weightKg = 'Le poids doit être supérieur à 0 et au plus de 500 kg.'
  }
  if (!isBlank(values.bodyFatPercent)) {
    const fat = decimal(values.bodyFatPercent)
    if (Number.isNaN(fat) || fat <= 0 || fat >= 100) errors.bodyFatPercent = 'La masse grasse doit être comprise entre 0 et 100 %.'
  }
  if (!isBlank(values.waistCm)) {
    const waist = decimal(values.waistCm)
    if (Number.isNaN(waist) || waist <= 0 || waist > 300) errors.waistCm = 'Le tour de taille doit être supérieur à 0 et au plus de 300 cm.'
  }

  return errors
}

/** The body sent to `/api/body-measurements`: the values already validated. */
export function toPayload(values: MeasurementValues): MeasurementPayload {
  const number = (value: string) => (isBlank(value) ? null : decimal(value))

  return {
    measuredAt: toOffsetIso(values.measuredAt),
    weightKg: number(values.weightKg),
    bodyFatPercent: number(values.bodyFatPercent),
    waistCm: number(values.waistCm),
  }
}

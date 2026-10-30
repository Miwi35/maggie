/** The topics Santé › Suivi listens to: one array, so the page opens one Mercure stream (MAG-473). */
export const BODY_MEASUREMENT_TOPICS = ['/api/body-measurements/{id}']

export const BODY_MEASUREMENTS = 'body-measurements'

export const MEASUREMENT_SOURCES = ['manual', 'health_connect', 'withings'] as const
export type MeasurementSource = (typeof MEASUREMENT_SOURCES)[number]

/** A body measurement as `/api/body-measurements` gives it; `id` is the IRI the Hydra provider keys the record by. */
export interface BodyMeasurement {
  id: string
  measuredAt: string
  weightKg?: number | null
  bodyFatPercent?: number | null
  waistCm?: number | null
  source: MeasurementSource
  externalId?: string | null
  excluded: boolean
}

/** The ULID at the end of an IRI (`/api/body-measurements/01J…`). */
export const measurementUlid = (id: string): string => id.slice(id.lastIndexOf('/') + 1)

import { eurosToCents } from './projectForm'
import type { Placement, PlacementFrequency, PlacementInput } from './placementsApi'

/** The placement form's values, checks and conversions (MAG-555). */

export interface PlacementFormValues {
  name: string
  /** As typed: « 2 000 », « 150,50 ». */
  initial: string
  deposit: string
  frequency: PlacementFrequency
  /** As typed, in percent: « 3,5 ». */
  rate: string
  years: string
}

export type PlacementFormField = 'name' | 'initial' | 'deposit' | 'rate' | 'years'
export type PlacementFormErrors = Partial<Record<PlacementFormField, string>>

export const MAX_YEARS = 60

export const emptyValues = (): PlacementFormValues => ({
  name: '',
  initial: '',
  deposit: '',
  frequency: 'monthly',
  rate: '',
  years: '',
})

const decimal = (value: number): string => String(value).replace('.', ',')

export const valuesOf = (placement: Placement): PlacementFormValues => ({
  name: placement.name,
  initial: decimal(placement.initial / 100),
  deposit: decimal(placement.deposit / 100),
  frequency: placement.frequency,
  rate: decimal(placement.annualRate / 100),
  years: String(placement.years),
})

/** An amount left empty is 0; null when it is not an amount. */
const optionalCents = (typed: string): number | null => (typed.trim() === '' ? 0 : eurosToCents(typed))

/** « 3,5 » → 350, « 6 % » → 600; null when it is not a rate of 0 to 100 % with at most two decimals. */
export const percentToBasisPoints = (typed: string): number | null => {
  const normalised = typed.replace(/[\s\u00a0\u202f%]/g, '').replace(',', '.')
  if (!/^\d+(\.\d{1,2})?$/.test(normalised)) return null
  const basisPoints = Math.round(Number(normalised) * 100)

  return basisPoints <= 10000 ? basisPoints : null
}

/** What the API would refuse, said before it is sent. */
export const validatePlacement = (values: PlacementFormValues): PlacementFormErrors => {
  const errors: PlacementFormErrors = {}
  if (values.name.trim() === '') errors.name = 'Le nom est obligatoire.'
  const initial = optionalCents(values.initial)
  const deposit = optionalCents(values.deposit)
  if (initial === null) errors.initial = 'Indiquez un montant en euros.'
  if (deposit === null) errors.deposit = 'Indiquez un montant en euros.'
  if (initial === 0 && deposit === 0) errors.deposit = 'Indiquez un versement initial ou un versement régulier.'
  if (percentToBasisPoints(values.rate) === null) errors.rate = 'Indiquez un rendement de 0 à 100 %.'
  const years = Number(values.years)
  if (!/^\d+$/.test(values.years.trim()) || years < 1 || years > MAX_YEARS) errors.years = `Indiquez une durée de 1 à ${MAX_YEARS} ans.`

  return errors
}

export const toInput = (values: PlacementFormValues): PlacementInput => ({
  name: values.name.trim(),
  initial: optionalCents(values.initial) ?? 0,
  deposit: optionalCents(values.deposit) ?? 0,
  frequency: values.frequency,
  annualRate: percentToBasisPoints(values.rate) ?? 0,
  years: Number(values.years.trim()),
})

import { bareId } from './envelopeTree'

/**
 * The user's simulated placements (MAG-555): an initial payment, a deposit
 * paid monthly, quarterly, yearly or once, an annual rate and a duration.
 * `GET|POST /api/finance/placements`, `GET|PATCH|DELETE /api/finance/placements/{id}`,
 * and `GET /api/finance/placements/{id}/simulation` for the curve. Every amount
 * is in integer cents, the rate in basis points (600 is 6 %).
 */

export type PlacementFrequency = 'monthly' | 'quarterly' | 'yearly' | 'once'

export interface Placement {
  /** The bare ULID. */
  id: string
  name: string
  initial: number
  deposit: number
  frequency: PlacementFrequency
  /** Basis points: 600 is 6 %. */
  annualRate: number
  years: number
  /** Computed by the API: the capital at the end. */
  final: number
  /** Everything paid in, initial included. */
  paid: number
  /** final − paid. */
  interest: number
  /** final × 4 % / 12: what the capital would give a month, a simulation. */
  rent: number
  /** The deposits per month on average, 0 for a single one. */
  monthlyDeposit: number
}

/** What the form writes. */
export interface PlacementInput {
  name: string
  initial: number
  deposit: number
  frequency: PlacementFrequency
  annualRate: number
  years: number
}

export interface SimulationPoint {
  month: number
  capital: number
  paid: number
}

export interface Simulation {
  final: number
  paid: number
  interest: number
  rent: number
  /** Month 0 to years × 12. */
  curve: SimulationPoint[]
}

export const PLACEMENTS_PATH = '/api/finance/placements'

/** Every Mercure payload of a placement — created, changed, deleted — goes out on this topic. */
export const PLACEMENT_TOPIC = '/api/finance/placements/{id}'

/** What the API answers when neither an initial payment nor a deposit is given. */
export const NOTHING_PAID = 'A placement needs an initial payment or a deposit.'

export const FREQUENCY_CHOICES: { id: PlacementFrequency; label: string }[] = [
  { id: 'monthly', label: 'Mensuel' },
  { id: 'quarterly', label: 'Trimestriel' },
  { id: 'yearly', label: 'Annuel' },
  { id: 'once', label: 'Ponctuel' },
]

const PERIOD: Record<Exclude<PlacementFrequency, 'once'>, string> = { monthly: 'mois', quarterly: 'trimestre', yearly: 'an' }

const EUROS = new Intl.NumberFormat('fr-FR', { style: 'currency', currency: 'EUR', minimumFractionDigits: 0, maximumFractionDigits: 0 })
const PERCENT = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 2 })

/** 7443007 → « 74 430 € »: rounded to the euro. */
export const formatEuros = (cents: number): string => EUROS.format(Math.round((cents ?? 0) / 100))

/** 600 → « 6 % », 350 → « 3,5 % ». */
export const formatRate = (basisPoints: number): string => PERCENT.format((basisPoints ?? 0) / 10000)

/** « 20 ans », « 1 an ». */
export const yearsLabel = (years: number): string => `${years} ${years > 1 ? 'ans' : 'an'}`

/** « 150 € / mois · 2 000 € initial », « 1 200 € / an », « 500 € une fois ». */
export const depositsLabel = (placement: Pick<Placement, 'initial' | 'deposit' | 'frequency'>): string => {
  const parts: string[] = []
  if (placement.deposit > 0) {
    parts.push(
      placement.frequency === 'once'
        ? `${formatEuros(placement.deposit)} une fois`
        : `${formatEuros(placement.deposit)} / ${PERIOD[placement.frequency]}`,
    )
  }
  if (placement.initial > 0) {
    parts.push(placement.deposit > 0 ? `${formatEuros(placement.initial)} initial` : `${formatEuros(placement.initial)} une fois`)
  }

  return parts.join(' · ')
}

/** By name, as the API lists them. */
export const byName = (placements: Placement[]): Placement[] =>
  [...placements].sort((a, b) => a.name.localeCompare(b.name, 'fr', { sensitivity: 'base' }))

type Raw = Record<string, unknown>

const FREQUENCIES: PlacementFrequency[] = ['monthly', 'quarterly', 'yearly', 'once']

const frequencyOf = (value: unknown): PlacementFrequency =>
  FREQUENCIES.includes(value as PlacementFrequency) ? (value as PlacementFrequency) : 'monthly'

/** A member of the collection, an item or a full Mercure create. */
export const toPlacement = (raw: Raw): Placement => ({
  id: bareId(String(raw.id ?? raw['@id'] ?? '')),
  name: String(raw.name ?? ''),
  initial: Number(raw.initial ?? 0),
  deposit: Number(raw.deposit ?? 0),
  frequency: frequencyOf(raw.frequency),
  annualRate: Number(raw.annualRate ?? 0),
  years: Number(raw.years ?? 1),
  final: Number(raw.final ?? 0),
  paid: Number(raw.paid ?? 0),
  interest: Number(raw.interest ?? 0),
  rent: Number(raw.rent ?? 0),
  monthlyDeposit: Number(raw.monthlyDeposit ?? 0),
})

const NUMBERS = ['initial', 'deposit', 'annualRate', 'years', 'final', 'paid', 'interest', 'rent', 'monthlyDeposit'] as const

/** A Mercure update carries only what changed: laid over the placement as it is. */
export const patchPlacement = (placement: Placement, payload: Raw): Placement => {
  const patched: Placement = { ...placement }
  if (typeof payload.name === 'string') patched.name = payload.name
  if ('frequency' in payload) patched.frequency = frequencyOf(payload.frequency)
  for (const field of NUMBERS) {
    if (field in payload) patched[field] = Number(payload[field])
  }

  return patched
}

/** Whether a Mercure payload holds a whole placement, as a create sends it. */
export const isWholePlacement = (payload: Raw): boolean =>
  typeof payload.name === 'string' && ['initial', 'deposit', 'frequency', 'annualRate', 'years', 'final'].every((field) => field in payload)

/** A write the API refused, in words the owner can read. */
export class PlacementError extends Error {
  /** The field the API blamed, when it is one the form shows. */
  readonly field?: string

  constructor(message: string, field?: string) {
    super(message)
    this.field = field
  }
}

const request = (path: string, method = 'GET', body?: unknown) => {
  const token = localStorage.getItem('token')
  const contentType = method === 'PATCH' ? 'application/merge-patch+json' : 'application/ld+json'

  return fetch(path, {
    method,
    headers: {
      Accept: 'application/ld+json',
      ...(body !== undefined ? { 'Content-Type': contentType } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
}

/**
 * The API refuses in English: the one refusal the form cannot foresee is said
 * in French under the deposit, anything else as the fallback sentence.
 */
const refused = async (response: Response, fallback: string): Promise<PlacementError> => {
  const body = (await response.json().catch(() => ({}))) as {
    violations?: { propertyPath?: string; message?: string }[]
    detail?: string
  }
  const nothingPaid = body.violations?.some((violation) => violation.message === NOTHING_PAID) || body.detail?.includes(NOTHING_PAID)
  if (nothingPaid) return new PlacementError('Indiquez un versement initial ou un versement régulier.', 'deposit')

  return new PlacementError(fallback)
}

const toBody = (input: PlacementInput) => ({
  name: input.name,
  initial: input.initial,
  deposit: input.deposit,
  frequency: input.frequency,
  annualRate: input.annualRate,
  years: input.years,
})

/** Every placement of the user, by name — all of them, in one answer. */
export async function fetchPlacements(): Promise<Placement[]> {
  const response = await request(`${PLACEMENTS_PATH}?pagination=false`)
  if (!response.ok) throw new PlacementError(`placements: ${response.status}`)
  const body = (await response.json()) as { member?: Raw[] }

  return byName((body.member ?? []).map(toPlacement))
}

export async function createPlacement(input: PlacementInput): Promise<Placement> {
  const response = await request(PLACEMENTS_PATH, 'POST', toBody(input))
  if (!response.ok) throw await refused(response, `Le placement « ${input.name} » n'a pas été créé.`)

  return toPlacement(await response.json())
}

export async function updatePlacement(id: string, input: PlacementInput): Promise<Placement> {
  const response = await request(`${PLACEMENTS_PATH}/${bareId(id)}`, 'PATCH', toBody(input))
  if (!response.ok) throw await refused(response, `Le placement « ${input.name} » n'a pas été modifié.`)

  return toPlacement(await response.json())
}

export async function deletePlacement(id: string): Promise<void> {
  const response = await request(`${PLACEMENTS_PATH}/${bareId(id)}`, 'DELETE')
  if (!response.ok && response.status !== 404) throw await refused(response, "Le placement n'a pas été supprimé.")
}

/** The placement month by month, to draw its curve. */
export async function fetchSimulation(id: string): Promise<Simulation> {
  const response = await request(`${PLACEMENTS_PATH}/${bareId(id)}/simulation`)
  if (!response.ok) throw new PlacementError(`simulation: ${response.status}`)
  const body = (await response.json()) as Partial<Simulation>

  return {
    final: Number(body.final ?? 0),
    paid: Number(body.paid ?? 0),
    interest: Number(body.interest ?? 0),
    rent: Number(body.rent ?? 0),
    curve: (body.curve ?? []).map((point) => ({
      month: Number(point.month),
      capital: Number(point.capital),
      paid: Number(point.paid),
    })),
  }
}

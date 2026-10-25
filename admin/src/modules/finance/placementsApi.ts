import { formatCentsRounded } from './accountTypes'
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

/** The capital a project takes out of a placement the month it is bought (MAG-556). */
export interface Withdrawal {
  /** The bare id of the project. */
  projectId: string
  /** The project's own name, « Voyage au Japon ». */
  name: string
  /** « 2027-04 »: the month of the purchase. */
  month: string
  amountCents: number
}

export interface Simulation {
  final: number
  paid: number
  interest: number
  rent: number
  /** Month 0 to years × 12. */
  curve: SimulationPoint[]
  /** What the projects of the scenario take out, by purchase month; empty when none does. */
  withdrawals: Withdrawal[]
  /** The cents really taken out. */
  withdrawn: number
  /** The capital with no withdrawal, month 0 to years × 12: `curve` itself when nothing leaves. */
  curveWithout: SimulationPoint[]
  /** The capital once the withdrawals are made. */
  curveWith: SimulationPoint[]
  /** The capital at the end without the withdrawals, less the capital at the end with them. */
  gapAtEnd: number
  finalWith: number
  /** The rente at 4 % of the capital after the withdrawals, per month. */
  rentWith: number
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

const PERCENT = new Intl.NumberFormat('fr-FR', { style: 'percent', maximumFractionDigits: 2 })

/** 600 → « 6 % », 350 → « 3,5 % ». */
export const formatRate = (basisPoints: number): string => PERCENT.format((basisPoints ?? 0) / 10000)

/** « 2027-04 » → « avril 2027 ». */
export const formatMonth = (month: string): string => {
  const [year, number] = month.split('-').map(Number)
  if (!year || !number) return month

  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(year, number - 1, 1)))
}

/** « Voyage au Japon », « Japon et Vélo », « A, B et C ». */
export const projectNames = (withdrawals: Pick<Withdrawal, 'name'>[]): string =>
  new Intl.ListFormat('fr-FR', { style: 'long', type: 'conjunction' }).format([...new Set(withdrawals.map((withdrawal) => withdrawal.name))])

/** « 20 ans », « 1 an ». */
export const yearsLabel = (years: number): string => `${years} ${years > 1 ? 'ans' : 'an'}`

/** « 150 € / mois · 2 000 € initial », « 1 200 € / an », « 500 € une fois ». */
export const depositsLabel = (placement: Pick<Placement, 'initial' | 'deposit' | 'frequency'>): string => {
  const parts: string[] = []
  if (placement.deposit > 0) {
    parts.push(
      placement.frequency === 'once'
        ? `${formatCentsRounded(placement.deposit)} une fois`
        : `${formatCentsRounded(placement.deposit)} / ${PERIOD[placement.frequency]}`,
    )
  }
  if (placement.initial > 0) {
    parts.push(placement.deposit > 0 ? `${formatCentsRounded(placement.initial)} initial` : `${formatCentsRounded(placement.initial)} une fois`)
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

const toPoints = (points: unknown): SimulationPoint[] =>
  (Array.isArray(points) ? (points as Raw[]) : []).map((point) => ({
    month: Number(point.month),
    capital: Number(point.capital),
    paid: Number(point.paid),
  }))

/**
 * The placement month by month, to draw its curve — and, when the projects of
 * the scenario (the current one by default) take capital out of it, with and
 * without those withdrawals (MAG-556). An answer without the new keys is a
 * placement nothing leaves: both curves are `curve`.
 */
export async function fetchSimulation(id: string, scenarioId?: string): Promise<Simulation> {
  const query = scenarioId ? `?scenarioId=${encodeURIComponent(bareId(scenarioId))}` : ''
  const response = await request(`${PLACEMENTS_PATH}/${bareId(id)}/simulation${query}`)
  if (!response.ok) throw new PlacementError(`simulation: ${response.status}`)
  const body = (await response.json()) as Raw

  const final = Number(body.final ?? 0)
  const rent = Number(body.rent ?? 0)
  const curve = toPoints(body.curve)
  const withdrawals = (Array.isArray(body.withdrawals) ? (body.withdrawals as Raw[]) : []).map((withdrawal) => ({
    projectId: bareId(String(withdrawal.projectId ?? '')),
    name: String(withdrawal.name ?? ''),
    month: String(withdrawal.month ?? ''),
    amountCents: Number(withdrawal.amountCents ?? 0),
  }))
  const curveWithout = Array.isArray(body.curveWithout) ? toPoints(body.curveWithout) : curve
  const curveWith = Array.isArray(body.curveWith) ? toPoints(body.curveWith) : curve

  return {
    final,
    paid: Number(body.paid ?? 0),
    interest: Number(body.interest ?? 0),
    rent,
    curve,
    withdrawals,
    withdrawn: Number(body.withdrawn ?? withdrawals.reduce((sum, withdrawal) => sum + withdrawal.amountCents, 0)),
    curveWithout,
    curveWith,
    gapAtEnd: Number(body.gapAtEnd ?? 0),
    finalWith: Number(body.finalWith ?? final),
    rentWith: Number(body.rentWith ?? rent),
  }
}

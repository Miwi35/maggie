import { bareId } from './envelopeTree'
import { formatDuration } from './independenceDates'

/**
 * The project scenarios (MAG-550, MAG-551): a selection and an order of the
 * user's projects, early repayments, placements and a monthly saving, each one
 * giving a date N. One of them at most is « courant ».
 * `GET|POST /api/finance/scenarios`, `GET|PATCH|DELETE /api/finance/scenarios/{id}`,
 * `POST …/duplicate`, `POST …/make-current`, `DELETE /api/finance/current-scenario`
 * and `GET /api/finance/scenario-comparison?ids=a,b,c`.
 */

export type ScenarioKind = 'user' | 'auto' | 'advisor'
export type ScenarioStatus = 'draft' | 'saved'

export interface ScenarioRepayment {
  id?: string
  loanId: string
  /** `YYYY-MM`. */
  month: string
  /** Null repays the whole capital. */
  amountCents: number | null
}

export interface ScenarioMetrics {
  /** `YYYY-MM`; null when a project falls past the horizon. */
  projectsDoneBy: string | null
  debtsEndDate: string | null
  interestPaid: number
  wellbeing: number
}

export interface Scenario {
  /** A stored scenario's ULID, or `auto-rapid`, `auto-balanced`, `auto-quality` for an offer not made current yet. */
  id: string
  name: string
  kind: ScenarioKind
  status: ScenarioStatus
  current: boolean
  /** Bare project ids, first priority first. */
  projectIds: string[]
  repayments: ScenarioRepayment[]
  placementIds: string[]
  monthlySaving: number | null
  /** `YYYY-MM`; null when N is not reached within 20 years. */
  nDate: string | null
  metrics: ScenarioMetrics
}

export interface CurvePoint {
  /** `YYYY-MM`. */
  month: string
  /** 0 to 1. */
  coverage: number
}

/** A scenario read in the comparison: its coverage curve and milestones come with it. */
export interface ComparedScenario extends Scenario {
  curve: CurvePoint[]
  /** 25, 50, 75 and 100 % reached in a month, or null. */
  milestones: Record<string, string | null>
}

export interface NDifference {
  scenarioId: string
  againstId: string
  months: number | null
  /** Already worded, in facts: shown as it is. */
  sentence: string
}

export interface Comparison {
  referenceId: string
  scenarios: ComparedScenario[]
  differences: NDifference[]
}

/** What the editor writes; an absent field is left as it is. */
export interface ScenarioInput {
  name?: string
  projectIds?: string[]
  repayments?: ScenarioRepayment[]
  placementIds?: string[]
  monthlySaving?: number | null
}

export interface LoanChoice {
  id: string
  name: string
}

export const SCENARIOS_PATH = '/api/finance/scenarios'

/** Every Mercure payload of a scenario goes out on this topic. */
export const SCENARIO_TOPIC = '/api/finance/scenarios/{id}'

export const MAX_COMPARED = 6

type Raw = Record<string, unknown>

const toRepayment = (raw: Raw): ScenarioRepayment => ({
  ...(raw.id ? { id: String(raw.id) } : {}),
  loanId: bareId(String(raw.loanId ?? '')),
  month: String(raw.month ?? ''),
  amountCents: raw.amountCents === null || raw.amountCents === undefined ? null : Number(raw.amountCents),
})

/** A scenario of the list, of the detail or of a write's answer. */
export const toScenario = (raw: Raw): Scenario => {
  const metrics = (raw.metrics as Raw | undefined) ?? {}
  const kind = raw.kind === 'auto' || raw.kind === 'advisor' ? raw.kind : 'user'

  return {
    id: String(raw.id ?? ''),
    name: String(raw.name ?? ''),
    kind,
    status: raw.status === 'saved' ? 'saved' : 'draft',
    current: raw.current === true,
    projectIds: [...((raw.projects as Raw[] | undefined) ?? [])]
      .sort((a, b) => Number(a.position ?? 0) - Number(b.position ?? 0))
      .map((row) => bareId(String(row.projectId ?? ''))),
    repayments: ((raw.repayments as Raw[] | undefined) ?? []).map(toRepayment),
    placementIds: ((raw.placements as Raw[] | undefined) ?? []).map((row) => bareId(String(row.placementId ?? ''))),
    monthlySaving: raw.monthlySaving === null || raw.monthlySaving === undefined ? null : Number(raw.monthlySaving),
    nDate: (raw.nDate as string | null | undefined) ?? null,
    metrics: {
      projectsDoneBy: (metrics.projectsDoneBy as string | null | undefined) ?? null,
      debtsEndDate: (metrics.debtsEndDate as string | null | undefined) ?? null,
      interestPaid: Number(metrics.interestPaid ?? 0),
      wellbeing: Number(metrics.wellbeing ?? 0),
    },
  }
}

const toCompared = (raw: Raw): ComparedScenario => ({
  ...toScenario(raw),
  curve: ((raw.curve as Raw[] | undefined) ?? []).map((point) => ({
    month: String(point.month ?? ''),
    coverage: Number(point.coverage ?? 0),
  })),
  milestones: (raw.milestones as Record<string, string | null> | undefined) ?? {},
})

export const toComparison = (raw: Raw): Comparison => ({
  referenceId: String(raw.referenceId ?? ''),
  scenarios: ((raw.scenarios as Raw[] | undefined) ?? []).map(toCompared),
  differences: ((raw.differences as Raw[] | undefined) ?? []).map((row) => ({
    scenarioId: String(row.scenarioId ?? ''),
    againstId: String(row.againstId ?? ''),
    months: row.months === null || row.months === undefined ? null : Number(row.months),
    sentence: String(row.sentence ?? ''),
  })),
})

/** The three offers made for the user: drafts that are duplicated or made current, never renamed or deleted. */
export const isAuto = (scenario: Pick<Scenario, 'kind'>): boolean => scenario.kind === 'auto'

export const isAdvisor = (scenario: Pick<Scenario, 'kind'>): boolean => scenario.kind === 'advisor'

/** « mars 2041 » from `2041-03`; « — » when there is no month. */
export const monthLabel = (month: string | null | undefined): string => {
  if (!month) return '—'
  const date = new Date(`${month}-01T00:00:00Z`)
  if (Number.isNaN(date.getTime())) return month

  return new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(date)
}

/** « mars 2041 », or the fact that N is out of reach. */
export const nLabel = (scenario: Pick<Scenario, 'nDate'>): string => (scenario.nDate ? monthLabel(scenario.nDate) : 'non atteinte avant 20 ans')

/** « Scénario courant · Rapide · N : mars 2041 ». */
export const currentLabel = (scenario: Pick<Scenario, 'name' | 'nDate'>): string =>
  `Scénario courant · ${scenario.name} · N : ${nLabel(scenario)}`

const monthNumber = (month: string): number => {
  const [year, number] = month.split('-').map(Number)

  return (year ?? 0) * 12 + (number ?? 1) - 1
}

/** N against the current scenario's, in facts: « 9 mois plus tôt que le courant »; null when either N is out of reach. */
export const gapAgainst = (scenario: Pick<Scenario, 'nDate'>, current: Pick<Scenario, 'nDate'>): string | null => {
  if (!scenario.nDate || !current.nDate) return null
  const months = monthNumber(scenario.nDate) - monthNumber(current.nDate)
  if (months === 0) return 'N à la même date que le courant'

  return `N ${formatDuration(Math.abs(months))} ${months < 0 ? 'plus tôt' : 'plus tard'} que le courant`
}

/** « 150 » or « 150,5 » typed in euros → cents; null when empty, NaN when not a positive amount. */
export const eurosToCents = (text: string): number | null => {
  const clean = text.trim().replace(/\s/g, '').replace(',', '.')
  if (clean === '') return null
  const euros = Number(clean)

  return Number.isFinite(euros) && euros > 0 ? Math.round(euros * 100) : Number.NaN
}

/** Cents → the euros typed in a field: 15050 → « 150.5 ». */
export const centsToEuros = (cents: number | null): string => (cents === null ? '' : String(cents / 100))

/** A write the API refused, with its own sentence. */
export class ScenarioError extends Error {}

const request = (path: string, method = 'GET', body?: unknown, accept = 'application/json') => {
  const token = localStorage.getItem('token')

  return fetch(path, {
    method,
    headers: {
      Accept: accept,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
}

const refused = async (response: Response, fallback: string): Promise<ScenarioError> => {
  const body = (await response.json().catch(() => ({}))) as { error?: unknown }

  return new ScenarioError(typeof body.error === 'string' && body.error ? body.error : fallback)
}

export async function fetchScenarios(): Promise<Scenario[]> {
  const response = await request(SCENARIOS_PATH)
  if (!response.ok) throw await refused(response, 'Les scénarios ne peuvent pas être chargés.')
  const body = (await response.json()) as { scenarios?: Raw[] }

  return (body.scenarios ?? []).map(toScenario)
}

/** Two to six ids, the first is the reference. */
export async function fetchComparison(ids: string[]): Promise<Comparison> {
  const response = await request(`/api/finance/scenario-comparison?ids=${ids.map(encodeURIComponent).join(',')}`)
  if (!response.ok) throw await refused(response, 'La comparaison ne peut pas être chargée.')

  return toComparison((await response.json()) as Raw)
}

const written = async (response: Response, fallback: string): Promise<Scenario> => {
  if (!response.ok) throw await refused(response, fallback)
  const body = (await response.json()) as { scenario?: Raw }

  return toScenario(body.scenario ?? {})
}

export async function createScenario(input: ScenarioInput): Promise<Scenario> {
  return written(await request(SCENARIOS_PATH, 'POST', input), "Le scénario n'a pas été créé.")
}

export async function updateScenario(id: string, input: ScenarioInput): Promise<Scenario> {
  return written(await request(`${SCENARIOS_PATH}/${id}`, 'PATCH', input), "Le scénario n'a pas été modifié.")
}

export async function duplicateScenario(id: string, name?: string): Promise<Scenario> {
  return written(await request(`${SCENARIOS_PATH}/${id}/duplicate`, 'POST', name ? { name } : {}), "Le scénario n'a pas été dupliqué.")
}

export async function makeCurrent(id: string): Promise<Scenario> {
  return written(await request(`${SCENARIOS_PATH}/${id}/make-current`, 'POST'), "Le scénario n'a pas été rendu courant.")
}

export async function deleteScenario(id: string): Promise<void> {
  const response = await request(`${SCENARIOS_PATH}/${id}`, 'DELETE')
  if (!response.ok) throw await refused(response, "Le scénario n'a pas été supprimé.")
}

export async function clearCurrent(): Promise<void> {
  const response = await request('/api/finance/current-scenario', 'DELETE')
  if (!response.ok) throw await refused(response, "Le scénario courant n'a pas été retiré.")
}

/** The loans a repayment can target. */
export async function fetchLoanChoices(): Promise<LoanChoice[]> {
  const response = await request('/api/loans?pagination=false', 'GET', undefined, 'application/ld+json')
  if (!response.ok) throw new ScenarioError(`loans: ${response.status}`)
  const body = (await response.json()) as { member?: Raw[] }

  return (body.member ?? []).map((raw) => ({ id: bareId(String(raw.id ?? raw['@id'] ?? '')), name: String(raw.name ?? '') }))
}

import { bareId } from './envelopeTree'

/**
 * The user's projects (MAG-519): what they save for, in their priority order.
 * `GET|POST /api/finance/projects`, `GET|PATCH|DELETE /api/finance/projects/{id}`,
 * and `POST /api/finance/projects/reorder` for the order.
 */

export type ProjectWellbeing = 'essential' | 'high' | 'medium' | 'comfort'
export type ProjectDateMode = 'none' | 'fixed' | 'deadline'

export interface Project {
  /** The bare ULID. */
  id: string
  name: string
  amountCents: number
  currency: string
  wellbeing: ProjectWellbeing
  wellbeingScore: number
  /** 0 first. */
  position: number
  dateMode: ProjectDateMode
  /** `YYYY-MM-DD`, null when the project has no date. */
  targetOn: string | null
  /** Bare ids of the projects this one waits for. */
  dependsOn: string[]
  note: string | null
  /** Bare ids of the dependencies dated after this project. */
  unmetDependencies: string[]
  /** Where the money comes from besides the salary (MAG-524). */
  fundingSources: FundingSource[]
  /** « Le reste »: what the salary pays, the amount minus the sources. */
  salaryCents: number
  /** Set when the project is an investment (MAG-557): rent, loan, planned resale. Null for any other project. */
  investment: ProjectInvestment | null
}

/** The planned resale of an investment: the net amount, once the loan is repaid and the fees paid. */
export interface ProjectResale {
  /** `YYYY-MM`. */
  month: string
  netAmountCents: number
  /** Bare id of the project the proceeds are set aside for; null: they speed up the projects waiting. */
  targetProjectId: string | null
}

/** A project that is bought to be rented, then resold (MAG-557). */
export interface ProjectInvestment {
  priceCents: number
  downPaymentCents: number
  /** Bare id of the projected loan; null when the down payment is the whole price. */
  loanId: string | null
  loanPaymentCents: number
  monthlyRentCents: number
  /** The rent minus the loan payment: negative when the owner tops it up. */
  monthlyBalanceCents: number
  resale: ProjectResale | null
}

/** What `PUT …/investment` takes; the investment is replaced whole. */
export interface InvestmentInput {
  /** The project's amount; left out, it stays. */
  priceCents?: number
  downPaymentCents: number
  /** The conditions of the loan; left out, the project's current loan keeps its own. */
  loan?: { type: 'amortizing_fixed'; annualRateBasisPoints: number; durationMonths: number }
  monthlyRentCents: number
  resale?: { month: string; netAmountCents: number; targetProjectId?: string }
}

export type FundingKind = 'savings' | 'capital' | 'loan' | 'resale'

export interface FundingSource {
  kind: FundingKind
  amountCents: number
  /** Bare id of the account a savings or capital source draws on. */
  accountId: string | null
  /** Bare id of the simulated placement a capital source leaves, when it names one (MAG-556). */
  placementId?: string | null
  /** Bare id of the projected loan of a loan source. */
  loanId: string | null
  /** Bare id of the investment whose resale a `resale` source stands for (MAG-557); read-only. */
  sourceProjectId: string | null
}

/** One source as `PUT …/funding` takes it; the list sent is the whole list. */
export interface FundingSourceInput {
  kind: Exclude<FundingKind, 'resale'>
  amountCents: number
  account?: string
  /** Bare id of the placement a capital source leaves (MAG-556). */
  placement?: string
  loan?: Record<string, unknown>
}

/** What one way of paying costs and how long it takes (MAG-524). */
export interface ProjectOption {
  totalCostCents: number
  netCostCents: number
  monthlyPaymentCents: number
  durationMonths: number
  /** Months of saving before the purchase; null when nothing is left to save. */
  availableInMonths: number | null
  capitalKeptCents: number
  /** Null until the independence number is computed (part 9). */
  nImpactMonths: number | null
}

export interface ProjectOptions {
  amountCents: number
  netCapacityCents: number
  loanRateBasisPoints: number
  loanMonths: number
  downPaymentCents: number
  yieldBasisPoints: number
  cash: ProjectOption
  loan: ProjectOption
  partial: ProjectOption
}

/** The simulation's parameters; null while not given. */
export interface OptionParams {
  loanRateBasisPoints: number | null
  loanMonths: number | null
  downPaymentCents: number | null
  yieldBasisPoints: number | null
}

export interface FundingAccount {
  id: string
  name: string
  type: string
  cushion: boolean
}

/** What the form writes. */
export interface ProjectInput {
  name: string
  amountCents: number
  wellbeing: ProjectWellbeing
  dateMode: ProjectDateMode
  targetOn: string | null
  /** Bare ids. */
  dependsOn: string[]
  note: string | null
}

export const PROJECTS_PATH = '/api/finance/projects'

/** Every Mercure payload of a project — created, changed, moved, deleted — goes out on this topic. */
export const PROJECT_TOPIC = '/api/finance/projects/{id}'

export const WELLBEING_CHOICES: { id: ProjectWellbeing; label: string; score: number }[] = [
  { id: 'essential', label: 'Indispensable', score: 4 },
  { id: 'high', label: 'Élevé', score: 3 },
  { id: 'medium', label: 'Moyen', score: 2 },
  { id: 'comfort', label: 'Confort', score: 1 },
]

export const DATE_MODE_CHOICES: { id: ProjectDateMode; label: string; hint: string }[] = [
  { id: 'none', label: 'Sans date', hint: 'décalable' },
  { id: 'fixed', label: 'Date fixe', hint: 'verrouillée' },
  { id: 'deadline', label: 'Date limite', hint: 'au plus tard' },
]

export const wellbeingLabel = (wellbeing: ProjectWellbeing): string =>
  WELLBEING_CHOICES.find((choice) => choice.id === wellbeing)?.label ?? wellbeing

const SCORES: Record<ProjectWellbeing, number> = { essential: 4, high: 3, medium: 2, comfort: 1 }

/** « 1er mars 2027 », « 15 janvier 2027 »: the calendar day, whatever the browser's zone. */
const longDay = (isoDate: string): string => {
  const date = new Date(`${isoDate}T00:00:00Z`)
  const monthYear = new Intl.DateTimeFormat('fr-FR', { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(date)
  const day = date.getUTCDate()

  return `${day === 1 ? '1er' : day} ${monthYear}`
}

/** « Sans date », « le 1er mars 2027 », « au plus tard le 31 août 2027 ». */
export const projectDateLabel = (project: Pick<Project, 'dateMode' | 'targetOn'>): string => {
  if (project.dateMode === 'none' || !project.targetOn) return 'Sans date'

  return project.dateMode === 'fixed' ? `le ${longDay(project.targetOn)}` : `au plus tard le ${longDay(project.targetOn)}`
}

/** « Déménagement », « Déménagement et Voiture », « A, B et C ». */
export const joinNames = (names: string[]): string =>
  names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} et ${names[names.length - 1]}`

/** Sorted by position, 0 first. */
export const byPosition = (projects: Project[]): Project[] => [...projects].sort((a, b) => a.position - b.position)

type Raw = Record<string, unknown>

/** A member of the collection, an item, a reorder answer or a full Mercure create. */
const toSource = (raw: Raw): FundingSource => ({
  kind: raw.kind as FundingKind,
  amountCents: Number(raw.amountCents ?? 0),
  accountId: raw.accountId ? bareId(String(raw.accountId)) : null,
  placementId: raw.placementId ? bareId(String(raw.placementId)) : null,
  loanId: raw.loanId ? bareId(String(raw.loanId)) : null,
  sourceProjectId: raw.sourceProjectId ? bareId(String(raw.sourceProjectId)) : null,
})

export const toInvestment = (raw: Raw | null | undefined): ProjectInvestment | null => {
  if (!raw) return null
  const resale = raw.resale as Raw | null | undefined

  return {
    priceCents: Number(raw.priceCents ?? 0),
    downPaymentCents: Number(raw.downPaymentCents ?? 0),
    loanId: raw.loanId ? bareId(String(raw.loanId)) : null,
    loanPaymentCents: Number(raw.loanPaymentCents ?? 0),
    monthlyRentCents: Number(raw.monthlyRentCents ?? 0),
    monthlyBalanceCents: Number(raw.monthlyBalanceCents ?? 0),
    resale: resale
      ? {
          month: String(resale.month ?? ''),
          netAmountCents: Number(resale.netAmountCents ?? 0),
          targetProjectId: resale.targetProjectId ? bareId(String(resale.targetProjectId)) : null,
        }
      : null,
  }
}

export const toProject = (raw: Raw): Project => {
  const wellbeing = (raw.wellbeing as ProjectWellbeing) ?? 'medium'
  const fundingSources = ((raw.fundingSources as Raw[] | undefined) ?? []).map(toSource)
  const amountCents = Number(raw.amountCents ?? 0)

  return {
    id: bareId(String(raw.id ?? raw['@id'] ?? '')),
    name: String(raw.name ?? ''),
    amountCents,
    currency: String(raw.currency ?? 'EUR'),
    wellbeing,
    wellbeingScore: Number(raw.wellbeingScore ?? SCORES[wellbeing] ?? 0),
    position: Number(raw.position ?? 0),
    dateMode: (raw.dateMode as ProjectDateMode) ?? 'none',
    targetOn: (raw.targetOn as string | null) ?? null,
    dependsOn: ((raw.dependsOn as string[] | undefined) ?? []).map((id) => bareId(id)),
    note: (raw.note as string | null) ?? null,
    unmetDependencies: ((raw.unmetDependencies as string[] | undefined) ?? []).map((id) => bareId(id)),
    fundingSources,
    salaryCents: Number(raw.salaryCents ?? Math.max(0, amountCents - fundingSources.reduce((sum, source) => sum + source.amountCents, 0))),
    investment: toInvestment(raw.investment as Raw | null | undefined),
  }
}

/** A Mercure update carries only what changed: laid over the project as it is. */
export const patchProject = (project: Project, payload: Raw): Project => {
  const patched: Project = { ...project }
  const fields = ['name', 'amountCents', 'currency', 'wellbeing', 'wellbeingScore', 'position', 'dateMode', 'targetOn', 'note'] as const
  for (const field of fields) {
    if (field in payload) (patched as unknown as Raw)[field] = payload[field]
  }
  if (Array.isArray(payload.dependsOn)) patched.dependsOn = (payload.dependsOn as string[]).map((id) => bareId(id))
  if (Array.isArray(payload.unmetDependencies)) {
    patched.unmetDependencies = (payload.unmetDependencies as string[]).map((id) => bareId(id))
  }
  if (Array.isArray(payload.fundingSources)) patched.fundingSources = (payload.fundingSources as Raw[]).map(toSource)
  if (typeof payload.salaryCents === 'number') patched.salaryCents = payload.salaryCents
  if ('investment' in payload) patched.investment = toInvestment(payload.investment as Raw | null)

  return patched
}

/** A write the API refused, in words the owner can read. */
export class ProjectError extends Error {
  /** The field the API blamed, when it named one. */
  readonly field?: string

  constructor(message: string, field?: string) {
    super(message)
    this.field = field
  }
}

/** The API's own sentences, in French. */
const translate = (message: string): string => {
  if (/cycle/i.test(message)) return 'Ces dépendances formeraient un cycle : un projet finirait par attendre lui-même.'
  if (/depend on itself/i.test(message)) return 'Un projet ne peut pas dépendre de lui-même.'

  return message
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

const refused = async (response: Response, fallback: string): Promise<ProjectError> => {
  const body = (await response.json().catch(() => ({}))) as {
    violations?: { propertyPath?: string; message?: string }[]
    detail?: string
    description?: string
    error?: string
  }
  const violation = body.violations?.[0]
  if (violation?.message) return new ProjectError(translate(violation.message), violation.propertyPath)
  const message = body.detail ?? body.description ?? body.error

  return new ProjectError(typeof message === 'string' && message ? translate(message) : fallback)
}

/** The form has no currency: a new project is in euros, a changed one keeps its own. */
const toBody = (input: ProjectInput, currency = 'EUR') => ({
  name: input.name,
  amountCents: input.amountCents,
  currency,
  wellbeing: input.wellbeing,
  dateMode: input.dateMode,
  targetOn: input.dateMode === 'none' ? null : input.targetOn,
  dependsOn: input.dependsOn.map((id) => `${PROJECTS_PATH}/${bareId(id)}`),
  note: input.note,
})

/** Every project of the user, first priority first — all of them, in one answer. */
export async function fetchProjects(): Promise<Project[]> {
  const response = await request(`${PROJECTS_PATH}?pagination=false`)
  if (!response.ok) throw new ProjectError(`projects: ${response.status}`)
  const body = (await response.json()) as { member?: Raw[] }

  return byPosition((body.member ?? []).map(toProject))
}

export async function createProject(input: ProjectInput): Promise<Project> {
  const response = await request(PROJECTS_PATH, 'POST', toBody(input))
  if (!response.ok) throw await refused(response, `Le projet « ${input.name} » n'a pas été créé.`)

  return toProject(await response.json())
}

export async function updateProject(project: Pick<Project, 'id' | 'currency'>, input: ProjectInput): Promise<Project> {
  const response = await request(`${PROJECTS_PATH}/${bareId(project.id)}`, 'PATCH', toBody(input, project.currency))
  if (!response.ok) throw await refused(response, `Le projet « ${input.name} » n'a pas été modifié.`)

  return toProject(await response.json())
}

export async function deleteProject(id: string): Promise<void> {
  const response = await request(`${PROJECTS_PATH}/${bareId(id)}`, 'DELETE')
  if (!response.ok && response.status !== 404) throw await refused(response, "Le projet n'a pas été supprimé.")
}

/** The whole priority order, every project id, first one first. Answers the projects in that order. */
export async function reorderProjects(ids: string[]): Promise<Project[]> {
  const response = await request(`${PROJECTS_PATH}/reorder`, 'POST', { ids: ids.map((id) => bareId(id)) })
  if (!response.ok) throw await refused(response, "Le nouvel ordre des projets n'a pas été enregistré.")
  const body = (await response.json()) as { projects?: Raw[] }

  return byPosition((body.projects ?? []).map(toProject))
}

export type CushionPlanState = 'building' | 'complete' | 'recharging'

export interface PlannedLoan {
  /** `YYYY-MM`, the month after the purchase. */
  startMonth: string
  /** `YYYY-MM`; null when the loan outlives the 36 months. */
  endMonth: string | null
  monthlyPaymentCents: number
}

/** One project of the planning, in priority order (`GET /api/finance/project-planning`, MAG-526). */
export interface PlannedProject {
  projectId: string
  name: string
  position: number
  wellbeing: ProjectWellbeing
  amountCents: number
  dateMode: ProjectDateMode
  /** `YYYY-MM-DD`. */
  targetOn: string | null
  /** `YYYY-MM`. */
  savingFromMonth: string
  /** `YYYY-MM`; null when the purchase falls past the horizon. */
  purchaseMonth: string | null
  gatheredCents: number
  loan: PlannedLoan | null
  /** `YYYY-MM` the last resale funding this project lands; null when it has none or one is beyond the planning. */
  resaleFromMonth: string | null
  /** What the project brings when it is an investment (MAG-557); null otherwise. */
  investment: PlannedInvestment | null
  isBeyondHorizon: boolean
  isDeadlineMissed: boolean
  delayMonths: number
  unmetDependencies: string[]
}

export interface PlannedInvestment {
  monthlyRentCents: number
  monthlyBalanceCents: number
  /** `YYYY-MM`, the month after the purchase; null while the purchase is beyond the planning. */
  rentFromMonth: string | null
  /** `YYYY-MM`, the last month rented; null: still rented at the end of the planning. */
  rentUntilMonth: string | null
  /** `YYYY-MM`; null without a resale, or when it falls beyond the planning. */
  resaleMonth: string | null
  resaleNetCents: number | null
  resaleTargetProjectId: string | null
}

/** One month of the planning: what the salary can give the projects, and what funds them by source. */
export interface PlanningMonth {
  /** `YYYY-MM`. */
  month: string
  capacityCents: number
  salaryCents: number
  savingsCents: number
  capitalCents: number
  /** The rent received this month (MAG-557). */
  rentCents: number
  /** The net proceeds of the resale landing this month (MAG-557). */
  resaleCents: number
  isOverCapacity: boolean
}

export interface ProjectPlanning {
  /** `YYYY-MM`, the current month: month 0. */
  startMonth: string
  horizonMonths: number
  currency: string
  isIncomeKnown: boolean
  cushion: { state: CushionPlanState; completeMonth: string | null }
  months: PlanningMonth[]
  projects: PlannedProject[]
}

export const PLANNING_PATH = '/api/finance/project-planning'

const toPlannedInvestment = (raw: Raw | null | undefined): PlannedInvestment | null =>
  raw
    ? {
        monthlyRentCents: Number(raw.monthlyRentCents ?? 0),
        monthlyBalanceCents: Number(raw.monthlyBalanceCents ?? 0),
        rentFromMonth: (raw.rentFromMonth as string | null) ?? null,
        rentUntilMonth: (raw.rentUntilMonth as string | null) ?? null,
        resaleMonth: (raw.resaleMonth as string | null) ?? null,
        resaleNetCents: raw.resaleNetCents === null || raw.resaleNetCents === undefined ? null : Number(raw.resaleNetCents),
        resaleTargetProjectId: raw.resaleTargetProjectId ? bareId(String(raw.resaleTargetProjectId)) : null,
      }
    : null

const toPlannedProject = (raw: Raw): PlannedProject => {
  const loan = raw.loan as Raw | null | undefined

  return {
    projectId: bareId(String(raw.projectId ?? '')),
    name: String(raw.name ?? ''),
    position: Number(raw.position ?? 0),
    wellbeing: (raw.wellbeing as ProjectWellbeing) ?? 'medium',
    amountCents: Number(raw.amountCents ?? 0),
    dateMode: (raw.dateMode as ProjectDateMode) ?? 'none',
    targetOn: (raw.targetOn as string | null) ?? null,
    savingFromMonth: String(raw.savingFromMonth ?? ''),
    purchaseMonth: (raw.purchaseMonth as string | null) ?? null,
    gatheredCents: Number(raw.gatheredCents ?? 0),
    loan: loan
      ? {
          startMonth: String(loan.startMonth ?? ''),
          endMonth: (loan.endMonth as string | null) ?? null,
          monthlyPaymentCents: Number(loan.monthlyPaymentCents ?? 0),
        }
      : null,
    resaleFromMonth: (raw.resaleFromMonth as string | null) ?? null,
    investment: toPlannedInvestment(raw.investment as Raw | null | undefined),
    isBeyondHorizon: Boolean(raw.isBeyondHorizon),
    isDeadlineMissed: Boolean(raw.isDeadlineMissed),
    delayMonths: Number(raw.delayMonths ?? 0),
    unmetDependencies: ((raw.unmetDependencies as string[] | undefined) ?? []).map((id) => bareId(id)),
  }
}

const toPlanningMonth = (raw: Raw): PlanningMonth => ({
  month: String(raw.month ?? ''),
  capacityCents: Number(raw.capacityCents ?? 0),
  salaryCents: Number(raw.salaryCents ?? 0),
  savingsCents: Number(raw.savingsCents ?? 0),
  capitalCents: Number(raw.capitalCents ?? 0),
  rentCents: Number(raw.rentCents ?? 0),
  resaleCents: Number(raw.resaleCents ?? 0),
  isOverCapacity: raw.isOverCapacity === true,
})

/** The projects over the next 36 months, first priority first. */
export async function fetchProjectPlanning(): Promise<ProjectPlanning> {
  const response = await request(PLANNING_PATH)
  if (!response.ok) throw new ProjectError(`project-planning: ${response.status}`)
  const body = (await response.json()) as Raw
  const cushion = (body.cushion as Raw | undefined) ?? {}

  return {
    startMonth: String(body.startMonth ?? ''),
    horizonMonths: Number(body.horizonMonths ?? 36),
    currency: String(body.currency ?? 'EUR'),
    isIncomeKnown: body.isIncomeKnown === true,
    cushion: {
      state: (cushion.state as CushionPlanState) ?? 'complete',
      completeMonth: (cushion.completeMonth as string | null) ?? null,
    },
    months: ((body.months as Raw[] | undefined) ?? []).map(toPlanningMonth),
    projects: ((body.projects as Raw[] | undefined) ?? []).map(toPlannedProject).sort((a, b) => a.position - b.position),
  }
}

/**
 * Replaces the whole list of sources (`PUT /api/finance/projects/{id}/funding`).
 * A refusal — the sources adding up to more than the project, a missing
 * condition — is the API's own sentence, kept as rendered.
 */
export async function saveFunding(id: string, sources: FundingSourceInput[]): Promise<Project> {
  const response = await request(`${PROJECTS_PATH}/${bareId(id)}/funding`, 'PUT', { sources })
  if (!response.ok) throw await refused(response, "Les sources n'ont pas été enregistrées.")
  const body = (await response.json()) as { project?: Raw }

  return toProject(body.project ?? {})
}

/**
 * Replaces the project's investment (`PUT /api/finance/projects/{id}/investment`): the price, the down
 * payment, the loan for the rest, the rent and the planned resale. A refusal is the API's own sentence.
 */
export async function saveInvestment(id: string, input: InvestmentInput): Promise<Project> {
  const response = await request(`${PROJECTS_PATH}/${bareId(id)}/investment`, 'PUT', input)
  if (!response.ok) throw await refused(response, "L'investissement n'a pas été enregistré.")
  const body = (await response.json()) as { project?: Raw }

  return toProject(body.project ?? {})
}

/** Makes the project a plain one again: its loan and rent go, the resale funding of its target too. */
export async function removeInvestment(id: string): Promise<Project> {
  const response = await request(`${PROJECTS_PATH}/${bareId(id)}/investment`, 'DELETE')
  if (!response.ok) throw await refused(response, "L'investissement n'a pas été supprimé.")
  const body = (await response.json()) as { project?: Raw }

  return toProject(body.project ?? {})
}

/** The three options priced; a missing parameter or a refusal is the API's `error`. */
export async function fetchProjectOptions(id: string, params: OptionParams): Promise<ProjectOptions> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== null) query.set(key, String(value))
  }
  const response = await request(`${PROJECTS_PATH}/${bareId(id)}/options?${query.toString()}`)
  if (!response.ok) throw await refused(response, 'Les options ne peuvent pas être chiffrées.')

  return (await response.json()) as ProjectOptions
}

/** The accounts a source can draw on: the caller filters by kind. */
export async function fetchFundingAccounts(): Promise<FundingAccount[]> {
  const response = await request('/api/accounts?pagination=false')
  if (!response.ok) throw new ProjectError(`accounts: ${response.status}`)
  const body = (await response.json()) as { member?: Raw[] }

  return (body.member ?? []).map((raw) => ({
    id: bareId(String(raw.id ?? raw['@id'] ?? '')),
    name: String(raw.name ?? ''),
    type: String(raw.type ?? ''),
    cushion: raw.cushion === true || raw.isCushion === true,
  }))
}

/** A loan source's stored conditions, to edit them. */
export async function fetchLoanConditions(loanId: string): Promise<Record<string, unknown>> {
  const response = await request(`/api/loans/${bareId(loanId)}`)
  if (!response.ok) throw new ProjectError(`loan: ${response.status}`)

  return (await response.json()) as Record<string, unknown>
}

export interface ProjectPreviewScenario {
  /** A stored scenario's ULID, or `auto-*` for an offer of Maggie's. */
  id: string
  name: string
  /** False for an automatic scenario: it is duplicated, not added to. */
  editable: boolean
}

export interface ProjectPreviewDate {
  projectId: string
  name: string
  /** `YYYY-MM`; null when the project falls past the 36 months. */
  before: string | null
  after: string | null
}

/** What adding a project would change (MAG-553): facts, computed without writing anything. */
export interface ProjectPreview {
  /** The scenario the preview is computed in; null when none is current (the projects in their order). */
  scenario: ProjectPreviewScenario | null
  project: { name: string; amountCents: number; currency: string }
  dates: ProjectPreviewDate[]
  /** `YYYY-MM`; null when N is not reached within 20 years. */
  nDate: { before: string | null; after: string | null }
  metrics: {
    realCost: number
    /** The month the new project is bought, null past the planning. */
    availableAt: string | null
    /** Months N moves: positive later, negative earlier, null when not reached before or after. */
    nImpactMonths: number | null
  }
}

const PREVIEW_PATH = `${PROJECTS_PATH}/preview`

const toPreview = (raw: Raw): ProjectPreview => {
  const scenario = raw.scenario as Raw | null | undefined
  const project = (raw.project as Raw | undefined) ?? {}
  const nDate = (raw.nDate as Raw | undefined) ?? {}
  const metrics = (raw.metrics as Raw | undefined) ?? {}

  return {
    scenario: scenario ? { id: String(scenario.id ?? ''), name: String(scenario.name ?? ''), editable: scenario.editable === true } : null,
    project: { name: String(project.name ?? ''), amountCents: Number(project.amountCents ?? 0), currency: String(project.currency ?? 'EUR') },
    dates: ((raw.dates as Raw[] | undefined) ?? []).map((row) => ({
      projectId: bareId(String(row.projectId ?? '')),
      name: String(row.name ?? ''),
      before: (row.before as string | null | undefined) ?? null,
      after: (row.after as string | null | undefined) ?? null,
    })),
    nDate: { before: (nDate.before as string | null | undefined) ?? null, after: (nDate.after as string | null | undefined) ?? null },
    metrics: {
      realCost: Number(metrics.realCost ?? 0),
      availableAt: (metrics.availableAt as string | null | undefined) ?? null,
      nImpactMonths: metrics.nImpactMonths === null || metrics.nImpactMonths === undefined ? null : Number(metrics.nImpactMonths),
    },
  }
}

/**
 * What adding this project would change, in the current scenario or in `scenarioId`
 * (`POST /api/finance/projects/preview`). Nothing is created.
 */
export async function previewProject(input: ProjectInput, scenarioId?: string): Promise<ProjectPreview> {
  const body = {
    name: input.name,
    amountCents: input.amountCents,
    wellbeing: input.wellbeing,
    dateMode: input.dateMode,
    targetOn: input.dateMode === 'none' ? null : input.targetOn,
    dependsOn: input.dependsOn.map(bareId),
    ...(scenarioId ? { scenarioId } : {}),
  }
  const response = await request(PREVIEW_PATH, 'POST', body)
  if (!response.ok) throw await refused(response, "L'aperçu n'a pas pu être calculé.")

  return toPreview((await response.json()) as Raw)
}

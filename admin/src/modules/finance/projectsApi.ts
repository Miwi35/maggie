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
export const toProject = (raw: Raw): Project => {
  const wellbeing = (raw.wellbeing as ProjectWellbeing) ?? 'medium'

  return {
    id: bareId(String(raw.id ?? raw['@id'] ?? '')),
    name: String(raw.name ?? ''),
    amountCents: Number(raw.amountCents ?? 0),
    currency: String(raw.currency ?? 'EUR'),
    wellbeing,
    wellbeingScore: Number(raw.wellbeingScore ?? SCORES[wellbeing] ?? 0),
    position: Number(raw.position ?? 0),
    dateMode: (raw.dateMode as ProjectDateMode) ?? 'none',
    targetOn: (raw.targetOn as string | null) ?? null,
    dependsOn: ((raw.dependsOn as string[] | undefined) ?? []).map((id) => bareId(id)),
    note: (raw.note as string | null) ?? null,
    unmetDependencies: ((raw.unmetDependencies as string[] | undefined) ?? []).map((id) => bareId(id)),
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

const toBody = (input: ProjectInput) => ({
  name: input.name,
  amountCents: input.amountCents,
  currency: 'EUR',
  wellbeing: input.wellbeing,
  dateMode: input.dateMode,
  targetOn: input.dateMode === 'none' ? null : input.targetOn,
  dependsOn: input.dependsOn.map((id) => `${PROJECTS_PATH}/${bareId(id)}`),
  note: input.note,
})

/** Every project of the user, first priority first. */
export async function fetchProjects(): Promise<Project[]> {
  const response = await request(`${PROJECTS_PATH}?itemsPerPage=100`)
  if (!response.ok) throw new ProjectError(`projects: ${response.status}`)
  const body = (await response.json()) as { member?: Raw[] }

  return byPosition((body.member ?? []).map(toProject))
}

export async function createProject(input: ProjectInput): Promise<Project> {
  const response = await request(PROJECTS_PATH, 'POST', toBody(input))
  if (!response.ok) throw await refused(response, `Le projet « ${input.name} » n'a pas été créé.`)

  return toProject(await response.json())
}

export async function updateProject(id: string, input: ProjectInput): Promise<Project> {
  const response = await request(`${PROJECTS_PATH}/${bareId(id)}`, 'PATCH', toBody(input))
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

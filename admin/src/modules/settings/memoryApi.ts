import { agentFetch } from './agentFetch'

export type MemoryKind = 'fiche' | 'conversation' | 'generated' | 'conflict' | 'miss'

export interface MemoryTreeEntry {
  path: string
  title: string
  kind: MemoryKind
  resume: string
  archived: boolean
  question: string
  editable: boolean
}

export interface MemoryClaim {
  texte: string
  provenance: 'dit' | 'observé' | 'déduit' | string
  source: string
  heure: string | null
  sourcePath: string | null
  sourceKind: string
  sourceExists: boolean
  replacedOn: string | null
  motif: string
}

export interface MemoryCarte {
  id: string
  resume: string
  moments: string[]
  personnes: string[]
  evoque: string[]
  alias: string[]
  liens: string[]
  sources: string[]
  archivee: string
  question: string
}

export interface MemoryFile {
  path: string
  title: string
  kind: MemoryKind
  text: string
  hash: string
  editable: boolean
  deletable: boolean
  carte: MemoryCarte | null
  unreadable: string | null
  known: MemoryClaim[]
  replaced: MemoryClaim[]
}

export interface MemoryJournalEntry {
  at: string
  text: string
  night: boolean
}

export interface MemorySyncState {
  enabled: boolean
  degraded: boolean
  degradedSince: string | null
  lastError: string | null
  lastSyncAt: string | null
  pending: number
  pendingPaths: string[]
  conflicts: string[]
}

export interface MemoryMiss {
  path: string
  title: string
  demande: string
  attendue: string
  souvenir: string
  statut: string
  cause: string
  enregistre: string
  vertDepuis: string | null
  ressorti: string | null
  historique: string[]
}

/** The agent refused the call; `status` tells why (404 unknown path, 409 changed or not editable, 400 unreadable). */
export class MemoryApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await agentFetch(path, init)
  } catch {
    throw new MemoryApiError(0, 'Erreur réseau')
  }
  if (!response.ok) {
    let detail = ''
    try {
      detail = (await response.json()).detail ?? ''
    } catch {
      // no body
    }
    throw new MemoryApiError(response.status, typeof detail === 'string' ? detail : '')
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

const fileUrl = (path: string) => `/memory/file?path=${encodeURIComponent(path)}`

export const memoryApi = {
  tree: () => call<{ files: MemoryTreeEntry[] }>('/memory/tree').then((r) => r.files),
  open: (path: string) => call<MemoryFile>(fileUrl(path)),
  save: (path: string, text: string, hash: string) =>
    call<MemoryFile>(fileUrl(path), { method: 'PUT', body: JSON.stringify({ text, hash }) }),
  move: (path: string, to: string) =>
    call<MemoryFile>(`/memory/file/move?path=${encodeURIComponent(path)}`, {
      method: 'POST',
      body: JSON.stringify({ to }),
    }),
  remove: (path: string) => call<void>(fileUrl(path), { method: 'DELETE' }),
  journal: () => call<{ entries: MemoryJournalEntry[] }>('/memory/journal').then((r) => r.entries),
  sync: () => call<MemorySyncState>('/memory/sync'),
  runSync: () => call<MemorySyncState>('/memory/sync', { method: 'POST' }),
  rebuild: () => call<{ files: number; skipped: number }>('/memory/rebuild', { method: 'POST' }),
  misses: () => call<{ misses: MemoryMiss[] }>('/memory/misses').then((r) => r.misses),
}

/** The whole memory as a zip, saved through the browser: the bearer rules out a plain link. */
export async function downloadMemoryArchive(): Promise<void> {
  const response = await agentFetch('/memory/export')
  if (!response.ok) throw new MemoryApiError(response.status, '')
  const blob = await response.blob()
  const match = /filename="([^"]+)"/.exec(response.headers.get('Content-Disposition') ?? '')
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = match?.[1] ?? 'memoire-maggie.zip'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

export function ago(iso: string | null, now: number = Date.now()): string {
  if (!iso) return 'jamais'
  const minutes = Math.max(0, Math.round((now - new Date(iso).getTime()) / 60000))
  if (minutes < 1) return 'à l’instant'
  if (minutes < 60) return `il y a ${minutes} min`
  if (minutes < 60 * 24) return `il y a ${Math.round(minutes / 60)} h`
  return `il y a ${Math.round(minutes / (60 * 24))} j`
}

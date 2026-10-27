import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemorySettings } from './MemorySettings'
import type { MemoryFile, MemoryJournalEntry, MemoryMiss, MemorySyncState, MemoryTreeEntry } from './memoryApi'

/**
 * The « Mémoire » tab (MAG-20): the owner reads their memory as it is in the bucket, corrects a fiche, follows a claim
 * to the conversation it comes from, and sees what the bucket cannot show — the journal, the state of the sync, the
 * missed recalls.
 */

const ROSES = "Famille/Françoise n'aime pas les roses.md"
const CONVERSATION = 'Conversations/2026-10-04 Cadeau.md'

const TREE: MemoryTreeEntry[] = [
  { path: ROSES, title: "Françoise n'aime pas les roses", kind: 'fiche', resume: 'Les fleurs de Françoise', archived: false, question: '', editable: true },
  { path: CONVERSATION, title: '2026-10-04 Cadeau', kind: 'conversation', resume: '', archived: false, question: '', editable: false },
  { path: 'journal.md', title: 'journal', kind: 'generated', resume: '', archived: false, question: '', editable: false },
]

const FICHE: MemoryFile = {
  path: ROSES,
  title: "Françoise n'aime pas les roses",
  kind: 'fiche',
  text: "---\nid: 01HX\nresume: Les fleurs de Françoise\n---\n\n## Ce qu'on sait\n\n- Françoise n'aime pas les roses — dit · " + CONVERSATION + ' · 2026-10-04 18h14\n',
  hash: 'h1',
  editable: true,
  deletable: true,
  carte: { id: '01HX', resume: 'Les fleurs de Françoise', moments: [], personnes: ['Françoise'], evoque: ['bouquet'], alias: [], liens: [], sources: [], archivee: '', question: '' },
  unreadable: null,
  known: [
    {
      texte: "Françoise n'aime pas les roses",
      provenance: 'dit',
      source: CONVERSATION,
      heure: '2026-10-04 18h14',
      sourcePath: CONVERSATION,
      sourceKind: 'conversation',
      sourceExists: true,
      replacedOn: null,
      motif: '',
    },
  ],
  replaced: [],
}

const CONVERSATION_FILE: MemoryFile = {
  ...FICHE,
  path: CONVERSATION,
  title: '2026-10-04 Cadeau',
  kind: 'conversation',
  text: '## 2026-10-04 17h02 — Propriétaire\n\nBonjour.\n\n## 2026-10-04 18h14 — Propriétaire\n\nFrançoise n\'aime pas les roses.\n',
  editable: false,
  deletable: false,
  carte: null,
  known: [],
}

const SYNC_OK: MemorySyncState = {
  enabled: true,
  degraded: false,
  degradedSince: null,
  lastError: null,
  lastSyncAt: new Date(Date.now() - 3 * 60000).toISOString(),
  pending: 0,
  pendingPaths: [],
  conflicts: [],
}

const JOURNAL: MemoryJournalEntry[] = [
  { at: '2026-10-14 03h10', text: 'entretien nocturne du 2026-10-14 : 2 fiches complétées', night: true },
  { at: '2026-10-13 18h14', text: `modifié \`${ROSES}\` — corrigé par le propriétaire depuis l'onglet Mémoire`, night: false },
]

const MISS: MemoryMiss = {
  path: 'Rappels manqués/2026-10-14 bouquet.md',
  title: '2026-10-14 bouquet',
  demande: 'Un bouquet pour la fête des mères',
  attendue: ROSES,
  souvenir: '',
  statut: 'ouvert',
  cause: 'la fiche n’est pas ressortie',
  enregistre: '2026-10-14',
  vertDepuis: null,
  ressorti: null,
  historique: [],
}

type Routes = Record<string, (init?: RequestInit) => Response | Promise<Response>>

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

const listeners = new Set<(paths: string[] | null) => void>()
const subscribe = vi.fn((listener: (paths: string[] | null) => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
})
const emit = (paths: string[] | null) => listeners.forEach((listener) => listener(paths))

const notify = vi.fn()

function stub(overrides: Routes = {}) {
  const routes: Routes = {
    'GET /agent/memory/tree': () => json({ files: TREE }),
    'GET /agent/memory/sync': () => json(SYNC_OK),
    [`GET /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () => json(FICHE),
    [`GET /agent/memory/file?path=${encodeURIComponent(CONVERSATION)}`]: () => json(CONVERSATION_FILE),
    'GET /agent/memory/journal': () => json({ entries: JOURNAL }),
    'GET /agent/memory/misses': () => json({ misses: [MISS] }),
    ...overrides,
  }
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const handler = routes[`${init?.method ?? 'GET'} ${String(input)}`]
    if (!handler) throw new Error(`unexpected call ${init?.method ?? 'GET'} ${String(input)}`)
    return handler(init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const calls = (fetchMock: ReturnType<typeof stub>, method: string, prefix: string) =>
  fetchMock.mock.calls.filter(([url, init]) => (init?.method ?? 'GET') === method && String(url).startsWith(prefix))

async function openRoses() {
  render(<MemorySettings notify={notify} subscribe={subscribe} />)
  await userEvent.click(await screen.findByRole('button', { name: /Famille/ }))
  await userEvent.click(await screen.findByRole('button', { name: /n'aime pas les roses/ }))
  await screen.findByRole('heading', { name: ROSES })
}

describe('MemorySettings', () => {
  beforeEach(() => {
    notify.mockClear()
    listeners.clear()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  test('shows the tree as the bucket has it, folders first, closed until opened', async () => {
    stub()
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    const tree = await screen.findByRole('list', { name: 'Arborescence de la mémoire' })
    expect(within(tree).getByRole('button', { name: /Famille/ })).toHaveAttribute('aria-expanded', 'false')
    expect(within(tree).getByRole('button', { name: /journal/ })).toBeInTheDocument()
    expect(within(tree).queryByText("Françoise n'aime pas les roses")).not.toBeInTheDocument()

    await userEvent.click(within(tree).getByRole('button', { name: /Famille/ }))
    expect(within(tree).getByText("Françoise n'aime pas les roses")).toBeInTheDocument()
  })

  test('opens a fiche with its carte and each claim with its provenance', async () => {
    stub()
    await openRoses()

    expect(screen.getByText('bouquet')).toBeInTheDocument()
    const claims = screen.getByLabelText('Affirmations')
    expect(within(claims).getByText('dit')).toBeInTheDocument()
    expect(within(claims).getByText("Françoise n'aime pas les roses")).toBeInTheDocument()
    expect(screen.getByLabelText('Markdown')).toHaveValue(FICHE.text)
  })

  test('follows a claim to the cited minute of the conversation', async () => {
    stub()
    await openRoses()

    await userEvent.click(screen.getByRole('button', { name: /Voir la conversation · 2026-10-04 18h14/ }))

    const cited = await screen.findByTestId('cited-passage')
    expect(cited).toHaveTextContent('## 2026-10-04 18h14 — Propriétaire')
    expect(cited).not.toHaveTextContent('Bonjour.')
    expect(screen.queryByLabelText('Markdown')).not.toBeInTheDocument()
  })

  test('saves the owner’s correction with the hash it started from', async () => {
    const saved: MemoryFile = { ...FICHE, text: FICHE.text.replace('n\'aime pas', 'adore'), hash: 'h2' }
    const fetchMock = stub({
      [`PUT /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () => json(saved),
    })
    await openRoses()

    const editor = screen.getByLabelText('Markdown')
    await userEvent.clear(editor)
    await userEvent.click(editor)
    await userEvent.paste(saved.text)
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Fichier enregistré', { type: 'success' }))
    const [, init] = calls(fetchMock, 'PUT', '/agent/memory/file')[0]
    expect(JSON.parse(String(init?.body))).toEqual({ text: saved.text, hash: 'h1' })
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('does not offer to save before anything is edited', async () => {
    stub()
    await openRoses()

    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('says so when the file changed meanwhile, and overwrites nothing', async () => {
    stub({
      [`PUT /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () => json({ detail: 'changed' }, 409),
    })
    await openRoses()

    await userEvent.type(screen.getByLabelText('Markdown'), ' plus')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText(/Ce fichier a changé depuis que vous l'avez ouvert/)).toBeInTheDocument()
    expect(notify).toHaveBeenCalledWith(expect.stringContaining('rien n’a été écrasé'), { type: 'error' })
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('shows the reason when the carte cannot be read', async () => {
    stub({
      [`PUT /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () =>
        json({ detail: "la carte de la fiche n'est pas lisible : yaml" }, 400),
    })
    await openRoses()

    await userEvent.type(screen.getByLabelText('Markdown'), '!')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith("la carte de la fiche n'est pas lisible : yaml", { type: 'error' }))
  })

  test('renames a fiche and opens it at its new path', async () => {
    const moved: MemoryFile = { ...FICHE, path: 'Amis/Françoise et les roses.md' }
    const fetchMock = stub({
      [`POST /agent/memory/file/move?path=${encodeURIComponent(ROSES)}`]: () => json(moved),
    })
    await openRoses()

    await userEvent.click(screen.getByRole('button', { name: 'Renommer / déplacer' }))
    const field = screen.getByLabelText('Nouveau chemin')
    await userEvent.clear(field)
    await userEvent.type(field, 'Amis/Françoise et les roses.md')
    await userEvent.click(screen.getByRole('button', { name: 'Déplacer' }))

    expect(await screen.findByRole('heading', { name: 'Amis/Françoise et les roses.md' })).toBeInTheDocument()
    const [, init] = calls(fetchMock, 'POST', '/agent/memory/file/move')[0]
    expect(JSON.parse(String(init?.body))).toEqual({ to: 'Amis/Françoise et les roses.md' })
  })

  test('asks before deleting, then removes the fiche', async () => {
    const fetchMock = stub({
      [`DELETE /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () => new Response(null, { status: 204 }),
    })
    await openRoses()

    await userEvent.click(screen.getByRole('button', { name: 'Supprimer' }))
    expect(calls(fetchMock, 'DELETE', '/agent/memory/file')).toHaveLength(0)
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(calls(fetchMock, 'DELETE', '/agent/memory/file')).toHaveLength(1))
    expect(await screen.findByText('Choisissez un fichier pour le lire ou le corriger.')).toBeInTheDocument()
  })

  test('a conversation can be read but not edited nor deleted', async () => {
    stub()
    render(<MemorySettings notify={notify} subscribe={subscribe} />)
    await userEvent.click(await screen.findByRole('button', { name: /Conversations/ }))
    await userEvent.click(await screen.findByRole('button', { name: /Cadeau/ }))

    await screen.findByRole('heading', { name: CONVERSATION })
    expect(screen.queryByRole('button', { name: 'Enregistrer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Supprimer' })).not.toBeInTheDocument()
  })

  test('says when the bucket is unreachable, since when, the last sync and what waits', async () => {
    stub({
      'GET /agent/memory/sync': () =>
        json({
          ...SYNC_OK,
          degraded: true,
          degradedSince: new Date(Date.now() - 2 * 3600000).toISOString(),
          lastSyncAt: new Date(Date.now() - 3 * 3600000).toISOString(),
          lastError: 'timeout',
          pending: 2,
          pendingPaths: ['Maison/A.md', 'Maison/B.md'],
        }),
    })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    const banner = await screen.findByRole('alert')
    expect(banner).toHaveTextContent('Le bucket ne répond pas (depuis il y a 2 h)')
    expect(banner).toHaveTextContent('Dernière synchronisation réussie : il y a 3 h')
    expect(banner).toHaveTextContent('2 écritures en attente')
    expect(within(banner).getByLabelText('Écritures en attente')).toHaveTextContent('Maison/B.md')
  })

  test('says it when the memory has no bucket at all', async () => {
    stub({ 'GET /agent/memory/sync': () => json({ ...SYNC_OK, enabled: false }) })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    expect(await screen.findByText(/Aucun bucket n'est configuré/)).toBeInTheDocument()
  })

  test('relaunches a sync and shows its outcome', async () => {
    const fetchMock = stub({
      'POST /agent/memory/sync': () => json({ ...SYNC_OK, pending: 0, lastSyncAt: new Date().toISOString() }),
    })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Synchroniser' }))

    await waitFor(() => expect(calls(fetchMock, 'POST', '/agent/memory/sync')).toHaveLength(1))
    expect(await screen.findByText(/Synchronisée avec le bucket : à l’instant/)).toBeInTheDocument()
  })

  test('rebuilds the index on demand', async () => {
    const fetchMock = stub({ 'POST /agent/memory/rebuild': () => json({ files: 12, skipped: 0 }) })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    await userEvent.click(await screen.findByRole('button', { name: "Reconstruire l'index" }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Index reconstruit : 12 fichiers', { type: 'success' }))
    expect(calls(fetchMock, 'POST', '/agent/memory/rebuild')).toHaveLength(1)
  })

  test('reads the journal and keeps the night’s entries on demand', async () => {
    stub()
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Journal' }))
    const list = await screen.findByLabelText('Journal', { selector: 'ul' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)

    await userEvent.click(screen.getByLabelText('Entretiens de nuit seulement'))
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    expect(list).toHaveTextContent('entretien nocturne du 2026-10-14')
  })

  test('lists the missed recalls and removes one', async () => {
    let remaining = [MISS]
    const fetchMock = stub({
      'GET /agent/memory/misses': () => json({ misses: remaining }),
      [`DELETE /agent/memory/file?path=${encodeURIComponent(MISS.path)}`]: () => {
        remaining = []
        return new Response(null, { status: 204 })
      },
    })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Rappels manqués' }))
    expect(await screen.findByText('Un bouquet pour la fête des mères')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retirer ce cas' }))

    expect(await screen.findByText(/Aucun « tu aurais dû t'en souvenir »/)).toBeInTheDocument()
    expect(calls(fetchMock, 'DELETE', '/agent/memory/file')).toHaveLength(1)
  })

  test('exports the whole memory as a download', async () => {
    const created = vi.fn(() => 'blob:memoire')
    const revoked = vi.fn()
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: created, revokeObjectURL: revoked }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    stub({
      'GET /agent/memory/export': () =>
        new Response('zip', {
          headers: { 'Content-Disposition': 'attachment; filename="memoire-maggie-2026-10-14.zip"' },
        }),
    })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Exporter' }))

    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(created).toHaveBeenCalled()
    expect(revoked).toHaveBeenCalledWith('blob:memoire')
    click.mockRestore()
  })

  test('says so when the export fails', async () => {
    stub({ 'GET /agent/memory/export': () => json({}, 500) })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    await userEvent.click(await screen.findByRole('button', { name: 'Exporter' }))

    await waitFor(() => expect(notify).toHaveBeenCalledWith('Export impossible', { type: 'error' }))
  })

  test('says so when the memory cannot be loaded, and retries', async () => {
    let up = false
    stub({ 'GET /agent/memory/tree': () => (up ? json({ files: TREE }) : json({}, 500)) })
    render(<MemorySettings notify={notify} subscribe={subscribe} />)

    expect(await screen.findByText('Impossible de charger la mémoire.')).toBeInTheDocument()
    up = true
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findByRole('list', { name: 'Arborescence de la mémoire' })).toBeInTheDocument()
  })

  test('follows the memory live: a Mercure notice reloads the tree and the open file', async () => {
    let tree = TREE
    let fiche = FICHE
    const fetchMock = stub({
      'GET /agent/memory/tree': () => json({ files: tree }),
      [`GET /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () => json(fiche),
    })
    await openRoses()
    tree = [...TREE, { ...TREE[0], path: 'Maison/Nouveau.md', title: 'Nouveau' }]
    fiche = { ...FICHE, hash: 'h9', text: FICHE.text.replace('n\'aime pas', 'adore') }
    emit([ROSES, 'Maison/Nouveau.md'])

    await waitFor(() => expect(screen.getByLabelText('Markdown')).toHaveValue(fiche.text))
    await userEvent.click(await screen.findByRole('button', { name: /Maison/ }))
    expect(await screen.findByText('Nouveau')).toBeInTheDocument()
    expect(calls(fetchMock, 'GET', '/agent/memory/tree').length).toBeGreaterThan(1)
  })

  test('keeps the owner’s unsaved text when the file changes under them, and warns', async () => {
    let fiche = FICHE
    stub({ [`GET /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () => json(fiche) })
    await openRoses()
    await userEvent.type(screen.getByLabelText('Markdown'), ' ajout')
    const typed = (screen.getByLabelText('Markdown') as HTMLTextAreaElement).value

    fiche = { ...FICHE, hash: 'h9', text: FICHE.text + '\n- Autre\n' }
    emit(null)

    expect(await screen.findByText(/Ce fichier a changé depuis que vous l'avez ouvert/)).toBeInTheDocument()
    expect(screen.getByLabelText('Markdown')).toHaveValue(typed)
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: 'Recharger' }))
    expect(screen.getByLabelText('Markdown')).toHaveValue(fiche.text)
  })

  test('keeps the open file and the owner’s text when the refresh fails on the network', async () => {
    let down = false
    stub({
      [`GET /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () => {
        if (down) throw new TypeError('network')
        return json(FICHE)
      },
    })
    await openRoses()
    await userEvent.type(screen.getByLabelText('Markdown'), ' ajout')
    const typed = (screen.getByLabelText('Markdown') as HTMLTextAreaElement).value

    down = true
    emit([ROSES])

    await waitFor(() => expect(subscribe).toHaveBeenCalled())
    await new Promise((resolve) => setTimeout(resolve, 500))
    expect(screen.getByLabelText('Markdown')).toHaveValue(typed)
  })

  test('closes the view when the open file is gone', async () => {
    let gone = false
    stub({
      [`GET /agent/memory/file?path=${encodeURIComponent(ROSES)}`]: () =>
        gone ? json({ detail: 'unknown' }, 404) : json(FICHE),
    })
    await openRoses()

    gone = true
    emit([ROSES])

    expect(await screen.findByText('Choisissez un fichier pour le lire ou le corriger.')).toBeInTheDocument()
  })

  test('stops following the memory when the tab goes away', async () => {
    stub()
    const { unmount } = render(<MemorySettings notify={notify} subscribe={subscribe} />)
    await screen.findByRole('list', { name: 'Arborescence de la mémoire' })

    unmount()

    expect(listeners.size).toBe(0)
  })
})

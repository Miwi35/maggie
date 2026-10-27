import { useCallback, useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { MemoryFileView } from './MemoryFileView'
import { MemoryTree } from './MemoryTree'
import { ancestors } from './memoryPaths'
import {
  ago,
  downloadMemoryArchive,
  MemoryApiError,
  memoryApi,
  type MemoryFile,
  type MemoryJournalEntry,
  type MemoryMiss,
  type MemorySyncState,
  type MemoryTreeEntry,
} from './memoryApi'

const LIVE_REFRESH_DELAY_MS = 300

type View = 'files' | 'journal' | 'misses'

function SyncBanner({ sync, busy, onSync }: { sync: MemorySyncState; busy: boolean; onSync: () => void }) {
  if (!sync.enabled) {
    return <Alert severity="info">Aucun bucket n'est configuré : la mémoire reste sur le serveur de Maggie.</Alert>
  }
  const waiting = `${sync.pending} écriture${sync.pending > 1 ? 's' : ''} en attente`
  return (
    <Alert
      severity={sync.degraded ? 'error' : sync.pending > 0 ? 'warning' : 'success'}
      action={
        <Button color="inherit" size="small" disabled={busy} onClick={onSync}>
          Synchroniser
        </Button>
      }
    >
      {sync.degraded ? (
        <>
          Le bucket ne répond pas (depuis {ago(sync.degradedSince)}). Dernière synchronisation réussie :{' '}
          {ago(sync.lastSyncAt)}. {waiting}.
          {sync.lastError ? ` (${sync.lastError})` : ''}
        </>
      ) : (
        <>
          Synchronisée avec le bucket : {ago(sync.lastSyncAt)}.{sync.pending > 0 ? ` ${waiting}.` : ''}
        </>
      )}
      {sync.pendingPaths.length > 0 && (
        <Box component="ul" sx={{ m: 0, pl: 3 }} aria-label="Écritures en attente">
          {sync.pendingPaths.map((path) => (
            <li key={path}>{path}</li>
          ))}
        </Box>
      )}
      {sync.conflicts.length > 0 && (
        <Typography variant="body2">
          {sync.conflicts.length} version{sync.conflicts.length > 1 ? 's' : ''} mise
          {sync.conflicts.length > 1 ? 's' : ''} de côté dans Conflits/.
        </Typography>
      )}
    </Alert>
  )
}

/** What a `memory` notice carries: the paths that changed, or null when it does not say. */
export type MemoryNotice = string[] | null

interface MemorySettingsProps {
  notify: (message: string, options?: { type: 'error' | 'success' }) => void
  /** The page's own Mercure connection delivers the notices: a browser allows six per origin, and the page already holds them all. */
  subscribe: (listener: (paths: MemoryNotice) => void) => () => void
}

export const MemorySettings = ({ notify, subscribe }: MemorySettingsProps) => {
  const [view, setView] = useState<View>('files')
  const [files, setFiles] = useState<MemoryTreeEntry[] | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [sync, setSync] = useState<MemorySyncState | null>(null)
  const [busy, setBusy] = useState(false)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [file, setFile] = useState<MemoryFile | null>(null)
  const [highlight, setHighlight] = useState<string | null>(null)
  const [journal, setJournal] = useState<MemoryJournalEntry[] | null>(null)
  const [nightOnly, setNightOnly] = useState(false)
  const [misses, setMisses] = useState<MemoryMiss[] | null>(null)
  const selectedRef = useRef<string | null>(null)
  const viewRef = useRef<View>('files')
  selectedRef.current = file?.path ?? null
  viewRef.current = view

  const loadTree = useCallback(async () => {
    try {
      const [tree, state] = await Promise.all([memoryApi.tree(), memoryApi.sync()])
      setFiles(tree)
      setSync(state)
      setLoadError(false)
    } catch {
      setLoadError(true)
    }
  }, [])

  const loadJournal = useCallback(async () => {
    try {
      setJournal(await memoryApi.journal())
    } catch {
      notify('Journal indisponible', { type: 'error' })
    }
  }, [notify])

  const loadMisses = useCallback(async () => {
    try {
      setMisses(await memoryApi.misses())
    } catch {
      notify('Rappels manqués indisponibles', { type: 'error' })
    }
  }, [notify])

  const openFile = useCallback(
    async (path: string, heure: string | null = null) => {
      try {
        const opened = await memoryApi.open(path)
        setFile(opened)
        setHighlight(heure)
        setView('files')
        setExpanded((prev) => new Set([...prev, ...ancestors(path)]))
      } catch {
        notify('Fichier introuvable', { type: 'error' })
      }
    },
    [notify],
  )

  useEffect(() => {
    void loadTree()
  }, [loadTree])

  useEffect(() => {
    if (view === 'journal') void loadJournal()
    if (view === 'misses') void loadMisses()
  }, [view, loadJournal, loadMisses])

  // The web follows the memory live: one coalesced notification per burst of writes (Mercure `memory` stream).
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined
    const unsubscribe = subscribe((changed) => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        void loadTree()
        if (viewRef.current === 'journal') void loadJournal()
        if (viewRef.current === 'misses') void loadMisses()
        const open = selectedRef.current
        if (open && (changed === null || changed.includes(open))) {
          // Only a file that is really gone clears the view: a network blip must not cost the owner their draft.
          memoryApi
            .open(open)
            .then((fresh) => {
              if (selectedRef.current === open) setFile(fresh)
            })
            .catch((error) => {
              if (error instanceof MemoryApiError && error.status === 404 && selectedRef.current === open) setFile(null)
            })
        }
      }, LIVE_REFRESH_DELAY_MS)
    })
    return () => {
      clearTimeout(timer)
      unsubscribe()
    }
  }, [subscribe, loadTree, loadJournal, loadMisses])

  const runSync = async () => {
    setBusy(true)
    try {
      setSync(await memoryApi.runSync())
      setFiles(await memoryApi.tree())
    } catch {
      notify('Synchronisation impossible', { type: 'error' })
    } finally {
      setBusy(false)
    }
  }

  const rebuild = async () => {
    setBusy(true)
    try {
      const report = await memoryApi.rebuild()
      notify(`Index reconstruit : ${report.files} fichiers`, { type: 'success' })
      await loadTree()
    } catch {
      notify("Reconstruction de l'index impossible", { type: 'error' })
    } finally {
      setBusy(false)
    }
  }

  const exportAll = async () => {
    try {
      await downloadMemoryArchive()
    } catch {
      notify("Export impossible", { type: 'error' })
    }
  }

  const removeMiss = async (miss: MemoryMiss) => {
    try {
      await memoryApi.remove(miss.path)
      await loadMisses()
    } catch {
      notify('Suppression impossible', { type: 'error' })
    }
  }

  if (loadError && !files) {
    return (
      <Alert
        severity="error"
        action={
          <Button color="inherit" size="small" onClick={() => void loadTree()}>
            Réessayer
          </Button>
        }
      >
        Impossible de charger la mémoire.
      </Alert>
    )
  }
  if (!files || !sync) {
    return (
      <Box display="flex" justifyContent="center" p={3}>
        <CircularProgress />
      </Box>
    )
  }

  const shownJournal = (journal ?? []).filter((entry) => !nightOnly || entry.night)

  return (
    <Stack spacing={2}>
      <SyncBanner sync={sync} busy={busy} onSync={runSync} />
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <ToggleButtonGroup size="small" exclusive value={view} onChange={(_, next: View | null) => next && setView(next)}>
          <ToggleButton value="files">Fichiers</ToggleButton>
          <ToggleButton value="journal">Journal</ToggleButton>
          <ToggleButton value="misses">Rappels manqués</ToggleButton>
        </ToggleButtonGroup>
        <Box sx={{ flex: 1 }} />
        <Button size="small" disabled={busy} onClick={rebuild}>
          Reconstruire l'index
        </Button>
        <Button size="small" variant="outlined" onClick={exportAll}>
          Exporter
        </Button>
      </Stack>

      {view === 'files' && (
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="flex-start">
          <Box sx={{ width: { xs: '100%', md: 300 }, flexShrink: 0, maxHeight: 640, overflow: 'auto' }}>
            {files.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                La mémoire est vide pour le moment.
              </Typography>
            ) : (
              <MemoryTree
                files={files}
                selected={file?.path ?? null}
                expanded={expanded}
                onToggle={(folder) =>
                  setExpanded((prev) => {
                    const next = new Set(prev)
                    if (!next.delete(folder)) next.add(folder)
                    return next
                  })
                }
                onSelect={(path) => void openFile(path)}
              />
            )}
          </Box>
          <Box sx={{ flex: 1, minWidth: 0, width: '100%' }}>
            {file ? (
              <MemoryFileView
                file={file}
                highlight={highlight}
                onOpen={(path, heure) => void openFile(path, heure)}
                onChanged={(saved) => {
                  setFile(saved)
                  notify('Fichier enregistré', { type: 'success' })
                  void loadTree()
                }}
                onRefreshed={setFile}
                onMoved={(moved) => {
                  setFile(moved)
                  setExpanded((prev) => new Set([...prev, ...ancestors(moved.path)]))
                  void loadTree()
                }}
                onDeleted={() => {
                  setFile(null)
                  void loadTree()
                }}
                onError={(message) => notify(message, { type: 'error' })}
              />
            ) : (
              <Typography variant="body2" color="text.secondary">
                Choisissez un fichier pour le lire ou le corriger.
              </Typography>
            )}
          </Box>
        </Stack>
      )}

      {view === 'journal' && (
        <Stack spacing={1}>
          <FormControlLabel
            control={<Switch checked={nightOnly} onChange={(event) => setNightOnly(event.target.checked)} />}
            label="Entretiens de nuit seulement"
          />
          {journal === null ? (
            <CircularProgress size={20} />
          ) : shownJournal.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Rien dans le journal.
            </Typography>
          ) : (
            <Box component="ul" sx={{ m: 0, pl: 0, listStyle: 'none' }} aria-label="Journal">
              {shownJournal.map((entry, index) => (
                <Box component="li" key={index} sx={{ py: 0.5 }}>
                  <Typography variant="caption" color="text.secondary" sx={{ mr: 1 }}>
                    {entry.at}
                  </Typography>
                  <Typography variant="body2" component="span">
                    {entry.text}
                  </Typography>
                </Box>
              ))}
            </Box>
          )}
        </Stack>
      )}

      {view === 'misses' && (
        <Stack spacing={1}>
          {misses === null ? (
            <CircularProgress size={20} />
          ) : misses.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Aucun « tu aurais dû t'en souvenir » en cours.
            </Typography>
          ) : (
            misses.map((miss) => (
              <Box key={miss.path} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1.5 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography variant="subtitle2" sx={{ flex: 1 }}>
                    {miss.demande}
                  </Typography>
                  <Chip size="small" label={miss.statut} color={miss.statut === 'ouvert' ? 'warning' : 'default'} />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {miss.cause} · enregistré {miss.enregistre}
                </Typography>
                <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
                  {miss.attendue && (
                    <Button size="small" onClick={() => void openFile(miss.attendue)}>
                      Ouvrir la fiche attendue
                    </Button>
                  )}
                  <Button size="small" color="error" onClick={() => void removeMiss(miss)}>
                    Retirer ce cas
                  </Button>
                </Stack>
              </Box>
            ))
          )}
        </Stack>
      )}
    </Stack>
  )
}

import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { MemoryApiError, memoryApi, type MemoryClaim, type MemoryFile } from './memoryApi'

const PROVENANCE_COLORS: Record<string, 'success' | 'info' | 'warning' | 'default'> = {
  dit: 'success',
  observé: 'info',
  déduit: 'warning',
}

interface Props {
  file: MemoryFile
  /** The minute of a conversation to show, when the file was opened from a fiche's source. */
  highlight: string | null
  onOpen: (path: string, heure: string | null) => void
  onChanged: (file: MemoryFile) => void
  /** The file as it is now, fetched after a save was refused because it moved on. */
  onRefreshed: (file: MemoryFile) => void
  onMoved: (file: MemoryFile) => void
  onDeleted: (path: string) => void
  onError: (message: string) => void
}

function ClaimRow({ claim, onOpen }: { claim: MemoryClaim; onOpen: Props['onOpen'] }) {
  return (
    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
      <Chip size="small" color={PROVENANCE_COLORS[claim.provenance] ?? 'default'} label={claim.provenance} />
      <Typography variant="body2" sx={{ flex: 1, minWidth: 200 }}>
        {claim.texte}
      </Typography>
      {claim.sourcePath && claim.sourceExists ? (
        <Button size="small" onClick={() => onOpen(claim.sourcePath as string, claim.heure)}>
          {claim.sourceKind === 'conversation' ? 'Voir la conversation' : 'Voir la source'}
          {claim.heure ? ` · ${claim.heure}` : ''}
        </Button>
      ) : (
        <Typography variant="caption" color="text.secondary">
          {claim.sourceKind === 'app' ? `Source : ${claim.source}` : `Source introuvable : ${claim.source}`}
        </Typography>
      )}
    </Stack>
  )
}

/** A conversation, one block per `## date heure — rôle`, the cited minute marked and scrolled into view. */
function Conversation({ text, highlight }: { text: string; highlight: string | null }) {
  const cited = useRef<HTMLDivElement | null>(null)
  const blocks = text.split(/^(?=## )/m)
  const citedIndex = highlight ? blocks.findIndex((b) => b.startsWith('## ') && b.split('\n', 1)[0].includes(highlight)) : -1

  useEffect(() => {
    cited.current?.scrollIntoView?.({ block: 'center' })
  }, [citedIndex, text])

  return (
    <Box>
      {highlight && citedIndex < 0 && <Alert severity="info">Le passage de {highlight} n'est plus dans ce fichier.</Alert>}
      {blocks.map((block, index) => (
        <Box
          key={index}
          ref={index === citedIndex ? cited : undefined}
          data-testid={index === citedIndex ? 'cited-passage' : undefined}
          sx={{
            whiteSpace: 'pre-wrap',
            fontFamily: 'monospace',
            fontSize: 13,
            p: 1,
            ...(index === citedIndex ? { bgcolor: 'warning.light', borderLeft: 4, borderColor: 'warning.main' } : {}),
          }}
        >
          {block}
        </Box>
      ))}
    </Box>
  )
}

export function MemoryFileView({ file, highlight, onOpen, onChanged, onRefreshed, onMoved, onDeleted, onError }: Props) {
  // The version the draft started from: a save is refused if the file moved on since.
  const [base, setBase] = useState({ path: file.path, hash: file.hash, text: file.text })
  const [draft, setDraft] = useState(file.text)
  const [saving, setSaving] = useState(false)
  const [moving, setMoving] = useState(false)
  const [target, setTarget] = useState(file.path)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [conflict, setConflict] = useState(false)

  useEffect(() => {
    setConflict(false)
    if (file.path !== base.path || draft === base.text) {
      setBase({ path: file.path, hash: file.hash, text: file.text })
      setDraft(file.text)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- adopt a new version only when the file prop changes
  }, [file.path, file.hash])

  const dirty = draft !== base.text
  const changedElsewhere = file.path === base.path && file.hash !== base.hash

  const reload = () => {
    setBase({ path: file.path, hash: file.hash, text: file.text })
    setDraft(file.text)
    setConflict(false)
  }

  const fail = (error: unknown, fallback: string) => {
    if (error instanceof MemoryApiError && error.status === 409) {
      setConflict(true)
      onError('Le fichier a changé depuis son ouverture : rien n’a été écrasé')
    } else if (error instanceof MemoryApiError && error.status === 400 && error.message) {
      onError(error.message)
    } else {
      onError(fallback)
    }
  }

  const save = async () => {
    setSaving(true)
    try {
      const saved = await memoryApi.save(file.path, draft, base.hash)
      setBase({ path: saved.path, hash: saved.hash, text: saved.text })
      setDraft(saved.text)
      onChanged(saved)
    } catch (error) {
      fail(error, 'Enregistrement impossible')
      if (error instanceof MemoryApiError && error.status === 409) {
        await memoryApi.open(file.path).then(onRefreshed, () => {})
      }
    } finally {
      setSaving(false)
    }
  }

  const move = async () => {
    try {
      const moved = await memoryApi.move(file.path, target.trim())
      setMoving(false)
      onMoved(moved)
    } catch (error) {
      fail(error, 'Déplacement impossible')
    }
  }

  const remove = async () => {
    try {
      await memoryApi.remove(file.path)
      setConfirmDelete(false)
      onDeleted(file.path)
    } catch (error) {
      setConfirmDelete(false)
      fail(error, 'Suppression impossible')
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
        <Typography variant="h6" sx={{ flex: 1, wordBreak: 'break-word' }}>
          {file.path}
        </Typography>
        {file.editable && (
          <Button
            onClick={() => {
              setTarget(file.path)
              setMoving(true)
            }}
          >
            Renommer / déplacer
          </Button>
        )}
        {file.deletable && (
          <Button color="error" onClick={() => setConfirmDelete(true)}>
            Supprimer
          </Button>
        )}
      </Stack>

      {file.unreadable && <Alert severity="warning">La carte de ce fichier n'est pas lisible : {file.unreadable}</Alert>}

      {file.carte && file.kind === 'fiche' && (
        <Box>
          <Typography variant="subtitle2">Carte</Typography>
          <Typography variant="body2">{file.carte.resume}</Typography>
          <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
            {file.carte.personnes.map((p) => (
              <Chip key={`p:${p}`} size="small" variant="outlined" label={p} />
            ))}
            {file.carte.moments.map((m) => (
              <Chip key={`m:${m}`} size="small" variant="outlined" label={m} />
            ))}
            {file.carte.evoque.map((e) => (
              <Chip key={`e:${e}`} size="small" label={e} />
            ))}
          </Stack>
        </Box>
      )}

      {file.kind === 'fiche' && file.known.length > 0 && (
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>
            Ce qu'on sait — et d'où ça vient
          </Typography>
          <Stack spacing={1} aria-label="Affirmations">
            {file.known.map((claim, index) => (
              <ClaimRow key={index} claim={claim} onOpen={onOpen} />
            ))}
          </Stack>
        </Box>
      )}

      {file.kind === 'fiche' && file.replaced.length > 0 && (
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>
            Ce qui n'est plus vrai
          </Typography>
          <Stack spacing={1}>
            {file.replaced.map((claim, index) => (
              <Typography key={index} variant="body2" color="text.secondary">
                <s>{claim.texte}</s> — {claim.provenance}
                {claim.replacedOn ? `, remplacé le ${claim.replacedOn}` : ''}
                {claim.motif ? ` : ${claim.motif}` : ''}
              </Typography>
            ))}
          </Stack>
        </Box>
      )}

      {file.kind === 'conversation' ? (
        <Conversation text={file.text} highlight={highlight} />
      ) : file.editable ? (
        <>
          {(changedElsewhere || conflict) && (
            <Alert
              severity="warning"
              action={
                <Button color="inherit" size="small" onClick={reload}>
                  Recharger
                </Button>
              }
            >
              Ce fichier a changé depuis que vous l'avez ouvert (Maggie ou la synchronisation). Votre version ne sera pas
              enregistrée tant que vous ne rechargez pas.
            </Alert>
          )}
          <TextField
            label="Markdown"
            multiline
            minRows={12}
            fullWidth
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            slotProps={{ htmlInput: { style: { fontFamily: 'monospace', fontSize: 13 } } }}
          />
          <Box>
            <Button variant="contained" disabled={!dirty || saving || changedElsewhere || conflict} onClick={save}>
              Enregistrer
            </Button>
          </Box>
        </>
      ) : (
        <Box sx={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: 13 }}>
          {file.kind === 'generated' && (
            <Alert severity="info" sx={{ mb: 1 }}>
              Fichier tenu par Maggie : il se met à jour tout seul.
            </Alert>
          )}
          {file.text}
        </Box>
      )}

      <Dialog open={moving} onClose={() => setMoving(false)} fullWidth>
        <DialogTitle>Renommer / déplacer</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            margin="dense"
            label="Nouveau chemin"
            value={target}
            onChange={(event) => setTarget(event.target.value)}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setMoving(false)}>Annuler</Button>
          <Button variant="contained" disabled={!target.trim() || target === file.path} onClick={move}>
            Déplacer
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={confirmDelete} onClose={() => setConfirmDelete(false)}>
        <DialogTitle>Supprimer ce fichier ?</DialogTitle>
        <DialogContent>
          <Typography variant="body2">
            {file.path} sera supprimé de la mémoire, de l'index et du bucket. La suppression est notée dans le journal.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmDelete(false)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={remove}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}

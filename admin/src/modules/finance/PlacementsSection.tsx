import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent, MouseEvent } from 'react'
import { useNotify } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import { useMercure } from '../../hooks/useMercure'
import { bareId } from './envelopeTree'
import { PlacementCurve } from './PlacementCurve'
import { PlacementDialog } from './PlacementDialog'
import {
  byName,
  createPlacement,
  deletePlacement,
  depositsLabel,
  fetchPlacements,
  fetchSimulation,
  formatEuros,
  formatRate,
  isWholePlacement,
  patchPlacement,
  PLACEMENT_TOPIC,
  toPlacement,
  updatePlacement,
  yearsLabel,
} from './placementsApi'
import type { Placement, PlacementInput, Simulation } from './placementsApi'

const PLACEMENT_TOPICS = [PLACEMENT_TOPIC]

const failureText = (error: unknown, fallback: string) => (error instanceof Error && error.message ? error.message : fallback)

const numeric = { fontVariantNumeric: 'tabular-nums' } as const

const Figure = ({ label, cents }: { label: string; cents: number }) => (
  <Box>
    <Typography variant="caption" color="text.secondary">
      {label}
    </Typography>
    <Typography variant="h6" sx={{ fontWeight: 300, ...numeric }}>
      {formatEuros(cents)}
    </Typography>
  </Box>
)

/** The curve of the selected placement, read from its simulation whenever one of its figures changes. */
const SelectedPlacement = ({ placement }: { placement: Placement }) => {
  // The figures are the simulation's own: a change of any of them reads it again.
  const { id, final, paid, years } = placement
  const key = `${id}:${final}:${paid}:${years}`
  const [answer, setAnswer] = useState<{ key: string; simulation?: Simulation; failed?: boolean } | null>(null)
  const current = answer?.key === key ? answer : null
  const simulation = current?.simulation ?? null
  const failed = current?.failed === true

  useEffect(() => {
    let live = true
    fetchSimulation(id)
      .then((read) => {
        if (live) setAnswer({ key, simulation: read })
      })
      .catch(() => {
        if (live) setAnswer({ key, failed: true })
      })

    return () => {
      live = false
    }
  }, [id, key])

  return (
    <Stack spacing={1.5} sx={{ mt: 2 }} aria-label={`Simulation de ${placement.name}`} component="section">
      <Typography variant="overline" color="text.secondary">
        {placement.name} · {yearsLabel(placement.years)}
      </Typography>
      {failed && <Alert severity="warning">La courbe de ce placement n'a pas pu être calculée.</Alert>}
      {!failed && !simulation && <Skeleton variant="rectangular" height={160} data-testid="placement-curve-loading" />}
      {!failed && simulation && <PlacementCurve name={placement.name} curve={simulation.curve} years={placement.years} />}
      <Stack direction="row" spacing={3} sx={{ flexWrap: 'wrap' }}>
        <Figure label={`Capital dans ${yearsLabel(placement.years)}`} cents={placement.final} />
        <Figure label="Dont versements" cents={placement.paid} />
        <Figure label="Dont intérêts composés" cents={placement.interest} />
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Retiré à 4 % par an, ce capital donnerait environ {formatEuros(placement.rent)} par mois de rente (simulation).
      </Typography>
    </Stack>
  )
}

/**
 * Finance › Planification › Indépendance › Placements (MAG-555): the simulated
 * placements, what each one would be worth at its end and the rente at 4 % it
 * would give, the curve of the one selected, created, changed and deleted here.
 */
export const PlacementsSection = () => {
  const notify = useNotify()
  const [placements, setPlacements] = useState<Placement[]>([])
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [editing, setEditing] = useState<Placement | 'new' | null>(null)
  const [toDelete, setToDelete] = useState<Placement | null>(null)
  const current = useRef<Placement[]>(placements)
  useEffect(() => {
    current.current = placements
  }, [placements])

  const reload = useCallback(async () => {
    try {
      setPlacements(await fetchPlacements())
      setStatus('ready')
    } catch {
      setStatus((previous) => (previous === 'ready' ? previous : 'failed'))
    }
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  // The payload leaves with the write, before the index has it: applied, not read back.
  useMercure(
    PLACEMENT_TOPICS,
    useCallback(
      (data?: string) => {
        let payload: Record<string, unknown>
        try {
          payload = JSON.parse(data ?? '')
        } catch {
          reload()
          return
        }
        const iri = payload?.['@id']
        if (typeof iri !== 'string') {
          reload()
          return
        }
        const id = bareId(iri)
        if (payload.deleted) {
          setPlacements((previous) => previous.filter((placement) => placement.id !== id))
          return
        }
        if (current.current.some((placement) => placement.id === id)) {
          setPlacements((previous) => byName(previous.map((placement) => (placement.id === id ? patchPlacement(placement, payload) : placement))))
          return
        }
        if (isWholePlacement(payload)) {
          setPlacements((previous) =>
            previous.some((placement) => placement.id === id) ? previous : byName([...previous, toPlacement({ ...payload, id })]),
          )
          return
        }
        reload()
      },
      [reload],
    ),
  )

  const selected = placements.find((placement) => placement.id === selectedId) ?? placements[0]
  const monthly = placements.reduce((sum, placement) => sum + placement.monthlyDeposit, 0)

  const save = async (input: PlacementInput) => {
    if (editing === 'new') {
      const created = await createPlacement(input)
      setPlacements((previous) =>
        byName(previous.some((placement) => placement.id === created.id) ? previous.map((placement) => (placement.id === created.id ? created : placement)) : [...previous, created]),
      )
      setSelectedId(created.id)
    } else if (editing) {
      const updated = await updatePlacement(editing.id, input)
      setPlacements((previous) => byName(previous.map((placement) => (placement.id === updated.id ? updated : placement))))
    }
    setEditing(null)
  }

  const remove = async (placement: Placement) => {
    setToDelete(null)
    setPlacements((list) => list.filter((candidate) => candidate.id !== placement.id))
    try {
      await deletePlacement(placement.id)
    } catch (error) {
      // Put back only the one refused: what Mercure brought meanwhile stays.
      setPlacements((list) => (list.some((candidate) => candidate.id === placement.id) ? list : byName([...list, placement])))
      notify(failureText(error, `Le placement « ${placement.name} » n'a pas été supprimé.`), { type: 'error' })
    }
  }

  const onRowKeyDown = (event: KeyboardEvent, placement: Placement) => {
    if (event.target !== event.currentTarget) return
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      setSelectedId(placement.id)
    }
  }

  const action = (event: MouseEvent, run: () => void) => {
    event.stopPropagation()
    run()
  }

  return (
    <Card component="section" aria-label="Placements" sx={{ mt: 3 }}>
      <CardContent>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2, mb: 1, flexWrap: 'wrap' }}>
          <Typography variant="h6" component="h2">
            Placements
          </Typography>
          <Stack direction="row" sx={{ alignItems: 'center', gap: 2 }}>
            {status === 'ready' && placements.length > 0 && (
              <Typography variant="body2" color="text.secondary" sx={numeric} data-testid="placements-monthly">
                {formatEuros(monthly)} / mois de versements
              </Typography>
            )}
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing('new')}>
              Placement
            </Button>
          </Stack>
        </Stack>

        {status === 'loading' && <Skeleton data-testid="placements-loading" height={64} />}
        {status === 'failed' && (
          <Alert
            severity="error"
            action={
              <Button color="inherit" size="small" onClick={() => reload()}>
                Réessayer
              </Button>
            }
          >
            Les placements n'ont pas pu être chargés.
          </Alert>
        )}
        {status === 'ready' && placements.length === 0 && (
          <Typography color="text.secondary">
            Aucun placement pour l'instant. Simulez un PEA, une assurance-vie ou un livret pour voir ce qu'il donnerait.
          </Typography>
        )}
        {status === 'ready' && placements.length > 0 && (
          <>
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small" aria-label="Placements simulés" sx={numeric}>
                <TableHead>
                  <TableRow>
                    <TableCell>Placement</TableCell>
                    <TableCell>Versements</TableCell>
                    <TableCell>Rendement</TableCell>
                    <TableCell>Durée</TableCell>
                    <TableCell align="right">Capital final</TableCell>
                    <TableCell align="right">Rente à 4 %</TableCell>
                    <TableCell padding="checkbox">
                      <Box component="span" sx={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>
                        Actions
                      </Box>
                    </TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {placements.map((placement) => {
                    const isSelected = placement.id === selected?.id

                    return (
                      <TableRow
                        key={placement.id}
                        hover
                        selected={isSelected}
                        aria-selected={isSelected}
                        tabIndex={0}
                        data-testid="placement-row"
                        data-placement={placement.name}
                        onClick={() => setSelectedId(placement.id)}
                        onKeyDown={(event) => onRowKeyDown(event, placement)}
                        sx={{ cursor: 'pointer' }}
                      >
                        <TableCell>{placement.name}</TableCell>
                        <TableCell>{depositsLabel(placement)}</TableCell>
                        <TableCell>{formatRate(placement.annualRate)}</TableCell>
                        <TableCell>{yearsLabel(placement.years)}</TableCell>
                        <TableCell align="right" data-testid="placement-final">
                          {formatEuros(placement.final)}
                        </TableCell>
                        <TableCell align="right" data-testid="placement-rent">
                          {formatEuros(placement.rent)}
                        </TableCell>
                        <TableCell padding="checkbox" sx={{ whiteSpace: 'nowrap' }}>
                          <IconButton
                            size="small"
                            aria-label={`Modifier ${placement.name}`}
                            onClick={(event) => action(event, () => setEditing(placement))}
                          >
                            <EditOutlinedIcon fontSize="small" />
                          </IconButton>
                          <IconButton
                            size="small"
                            aria-label={`Supprimer ${placement.name}`}
                            onClick={(event) => action(event, () => setToDelete(placement))}
                          >
                            <DeleteOutlineIcon fontSize="small" />
                          </IconButton>
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </Box>
            {selected && <SelectedPlacement placement={selected} />}
          </>
        )}
      </CardContent>

      {editing !== null && (
        <PlacementDialog placement={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} onSubmit={save} />
      )}

      <Dialog open={toDelete !== null} onClose={() => setToDelete(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Supprimer le placement « {toDelete?.name} » ?</DialogTitle>
        <DialogContent>
          <Typography variant="body2">C'est une simulation : aucun argent ne bouge, seule la simulation disparaît.</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setToDelete(null)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={() => toDelete && remove(toDelete)}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  )
}

import { useNotify, useUpdate } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { BODY_MEASUREMENTS, measurementUlid } from './bodyMeasurementsApi'
import type { BodyMeasurement } from './bodyMeasurementsApi'
import { formatDay, formatKg, formatPercent } from './suiviSeries'

interface SuiviWeighingsCardProps {
  /** The weighings of the period, newest first, the excluded ones included. */
  weighings: BodyMeasurement[]
  loaded: boolean
  onAdd: () => void
  onEdit: (measurement: BodyMeasurement) => void
}

const head = { textAlign: 'left', py: 1, px: 1.25, fontSize: 12, fontWeight: 500 } as const
const cell = { py: 1, px: 1.25, fontSize: 13 } as const
const right = { textAlign: 'right' } as const
const hidden = { position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap' } as const

/**
 * « Pesées »: Date · Poids · Masse grasse of the period, newest first. An
 * excluded weighing stays, dimmed and struck through, with « Réintégrer ».
 */
export const SuiviWeighingsCard = ({ weighings, loaded, onAdd, onEdit }: SuiviWeighingsCardProps) => {
  const notify = useNotify()
  const [update] = useUpdate()

  const setExcluded = (measurement: BodyMeasurement, excluded: boolean) =>
    update(
      BODY_MEASUREMENTS,
      { id: measurement.id, data: { excluded }, previousData: measurement },
      {
        onError: () =>
          notify(excluded ? 'Erreur : la pesée n’a pas été écartée.' : 'Erreur : la pesée n’a pas été réintégrée.', { type: 'error' }),
      },
    )

  return (
    <Box
      component="section"
      aria-label="Pesées"
      sx={{ borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0, minHeight: 0 }}
    >
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 15, fontWeight: 500 }}>
        <span>Pesées</span>
        <Button size="small" onClick={onAdd} sx={(theme) => ({ fontSize: 13, color: theme.palette.module.sport })}>
          Ajouter une pesée
        </Button>
      </Box>
      <Box sx={{ overflowY: 'auto', minHeight: 0, flexGrow: 1 }}>
        <Table size="small" sx={{ borderCollapse: 'collapse', fontVariantNumeric: 'tabular-nums' }}>
          <TableHead>
            <TableRow>
              <TableCell sx={(theme) => ({ ...head, color: theme.palette.veilleuse.caption })}>Date</TableCell>
              <TableCell sx={(theme) => ({ ...head, ...right, color: theme.palette.veilleuse.caption })}>Poids</TableCell>
              <TableCell sx={(theme) => ({ ...head, ...right, color: theme.palette.veilleuse.caption })}>Masse grasse</TableCell>
              <TableCell sx={head}>
                <Box component="span" sx={hidden}>
                  Actions
                </Box>
              </TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {weighings.map((measurement) => {
              const date = formatDay(new Date(measurement.measuredAt))
              const struck = measurement.excluded ? { textDecoration: 'line-through' } : {}

              return (
                <TableRow
                  key={measurement.id}
                  data-testid={`weighing-row-${measurementUlid(measurement.id)}`}
                  data-excluded={measurement.excluded ? 'true' : undefined}
                  sx={{ opacity: measurement.excluded ? 0.5 : 1 }}
                >
                  <TableCell sx={{ ...cell, ...struck }}>{date}</TableCell>
                  <TableCell sx={{ ...cell, ...right, ...struck }}>{formatKg(measurement.weightKg ?? null)}</TableCell>
                  <TableCell sx={{ ...cell, ...right, ...struck, color: 'text.secondary' }}>{formatPercent(measurement.bodyFatPercent ?? null)}</TableCell>
                  <TableCell sx={{ ...cell, ...right, whiteSpace: 'nowrap' }}>
                    <Button size="small" aria-label={`Modifier la pesée du ${date}`} onClick={() => onEdit(measurement)} sx={{ minWidth: 0, fontSize: 12 }}>
                      Modifier
                    </Button>
                    <Button
                      size="small"
                      aria-label={`${measurement.excluded ? 'Réintégrer' : 'Écarter'} la pesée du ${date}`}
                      onClick={() => setExcluded(measurement, !measurement.excluded)}
                      sx={{ minWidth: 0, fontSize: 12 }}
                    >
                      {measurement.excluded ? 'Réintégrer' : 'Écarter'}
                    </Button>
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
        {loaded && weighings.length === 0 && (
          <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center', fontSize: 13 }}>
            Aucune pesée sur la période
          </Typography>
        )}
      </Box>
    </Box>
  )
}

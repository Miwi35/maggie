import { useState } from 'react'
import { useGetList, useListContext } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { PeriodDialog } from './PeriodDialog'
import { formatMoment, formatRepeat, PERIODS_TOPICS } from './periodsApi'
import type { Period } from './periodsApi'

const PERIOD_LIVE: LiveConfig = {
  topics: PERIODS_TOPICS,
  required: ['name', 'allDay'],
  fallback: () => ({ agenda: '' }),
  accept: (row) => row.id.startsWith('/api/periods/'),
}

interface AgendaColor {
  id: string
  name: string
  color?: string | null
}

const startOf = (period: Period) => period.startAt ?? period.startDate ?? ''

const NameBar = ({ color }: { color?: string | null }) => (
  <Box
    aria-hidden
    sx={{
      width: 6,
      height: 24,
      borderRadius: 1,
      flexShrink: 0,
      backgroundColor: color ?? 'divider',
      backgroundImage:
        'repeating-linear-gradient(135deg, rgba(255,255,255,0.45) 0 2px, transparent 2px 5px)',
    }}
  />
)

const PeriodsTable = ({ onOpen }: { onOpen: (period: Period) => void }) => {
  const { data, isPending, error } = useListContext<Period>()
  const { data: agendas } = useGetList<AgendaColor>('agendas', {
    pagination: { page: 1, perPage: 50 },
    sort: { field: 'name', order: 'ASC' },
    filter: {},
  })
  const agendaOf = (period: Period) => agendas?.find((a) => String(a.id) === period.agenda)

  if (isPending) return <LinearProgress />
  if (error) return <Typography color="error">Erreur : les périodes n'ont pas pu être chargées.</Typography>

  const periods = [...(data ?? [])].sort((a, b) => startOf(a).localeCompare(startOf(b)))
  if (periods.length === 0) return <Typography color="text.secondary">Aucune période pour l'instant</Typography>

  return (
    <Table data-testid="periods-table">
      <TableHead>
        <TableRow>
          {['Nom', 'Début', 'Fin', 'Répétition', 'Agenda'].map((label) => (
            <TableCell key={label}>{label}</TableCell>
          ))}
        </TableRow>
      </TableHead>
      <TableBody>
        {periods.map((period) => {
          const agenda = agendaOf(period)

          return (
            <TableRow key={period.id} hover data-testid="period-row" data-period={period.name} onClick={() => onOpen(period)} sx={{ cursor: 'pointer' }}>
              <TableCell>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
                  <NameBar color={agenda?.color} />
                  {period.name}
                </Box>
              </TableCell>
              <TableCell>{formatMoment(period, 'start')}</TableCell>
              <TableCell>{formatMoment(period, 'end')}</TableCell>
              <TableCell>{formatRepeat(period.rrule)}</TableCell>
              <TableCell>{agenda?.name ?? ''}</TableCell>
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  )
}

export const PeriodList = () => {
  const [dialog, setDialog] = useState<{ open: boolean; period: Period | null }>({ open: false, period: null })

  return (
    <Box data-testid="periods-page" sx={{ py: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="h5" component="h1" sx={{ fontWeight: 600 }}>
          Périodes
        </Typography>
        <Button variant="contained" onClick={() => setDialog({ open: true, period: null })}>
          Nouvelle période
        </Button>
      </Box>
      <LiveList live={PERIOD_LIVE} actions={false} pagination={false} empty={false} title={false} perPage={200} sort={{ field: 'startAt', order: 'ASC' }}>
        <PeriodsTable onOpen={(period) => setDialog({ open: true, period })} />
      </LiveList>
      <PeriodDialog open={dialog.open} period={dialog.period} onClose={() => setDialog((d) => ({ ...d, open: false }))} />
    </Box>
  )
}

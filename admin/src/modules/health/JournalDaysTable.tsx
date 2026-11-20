import type { MouseEvent } from 'react'
import Box from '@mui/material/Box'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import type { HealthDay } from './healthDaysApi'
import { formatBalanceFigure, formatMinutes, formatScore, formatSteps, formatWeight, journalShortDay, notePreview } from './healthJournal'
import { cardSx } from './nutritionBalance'
import type { Loaded } from './nutritionApi'

interface JournalDaysTableProps {
  state: Loaded<HealthDay[]>
  today: string
  selected: string
  onSelect: (day: string) => void
}

const HEADERS = ['Date', 'Moral', 'Fatigue', 'Poids', 'Pas', 'Bilan kcal', 'Sport', 'Note'] as const

const NUMERIC = new Set<string>(['Moral', 'Fatigue', 'Poids', 'Pas', 'Bilan kcal'])

const message = { fontSize: 13, color: 'text.secondary' } as const

/** « Jours »: the days of the period the journal holds, newest first; a click chooses the day. */
export const JournalDaysTable = ({ state, selected, onSelect }: JournalDaysTableProps) => {
  const rows = state.status === 'ready' ? [...state.data].reverse() : []

  return (
    <Box component="section" aria-label="Jours" sx={{ ...cardSx, overflowX: 'auto' }}>
      {state.status === 'loading' && <Typography sx={message}>Chargement…</Typography>}
      {state.status === 'error' && <Typography sx={message}>Erreur : le journal n’a pas pu être chargé.</Typography>}
      {state.status === 'ready' && rows.length === 0 && <Typography sx={message}>Aucun jour noté sur la période.</Typography>}
      {rows.length > 0 && (
        <Table size="small">
          <TableHead>
            <TableRow>
              {HEADERS.map((header) => (
                <TableCell key={header} align={NUMERIC.has(header) ? 'right' : 'left'}>
                  {header}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((day) => (
              <TableRow
                key={day.date}
                hover
                selected={day.date === selected}
                data-testid={`journal-row-${day.date}`}
                onClick={() => onSelect(day.date)}
                sx={{ cursor: 'pointer' }}
              >
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  <Box
                    component="button"
                    type="button"
                    aria-pressed={day.date === selected}
                    onClick={(event: MouseEvent) => {
                      event.stopPropagation()
                      onSelect(day.date)
                    }}
                    sx={{ appearance: 'none', border: 0, m: 0, p: 0, bgcolor: 'transparent', font: 'inherit', color: 'inherit', cursor: 'pointer' }}
                  >
                    {journalShortDay(day.date)}
                  </Box>
                </TableCell>
                <TableCell align="right">{formatScore(day.mood)}</TableCell>
                <TableCell align="right">{formatScore(day.fatigue)}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {formatWeight(day.weightKg)}
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap', color: 'text.secondary' }}>
                  {formatSteps(day.steps)}
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {formatBalanceFigure(day.balanceKcal)}
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap', color: 'text.secondary' }}>{formatMinutes(day.sportMinutes)}</TableCell>
                <TableCell sx={{ color: 'text.secondary', maxWidth: 220, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {notePreview(day.note)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Box>
  )
}

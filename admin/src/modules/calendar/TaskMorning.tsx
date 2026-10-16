import { useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import TimerOutlinedIcon from '@mui/icons-material/TimerOutlined'
import { REASON_LABELS, fetchDayProposal, formatMinutes, saveDayPlan, swapProposal } from './dayPlan'
import type { DayPlan, DayProposal, ProposalEntry } from './dayPlan'

interface TaskMorningProps {
  /** The local day prepared, `YYYY-MM-DD`. */
  date: string
  /** « Plus tard »: leaves the day as it is. */
  onClose: () => void
  /** « C'est parti »: the day as the API answered the write. */
  onConfirmed: (day: DayPlan) => void
}

const Reason = ({ entry }: { entry: ProposalEntry }) => (
  <Chip size="small" variant="outlined" label={REASON_LABELS[entry.reason]} />
)

const Minutes = ({ value }: { value: number }) => (
  <Typography
    component="span"
    variant="caption"
    color="text.secondary"
    sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flexShrink: 0 }}
  >
    <TimerOutlinedIcon aria-hidden fontSize="inherit" />
    {formatMinutes(value)}
  </Typography>
)

/** The proposed time against the day's free time: past the bar, the day is too full. */
const TimeBar = ({ total, free }: { total: number; free: number }) => {
  const value = free > 0 ? Math.min(100, Math.round((total / free) * 100)) : 100

  return (
    <Box sx={{ mb: 2 }}>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
        ≈ {formatMinutes(total)} · {formatMinutes(free)} libres
      </Typography>
      <LinearProgress
        variant="determinate"
        value={value}
        aria-label="Temps proposé sur le temps libre"
        color={total > free ? 'warning' : 'primary'}
      />
    </Box>
  )
}

/**
 * « Ta journée » (MAG-498): the morning's five tasks, proposed by a simple rule
 * and written nowhere until « C'est parti ». A swap takes the next task of the
 * reserve in the client; the old one goes to the end of the reserve.
 */
export const TaskMorning = ({ date, onClose, onConfirmed }: TaskMorningProps) => {
  const notify = useNotify()
  const [proposal, setProposal] = useState<DayProposal | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    fetchDayProposal(date)
      .then((result) => {
        if (current) setProposal(result)
      })
      .catch((error: unknown) => {
        if (!current) return
        console.error(error)
        setFailed(true)
      })

    return () => {
      current = false
    }
  }, [date])

  const confirm = () => {
    if (!proposal || busy) return
    setBusy(true)
    saveDayPlan(
      date,
      proposal.slots.map((slot) => slot.task.id),
    )
      .then(onConfirmed)
      .catch((error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' }))
      .finally(() => setBusy(false))
  }

  if (failed) {
    return (
      <Alert severity="error" action={<Button onClick={onClose}>Fermer</Button>}>
        Proposition indisponible
      </Alert>
    )
  }
  if (!proposal) return <>{[1, 2, 3].map((i) => <Skeleton key={i} height={40} />)}</>

  return (
    <Box component="section" aria-labelledby="morning-title">
      <Typography id="morning-title" variant="h6" component="h2" sx={{ mb: 1 }}>
        Ta journée
      </Typography>

      {proposal.slots.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Rien à proposer aujourd'hui
        </Typography>
      ) : (
        <>
          <TimeBar total={proposal.totalMinutes} free={proposal.freeMinutes} />
          <List component="ol" disablePadding aria-label="Ta journée">
            {proposal.slots.map((slot, index) => (
              <ListItem
                key={slot.task.id}
                sx={{ gap: 1, border: 1, borderColor: 'divider', borderRadius: 1, mb: 0.5 }}
                secondaryAction={
                  proposal.reserveRanked.length > 0 ? (
                    <IconButton
                      edge="end"
                      size="small"
                      aria-label={`Échanger ${slot.task.title}`}
                      onClick={() => setProposal(swapProposal(proposal, index))}
                    >
                      <SwapHorizIcon fontSize="small" />
                    </IconButton>
                  ) : undefined
                }
              >
                <Typography variant="body2" color="text.secondary" sx={{ minWidth: 16 }}>
                  {index + 1}
                </Typography>
                <Typography variant="body2" sx={{ flex: 1, minWidth: 0, pr: 4 }}>
                  {slot.task.title}
                </Typography>
                <Reason entry={slot} />
                <Minutes value={slot.minutes} />
              </ListItem>
            ))}
          </List>
        </>
      )}

      {proposal.reserveRanked.length > 0 && (
        <Box sx={{ mt: 2 }}>
          <Typography variant="overline" color="text.secondary" component="h3">
            Ensuite
          </Typography>
          <List component="ul" disablePadding aria-label="Ensuite">
            {proposal.reserveRanked.slice(0, 3).map((entry) => (
              <ListItem key={entry.task.id} disableGutters dense sx={{ gap: 1 }}>
                <Typography variant="body2" color="text.secondary" sx={{ flex: 1, minWidth: 0 }}>
                  {entry.task.title}
                </Typography>
                <Reason entry={entry} />
              </ListItem>
            ))}
          </List>
        </Box>
      )}

      <Stack direction="row" spacing={1} sx={{ mt: 3 }}>
        <Button variant="contained" onClick={confirm} disabled={busy || proposal.slots.length === 0}>
          C'est parti
        </Button>
        <Button color="inherit" onClick={onClose} disabled={busy}>
          Plus tard
        </Button>
      </Stack>
    </Box>
  )
}

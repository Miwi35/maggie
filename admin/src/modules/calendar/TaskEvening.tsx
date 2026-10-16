import { useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import FormGroup from '@mui/material/FormGroup'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Stack from '@mui/material/Stack'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import { addDays } from '../../dates'
import { DayRing } from './TaskDay'
import { DAY_SLOTS, fetchDayPlan, saveDayPlan } from './dayPlan'
import type { DayPlan, DayTask } from './dayPlan'
import { HELPED, HELPED_LABELS, restoreAt, saveDayReview, setTaskDueDate } from './dayReview'
import type { Helped, Outcome } from './dayReview'

interface TaskEveningProps {
  /** The day reviewed, as it stood when the evening opened. */
  day: DayPlan
  /** « Plus tard »: closes without recording the review. */
  onClose: () => void
  /** « Bonne nuit »: the review is recorded, it does not open again today. */
  onDone: () => void
}

const OUTCOME_LABELS: Record<Outcome, string> = {
  tomorrow: 'Demain',
  later: 'Plus tard',
  aside: 'Mettre de côté',
}

const ids = (plan: DayPlan): string[] => plan.slots.map((slot) => slot.task.id)

/**
 * « Le soir » (MAG-500): what is done first, then each task left with its way
 * out in one gesture — « Demain » writes it in tomorrow's five without
 * settling that day, « Plus tard » leaves it in the reserve, « Mettre de
 * côté » clears its due day and takes it out of today, so it waits in « Un
 * jour » and not « reportée »; « Annuler » puts it back. Each gesture writes
 * at once; « Bonne nuit » records what helped and closes the review.
 */
export const TaskEvening = ({ day, onClose, onDone }: TaskEveningProps) => {
  const notify = useNotify()
  // The review keeps the day as it opened on: a task set aside leaves today, not the review.
  const [opened] = useState(day)
  // Every write goes to the day reviewed and the next one, even once past midnight.
  const today = opened.date
  const tomorrow = addDays(opened.date, 1)
  const [todayIds, setTodayIds] = useState(() => ids(day))
  const [tomorrowIds, setTomorrowIds] = useState<string[] | null>(null)
  const [outcomes, setOutcomes] = useState<Record<string, Outcome>>({})
  const [helped, setHelped] = useState<Helped[]>([])
  const [busy, setBusy] = useState(false)

  const done = opened.slots.filter((slot) => slot.task.completedAt != null).map((slot) => slot.task)
  const left = opened.slots.filter((slot) => slot.task.completedAt == null).map((slot) => slot.task)

  useEffect(() => {
    let current = true
    fetchDayPlan(tomorrow)
      .then((plan) => {
        if (!current) return
        const planned = ids(plan)
        setTomorrowIds(planned)
        // A task already in tomorrow's five comes out that way.
        setOutcomes((before) => {
          const next = { ...before }
          for (const id of planned) next[id] ??= 'tomorrow'

          return next
        })
      })
      .catch((error: unknown) => {
        if (current) console.error(error)
      })

    return () => {
      current = false
    }
  }, [tomorrow])

  const outcomeOf = (task: DayTask): Outcome => outcomes[task.id] ?? 'later'
  const tomorrowFull = (task: DayTask) =>
    tomorrowIds != null && tomorrowIds.length >= DAY_SLOTS && !tomorrowIds.includes(task.id)

  const choose = async (task: DayTask, next: Outcome) => {
    const previous = outcomeOf(task)
    if (busy || next === previous) return
    // Tomorrow is written whole: not before it was read, or its five would be lost.
    if (tomorrowIds == null && (next === 'tomorrow' || previous === 'tomorrow')) return
    if (next === 'tomorrow' && tomorrowFull(task)) {
      notify(`Demain a déjà ${DAY_SLOTS} tâches`, { type: 'warning' })
      return
    }

    setBusy(true)
    let todayNow = todayIds
    let tomorrowNow = tomorrowIds
    // The way out as written so far: a write that fails halfway shows where the task really is.
    let reached: Outcome = previous
    try {
      // Undo the previous way out first: a task is in one place at a time.
      if (previous === 'tomorrow') {
        tomorrowNow = ids(await saveDayPlan(tomorrow, (tomorrowNow ?? []).filter((id) => id !== task.id), false))
        reached = 'later'
      }
      if (previous === 'aside') {
        await setTaskDueDate(task.id, task.dueDate)
        reached = 'later'
        todayNow = ids(await saveDayPlan(today, restoreAt(todayNow, task.id, ids(opened))))
      }
      if (next === 'tomorrow') {
        tomorrowNow = ids(await saveDayPlan(tomorrow, [...(tomorrowNow ?? []).filter((id) => id !== task.id), task.id], false))
        reached = 'tomorrow'
      }
      if (next === 'aside') {
        await setTaskDueDate(task.id, null)
        reached = 'aside'
        todayNow = ids(await saveDayPlan(today, todayNow.filter((id) => id !== task.id)))
      }
      reached = next
    } catch (error) {
      notify(`Erreur: ${(error as Error).message}`, { type: 'error' })
    } finally {
      setOutcomes((before) => ({ ...before, [task.id]: reached }))
      setTodayIds(todayNow)
      if (tomorrowNow !== tomorrowIds) setTomorrowIds(tomorrowNow)
      setBusy(false)
    }
  }

  const toggleHelped = (value: Helped) =>
    setHelped((before) => (before.includes(value) ? before.filter((v) => v !== value) : [...before, value]))

  const goodNight = () => {
    if (busy) return
    setBusy(true)
    saveDayReview(today, helped)
      .then(onDone)
      .catch((error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' }))
      .finally(() => setBusy(false))
  }

  return (
    <Box component="section" aria-label="Le soir">
      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 2 }}>
        <Typography variant="h6" component="h2">
          {done.length} sur {opened.total}
        </Typography>
        <DayRing done={done.length} total={opened.total} />
      </Stack>

      {done.length > 0 && (
        <List disablePadding aria-label="Fait" sx={{ mb: 2 }}>
          {done.map((task) => (
            <ListItem key={task.id} disableGutters dense sx={{ gap: 1 }}>
              <CheckCircleIcon color="primary" fontSize="small" aria-hidden />
              <Typography variant="body2">{task.title}</Typography>
            </ListItem>
          ))}
        </List>
      )}

      {left.length > 0 && (
        <List disablePadding aria-label="Reste">
          {left.map((task) => {
            const outcome = outcomeOf(task)

            return (
              <ListItem
                key={task.id}
                sx={{ gap: 1, flexWrap: 'wrap', border: 1, borderColor: 'divider', borderRadius: 1, mb: 0.5 }}
              >
                <Typography
                  variant="body2"
                  sx={{ flex: 1, minWidth: 160, color: outcome === 'aside' ? 'text.secondary' : 'text.primary' }}
                >
                  {task.title}
                </Typography>
                {outcome === 'aside' ? (
                  <>
                    <Chip size="small" variant="outlined" label="Un jour" />
                    <Button size="small" onClick={() => void choose(task, 'later')} disabled={busy} aria-label={`Annuler : ${task.title}`}>
                      Annuler
                    </Button>
                  </>
                ) : (
                  <ToggleButtonGroup
                    size="small"
                    exclusive
                    value={outcome}
                    onChange={(_, next: Outcome | null) => {
                      if (next) void choose(task, next)
                    }}
                    aria-label={`Issue de ${task.title}`}
                    disabled={busy}
                  >
                    {(Object.keys(OUTCOME_LABELS) as Outcome[]).map((value) => (
                      <ToggleButton
                        key={value}
                        value={value}
                        aria-label={`${task.title} : ${OUTCOME_LABELS[value]}`}
                        disabled={busy || (value === 'tomorrow' && (tomorrowIds == null || tomorrowFull(task)))}
                      >
                        {OUTCOME_LABELS[value]}
                      </ToggleButton>
                    ))}
                  </ToggleButtonGroup>
                )}
              </ListItem>
            )
          })}
        </List>
      )}

      <Typography variant="subtitle2" component="h3" sx={{ mt: 3 }}>
        Qu'est-ce qui t'a aidé ?
      </Typography>
      <FormGroup row aria-label="Qu'est-ce qui t'a aidé ?">
        {HELPED.map((value) => (
          <FormControlLabel
            key={value}
            control={<Checkbox size="small" checked={helped.includes(value)} onChange={() => toggleHelped(value)} />}
            label={HELPED_LABELS[value]}
          />
        ))}
      </FormGroup>

      <Stack direction="row" spacing={1} sx={{ mt: 3 }}>
        <Button variant="contained" onClick={goodNight} disabled={busy}>
          Bonne nuit
        </Button>
        <Button color="inherit" onClick={onClose} disabled={busy}>
          Plus tard
        </Button>
      </Stack>
    </Box>
  )
}

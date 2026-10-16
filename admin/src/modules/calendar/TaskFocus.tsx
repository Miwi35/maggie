import { useEffect, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { useDataProvider, useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import CheckIcon from '@mui/icons-material/Check'
import ContentCutIcon from '@mui/icons-material/ContentCut'
import PauseIcon from '@mui/icons-material/Pause'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import ReplayIcon from '@mui/icons-material/Replay'
import ScheduleIcon from '@mui/icons-material/Schedule'
import TimerOutlinedIcon from '@mui/icons-material/TimerOutlined'
import { bySiblingOrder, taskIri } from './TaskEditDialog'
import type { TaskData } from './TaskEditDialog'
import type { DayTask } from './dayPlan'
import { FOCUS_MS, IDLE, formatClock, pauseTimer, resumeTimer, startTimer, viewTimer } from './focusTimer'
import type { TimerState } from './focusTimer'
import { setTaskDone } from './taskViews'

interface TaskFocusProps {
  task: DayTask
  /** Back to the day, nothing changed. */
  onBack: () => void
  /** The task has been ticked: the day reads again and shows. */
  onDone: () => void
  /** Puts the task after the other slots not done; rejects when the API refuses. */
  onLater: () => Promise<void>
  /** The clock, in milliseconds; injected by the tests. */
  clock?: () => number
}

/** The ten minutes drawn as a ring that empties, the time left in its middle. */
const TimerRing = ({ remainingMs, label }: { remainingMs: number; label: string }) => {
  const theme = useTheme()
  const size = 200
  const stroke = 12
  const radius = (size - stroke) / 2
  const circumference = 2 * Math.PI * radius
  const left = Math.min(1, Math.max(0, remainingMs / FOCUS_MS))

  return (
    <Box
      role="timer"
      aria-label="Minuteur"
      sx={{ position: 'relative', width: size, height: size, display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}
    >
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden style={{ position: 'absolute', inset: 0 }}>
        <circle cx={size / 2} cy={size / 2} r={radius} fill="none" stroke={theme.palette.action.disabledBackground} strokeWidth={stroke} />
        <circle
          data-testid="focus-ring"
          data-left={left.toFixed(3)}
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={theme.palette.primary.main}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - left)}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </svg>
      <Typography variant="h3" component="span" sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {label}
      </Typography>
    </Box>
  )
}

/**
 * The Focus (MAG-499): one task, its first step, ten minutes on a ring and its
 * petits pas — the task's own subtasks, ticked and added through the same API
 * calls as the edit form. Nothing here explains itself, nothing reproaches.
 */
export const TaskFocus = ({ task, onBack, onDone, onLater, clock = Date.now }: TaskFocusProps) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()

  const [timer, setTimer] = useState<TimerState>(IDLE)
  const [now, setNow] = useState(() => clock())
  const [steps, setSteps] = useState<TaskData[]>([])
  const [stuck, setStuck] = useState(false)
  const [newStep, setNewStep] = useState('')
  const [busy, setBusy] = useState(false)

  const view = viewTimer(timer, now)
  const running = view.status === 'running'

  // The time left is read from the clock, never counted: the ticks only redraw it.
  // A tab put in the background throttles them, so it is read again as it comes back.
  useEffect(() => {
    if (!running) return
    const tick = () => setNow(clock())
    const interval = setInterval(tick, 250)
    document.addEventListener('visibilitychange', tick)

    return () => {
      clearInterval(interval)
      document.removeEventListener('visibilitychange', tick)
    }
  }, [running, clock])

  const fail = (error: Error) => notify(`Erreur: ${error.message}`, { type: 'error' })

  useEffect(() => {
    let current = true
    dataProvider
      .getList('tasks', {
        pagination: { page: 1, perPage: 100 },
        sort: { field: 'position', order: 'ASC' },
        filter: { parent: taskIri(task.id) },
      })
      .then(({ data }) => {
        if (current) setSteps([...(data as unknown as TaskData[])].sort(bySiblingOrder))
      })
      .catch((error: Error) => {
        if (current) notify(`Erreur: ${error.message}`, { type: 'error' })
      })

    return () => {
      current = false
    }
  }, [task.id, dataProvider, notify])

  const start = () => {
    const at = clock()
    setNow(at)
    setTimer(startTimer(at))
  }
  const pause = () => setTimer((state) => pauseTimer(state, clock()))
  const resume = () => {
    const at = clock()
    setNow(at)
    setTimer((state) => resumeTimer(state, at))
  }

  // Kept locally after each write: the collection is read from Elasticsearch, which lags the write.
  const toggleStep = (step: TaskData, checked: boolean) => {
    const completedAt = checked ? new Date().toISOString() : null
    const set = (value: string | null) =>
      setSteps((list) => list.map((s) => (s.id === step.id ? { ...s, completedAt: value } : s)))
    set(completedAt)
    dataProvider.update('tasks', { id: step.id, data: { completedAt }, previousData: step }).catch((error: Error) => {
      set(step.completedAt ?? null)
      fail(error)
    })
  }

  const addStep = () => {
    const title = newStep.trim()
    if (!title || busy) return
    setBusy(true)
    dataProvider
      .create('tasks', { data: { title, parent: taskIri(task.id) } })
      .then(({ data }) => {
        setSteps((list) => [...list, data as unknown as TaskData])
        setNewStep('')
      })
      .catch(fail)
      .finally(() => setBusy(false))
  }

  const onStepKey = (event: KeyboardEvent) => {
    if (event.key !== 'Enter') return
    event.preventDefault()
    addStep()
  }

  const finish = () => {
    if (busy) return
    setBusy(true)
    setTaskDone(task.id, true)
      .then(() => {
        notify('Tâche terminée', { type: 'success' })
        onDone()
      })
      .catch((error: Error) => {
        fail(error)
        setBusy(false)
      })
  }

  const later = () => {
    if (busy) return
    setBusy(true)
    onLater().catch((error: Error) => {
      fail(error)
      setBusy(false)
    })
  }

  return (
    <Paper component="section" aria-labelledby="focus-title" variant="outlined" sx={{ p: 3, mb: 3 }}>
      <Button color="inherit" startIcon={<ArrowBackIcon />} onClick={onBack} disabled={busy} sx={{ mb: 1 }}>
        Retour à la journée
      </Button>

      <Typography id="focus-title" variant="h5" component="h2">
        {task.title}
      </Typography>

      {task.firstStep && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }}>
          <Typography
            component="span"
            variant="caption"
            color="text.secondary"
            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flexShrink: 0 }}
          >
            <TimerOutlinedIcon aria-hidden fontSize="inherit" />
            2 min
          </Typography>
          <Typography variant="body1">{task.firstStep}</Typography>
        </Stack>
      )}

      <Stack alignItems="center" spacing={2} sx={{ my: 3 }}>
        <TimerRing remainingMs={view.remainingMs} label={formatClock(view.remainingMs)} />
        {view.status === 'idle' && (
          <Button variant="contained" startIcon={<PlayArrowIcon />} onClick={start}>
            Démarrer
          </Button>
        )}
        {view.status === 'running' && (
          <Button variant="outlined" startIcon={<PauseIcon />} onClick={pause}>
            Pause
          </Button>
        )}
        {view.status === 'paused' && (
          <Button variant="contained" startIcon={<PlayArrowIcon />} onClick={resume}>
            Reprendre
          </Button>
        )}
        {view.status === 'finished' && (
          <Button variant="outlined" startIcon={<ReplayIcon />} onClick={start}>
            Encore 10 minutes
          </Button>
        )}
      </Stack>

      {(steps.length > 0 || stuck) && (
        <Box component="section" aria-label="Petits pas" sx={{ mb: 2 }}>
          <List dense disablePadding>
            {steps.map((step) => (
              <ListItem key={step.id} disableGutters>
                <FormControlLabel
                  control={<Checkbox checked={step.completedAt != null} onChange={(e) => toggleStep(step, e.target.checked)} />}
                  label={step.title}
                  sx={{ textDecoration: step.completedAt != null ? 'line-through' : 'none' }}
                />
              </ListItem>
            ))}
          </List>
          {stuck && (
            <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
              <TextField
                label="Nouveau petit pas"
                size="small"
                value={newStep}
                onChange={(e) => setNewStep(e.target.value)}
                onKeyDown={onStepKey}
                disabled={busy}
                autoFocus
                sx={{ flex: 1 }}
              />
              <Button onClick={addStep} disabled={busy || !newStep.trim()}>
                Ajouter un petit pas
              </Button>
            </Stack>
          )}
        </Box>
      )}

      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
        <Button variant="contained" startIcon={<CheckIcon />} onClick={finish} disabled={busy}>
          C'est fait
        </Button>
        <Button variant="outlined" startIcon={<ContentCutIcon />} onClick={() => setStuck(true)} disabled={stuck}>
          Je bloque : découpe-la
        </Button>
        <Button variant="text" startIcon={<ScheduleIcon />} onClick={later} disabled={busy}>
          Plus tard, sans souci
        </Button>
      </Stack>
    </Paper>
  )
}

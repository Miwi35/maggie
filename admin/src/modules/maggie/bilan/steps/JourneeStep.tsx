import { useCallback, useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CheckIcon from '@mui/icons-material/Check'
import { addDays } from '../../../../dates'
import { clock } from '../../../home/cards/agendaTimeline'
import type { AgendaHomeEvent } from '../../../home/cards/agendaTimeline'
import { localMidnightIso } from '../../../home/cards/tasksCardState'
import { useAgendaCardState } from '../../../home/useAgendaCardState'
import { fetchCompletedBetween, fetchTask, fetchTaskView, setTaskDueDate } from '../../../calendar/taskViews'
import type { ViewTask } from '../../../calendar/taskViews'
import { taskChoices, withTaskChoice } from '../bilanSession'
import type { BilanAnswers, BilanSession, TaskChoice } from '../bilanSession'
import { BilanCard, BilanError } from './BilanCard'
import type { BilanSlot } from './slot'

interface JourneeTask {
  id: string
  title: string
  choice: TaskChoice | null
  saving: boolean
}

interface JourneeState {
  loading: boolean
  error: boolean
  done: ViewTask[]
  remaining: JourneeTask[]
  message: string | null
}

const CHOICES: { value: TaskChoice; label: string }[] = [
  { value: 'tomorrow', label: 'Demain' },
  { value: 'later', label: 'Plus tard' },
]

const isTop = (task: ViewTask): boolean => !task.parent

/** The events of the day that are over, in order: all-day, cancelled and unfinished ones are not part of the day done. */
export const finishedEvents = (events: AgendaHomeEvent[], nowMs: number): AgendaHomeEvent[] =>
  events
    .filter((event) => !event.allDay && event.status !== 'cancelled' && event.startAt)
    .filter((event) => Date.parse(event.endAt ?? event.startAt ?? '') <= nowMs)
    .sort((a, b) => Date.parse(a.startAt ?? '') - Date.parse(b.startAt ?? ''))

const list = (titles: string[]): string => (titles.length < 2 ? titles.join('') : `${titles.slice(0, -1).join(', ')} et ${titles[titles.length - 1]}`)

export const journeeSay = (done: number, total: number, pending: string[], remaining: number): string => {
  if (remaining === 0) return 'Tout est fait aujourd’hui. Rien à ranger.'
  if (pending.length === 0) return 'C’est rangé. Passons à la suite ?'

  return `${done} ${done > 1 ? 'tâches' : 'tâche'} sur ${total} aujourd’hui. Il reste ${list(pending)} : je les mets demain ?`
}

/** The Journée step: the day's finished events, and each task left with « Demain » or « Plus tard ». */
export const useJourneeSlot = (today: string, session: BilanSession | null, record: (answers: (session: BilanSession) => BilanAnswers) => void): BilanSlot => {
  const open = session !== null
  const agenda = useAgendaCardState('today')
  const [state, setState] = useState<JourneeState>({ loading: true, error: false, done: [], remaining: [], message: null })
  const [attempt, setAttempt] = useState(0)
  const choicesRef = useRef<Record<string, TaskChoice>>({})
  useEffect(() => {
    choicesRef.current = session ? taskChoices(session) : {}
  })

  useEffect(() => {
    if (!open) return
    let live = true
    const choices = choicesRef.current

    const read = async () => {
      const tomorrow = addDays(today, 1)
      const [view, done] = await Promise.all([
        fetchTaskView('today'),
        fetchCompletedBetween(localMidnightIso(today), localMidnightIso(tomorrow), 100).catch(() => [] as ViewTask[]),
      ])
      const doneTop = done.filter(isTop)
      const opened = view.member.filter((task) => !task.completedAt && isTop(task))
      // Already moved by an earlier visit: no longer due today, still on the list the owner answered.
      const moved = await Promise.all(
        Object.keys(choices)
          .filter((id) => !opened.some((task) => task.id === id) && !doneTop.some((task) => task.id === id))
          .map((id) => fetchTask(id).catch(() => null)),
      )
      const remaining: JourneeTask[] = [...opened, ...moved.filter((task): task is ViewTask => !!task && !task.completedAt)].map((task) => ({
        id: task.id,
        title: task.title,
        choice: choices[task.id] ?? null,
        saving: false,
      }))
      if (live) setState((current) => ({ ...current, loading: false, error: false, done: doneTop, remaining }))
    }

    setState((current) => ({ ...current, loading: true, error: false }))
    read().catch(() => {
      if (live) setState((current) => ({ ...current, loading: false, error: true }))
    })

    return () => {
      live = false
    }
  }, [open, today, attempt])

  const stateRef = useRef(state)
  useEffect(() => {
    stateRef.current = state
  })

  const update = useCallback((id: string, change: (task: JourneeTask) => JourneeTask) => {
    setState((current) => ({ ...current, remaining: current.remaining.map((task) => (task.id === id ? change(task) : task)) }))
  }, [])

  const choose = useCallback(
    (id: string, choice: TaskChoice) => {
      const task = stateRef.current.remaining.find((candidate) => candidate.id === id)
      if (!task || task.saving || task.choice === choice) return
      update(id, (current) => ({ ...current, choice, saving: true }))
      setState((current) => ({ ...current, message: null }))
      setTaskDueDate(id, choice === 'tomorrow' ? addDays(today, 1) : null)
        .then(() => {
          record((current) => withTaskChoice(current, id, choice))
          update(id, (current) => ({ ...current, saving: false }))
        })
        .catch(() => {
          update(id, (current) => ({ ...current, choice: task.choice, saving: false }))
          setState((current) => ({ ...current, message: `Impossible de ranger « ${task.title} » : réessayez.` }))
        })
    },
    [record, today, update],
  )

  const pending = state.remaining.filter((task) => task.choice === null)
  const done = state.done.length
  const total = done + state.remaining.length
  const events = agenda.state ? finishedEvents(agenda.state.events, Date.parse(agenda.state.now ?? '')) : []

  const content = state.error ? (
    <BilanError message="Impossible de lire votre journée." onRetry={() => setAttempt((count) => count + 1)} />
  ) : state.loading ? null : (
    <>
      <BilanCard label="Agenda · fait" testId="bilan-agenda">
        {events.length === 0 ? (
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>Rien de terminé à l’agenda.</Typography>
        ) : (
          events.map((event) => (
            <Box key={event.id} sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              <Typography component="span" sx={{ minWidth: 48, fontSize: 14, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
                {clock(event.startAt ?? '', event.timeZone)}
              </Typography>
              <Typography component="span" sx={{ flex: 1, minWidth: 0, fontSize: 15 }}>
                {event.summary}
              </Typography>
              <CheckIcon fontSize="small" color="primary" titleAccess="Fait" />
            </Box>
          ))
        )}
      </BilanCard>
      <BilanCard label="Tâches" aside={<span data-testid="bilan-tasks-count">{`${done} ${done > 1 ? 'faites' : 'faite'} sur ${total}`}</span>}>
        {state.remaining.map((task) => {
          const selected = task.choice ?? 'tomorrow'

          return (
            <Box key={task.id} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
              <Typography component="span" sx={{ flex: 1, minWidth: 0, fontSize: 15 }}>
                {task.title}
              </Typography>
              <Box role="radiogroup" aria-label={task.title} sx={{ display: 'flex', gap: 1 }}>
                {CHOICES.map(({ value, label }) => {
                  const checked = selected === value

                  return (
                    <Box
                      key={value}
                      component="button"
                      type="button"
                      role="radio"
                      aria-checked={checked}
                      disabled={task.saving}
                      onClick={() => choose(task.id, value)}
                      sx={(theme) => ({
                        appearance: 'none',
                        border: 0,
                        font: 'inherit',
                        fontSize: 13,
                        cursor: 'pointer',
                        px: 1.75,
                        height: 34,
                        borderRadius: 999,
                        bgcolor: checked ? theme.palette.primary.main : theme.palette.veilleuse.raised,
                        color: checked ? theme.palette.primary.contrastText : theme.palette.text.secondary,
                        opacity: task.saving ? 0.6 : 1,
                      })}
                    >
                      {label}
                    </Box>
                  )
                })}
              </Box>
            </Box>
          )
        })}
        {state.message && (
          <Typography role="alert" data-testid="bilan-tasks-message" sx={{ fontSize: 13, color: 'error.main' }}>
            {state.message}
          </Typography>
        )}
        {done > 0 && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{`Faites · ${state.done.map((task) => task.title).join(', ')}`}</Typography>
        )}
      </BilanCard>
    </>
  )

  const asking = !state.loading && !state.error && pending.length > 0

  return {
    content,
    say: state.loading || state.error ? undefined : journeeSay(done, total, pending.map((task) => task.title), state.remaining.length),
    asks: asking
      ? [
          { label: 'Oui, demain', onClick: () => pending.forEach((task) => choose(task.id, 'tomorrow')) },
          { label: 'Je choisis', onClick: () => undefined },
        ]
      : [],
  }
}

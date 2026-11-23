import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import { parseDay } from '../../../../dates'
import { MOMENT_LABELS } from '../../../calendar/TaskFormDialog'
import { UrgencyBadge } from '../../../calendar/UrgencyBadge'
import { postponedLabel } from '../../../calendar/taskViews'
import type { ViewTask } from '../../../calendar/taskViews'
import type { BilanSession } from '../bilanSession'
import { tomorrowSay } from '../tripQuestions'
import { useBilanTomorrow } from '../useBilanTomorrow'
import type { PlannedRow } from '../useBilanTomorrow'
import { BilanCard, BilanError } from './BilanCard'
import type { BilanSlot } from './slot'

const DAY_LABEL = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'short' })
const DUE = new Intl.DateTimeFormat('fr-FR', { weekday: 'short', day: 'numeric' })

const capitalized = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1)

/** What a task says of itself when Maggie gave no reason: « reportée hier », « limite ven. 30 ». */
export const taskMeta = (task: ViewTask, today: string): string | null =>
  postponedLabel(task.postponedSince, today) ?? (task.dueDate ? `limite ${DUE.format(parseDay(task.dueDate.slice(0, 10)))}` : null)

const momentOf = (task: ViewTask): string | null => (task.scheduledMoment && task.scheduledMoment !== 'time' ? MOMENT_LABELS[task.scheduledMoment] : null)

const pill = (label: string) => (
  <Box key={label} component="span" sx={(theme) => ({ fontSize: 11, fontWeight: 600, px: 1, py: 0.25, borderRadius: 999, bgcolor: theme.palette.veilleuse.raised, color: 'text.secondary', whiteSpace: 'nowrap' })}>
    {label}
  </Box>
)

const TaskLine = ({ row, today, canSwap, onSwap }: { row: PlannedRow; today: string; canSwap: boolean; onSwap: () => void }) => {
  const { task } = row
  const moment = momentOf(task)
  const time = task.scheduledMoment === 'time' ? (task.scheduledTime ?? '') : ''
  const sub = row.reason ?? taskMeta(task, today)

  return (
    <Box data-testid="bilan-planned-task" sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
      <Typography component="span" sx={{ minWidth: 48, fontSize: 14, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
        {time}
      </Typography>
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 0.25 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
          <Typography component="span" sx={{ fontSize: 15 }}>
            {row.title}
          </Typography>
          {moment && pill(moment)}
          <UrgencyBadge urgency={task.urgency} />
        </Box>
        {sub && <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>{sub}</Typography>}
      </Box>
      {canSwap && (
        <Box
          component="button"
          type="button"
          aria-label={`Échanger « ${row.title} » contre une autre tâche`}
          onClick={onSwap}
          sx={(theme) => ({
            appearance: 'none',
            border: 0,
            cursor: 'pointer',
            width: 44,
            height: 44,
            flexShrink: 0,
            borderRadius: '50%',
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            bgcolor: theme.palette.veilleuse.raised,
            color: 'text.secondary',
          })}
        >
          <SwapHorizIcon fontSize="small" />
        </Box>
      )}
    </Box>
  )
}

/** The Demain step. `plan` is what « Bonne nuit » runs first: it plans the shown tasks and says false when it could not. */
export const useDemainSlot = (
  today: string,
  session: BilanSession | null,
  amend: (change: (session: BilanSession) => Record<string, unknown>) => void,
  openChat: () => void,
): BilanSlot & { plan: () => Promise<boolean> } => {
  const tomorrow = useBilanTomorrow(today, session, session?.step === 'demain', amend, openChat)
  const { rows, programme } = tomorrow

  const content = (
    <>
      <BilanCard label={capitalized(DAY_LABEL.format(parseDay(tomorrow.date)))} testId="bilan-programme">
        {tomorrow.programmeError ? (
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>Le programme n’a pas pu être chargé.</Typography>
        ) : programme.length === 0 ? (
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>Rien de prévu demain.</Typography>
        ) : (
          programme.map((row) => (
            <Box key={row.key} sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5 }}>
              <Typography component="span" sx={{ minWidth: 48, fontSize: 14, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
                {row.time}
              </Typography>
              <Typography component="span" sx={{ flex: 1, minWidth: 0, fontSize: 15 }}>
                {row.title}
              </Typography>
              {row.detail && (
                <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
                  {row.detail}
                </Typography>
              )}
            </Box>
          ))
        )}
      </BilanCard>
      {tomorrow.failed ? (
        <BilanError message="Impossible de lire vos tâches de demain." onRetry={tomorrow.retry} />
      ) : (
        rows.length > 0 && (
          <BilanCard label="Tâches prévues" aside={`${rows.length} ${rows.length > 1 ? 'tâches' : 'tâche'}`} testId="bilan-planned">
            {rows.map((row) => (
              <TaskLine key={row.taskId} row={row} today={today} canSwap={tomorrow.canSwap} onSwap={() => void tomorrow.swap(row.taskId, row.title)} />
            ))}
          </BilanCard>
        )
      )}
      {tomorrow.message && (
        <Typography role="alert" data-testid="bilan-demain-message" sx={{ fontSize: 13, color: 'error.main' }}>
          {tomorrow.message}
        </Typography>
      )}
    </>
  )

  return {
    content,
    say: tomorrowSay(tomorrow.events, tomorrow.question),
    asks: tomorrow.question?.choices.map((choice) => ({ label: choice.label, onClick: () => void tomorrow.answer(choice) })) ?? [],
    plan: tomorrow.plan,
  }
}

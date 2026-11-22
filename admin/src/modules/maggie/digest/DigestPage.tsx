import { useEffect } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { useNotify } from 'react-admin'
import { useSearchParams } from 'react-router-dom'
import { VoiceBubble } from '../../../components/chat/VoiceBubble'
import { MaggieAvatar } from '../../../components/maggie/MaggieAvatar'
import { localDay } from '../../../dates'
import { TOKENS, urgencyColor } from '../../../design/tokens'
import { HEALTH_SUIVI_PATH } from '../../../components/layout/navigation'
import { clock, rowTime } from '../../home/cards/agendaTimeline'
import type { AgendaHomeEvent } from '../../home/cards/agendaTimeline'
import type { HealthFast } from '../../home/cards/healthCardState'
import { isHealthEmpty } from '../../home/cards/healthCardState'
import { HomeCard } from '../../home/HomeCard'
import { useAgendaCardState } from '../../home/useAgendaCardState'
import { useHealthCardState } from '../../home/useHealthCardState'
import { useTasksCardState } from '../../home/useTasksCardState'
import { postponedLabel } from '../../calendar/taskViews'
import { URGENCIES } from '../../calendar/UrgencyBadge'
import { useCuisineCardState } from '../../cookbook/useCuisineCardState'
import { digestTitle, digestWord } from './digestApi'
import { useDigest } from './useDigest'
import { WeatherCard } from './WeatherCard'

export const DIGEST_EMPTY = 'Pas de digest ce matin.'
export const DIGEST_FAILED = "Le digest n'a pas pu être chargé."

const TRIP_MODES: Record<string, string> = { walk: 'à pied', drive: 'en voiture', transit: 'en transports', bicycle: 'à vélo' }
const MOMENTS: Record<string, string> = { morning: 'ce matin', afternoon: 'cet après-midi', evening: 'ce soir' }
const SLOTS: Record<string, string> = { lunch: 'midi', dinner: 'soir' }

const Line = ({ time, children, aside, sub }: { time?: string; children: React.ReactNode; aside?: React.ReactNode; sub?: string }) => (
  <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5, py: 0.5 }}>
    {time !== undefined && (
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 44, fontVariantNumeric: 'tabular-nums' }}>
        {time}
      </Typography>
    )}
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="body2" sx={{ fontWeight: 500 }}>
        {children}
      </Typography>
      {sub && (
        <Typography variant="caption" color="text.secondary" component="div">
          {sub}
        </Typography>
      )}
    </Box>
    {aside}
  </Box>
)

const Pill = ({ children, color }: { children: React.ReactNode; color?: string }) => (
  <Box
    component="span"
    sx={(theme) => ({
      px: 1,
      borderRadius: `${TOKENS.radius.pill}px`,
      fontSize: '0.75rem',
      fontWeight: 600,
      whiteSpace: 'nowrap',
      color: color ?? theme.palette.text.secondary,
      border: '1px solid',
      borderColor: color ?? theme.palette.divider,
    })}
  >
    {children}
  </Box>
)

const AgendaDigestCard = () => {
  const { state, loading, error } = useAgendaCardState('today')
  if (loading || error || !state) return null
  const trip = state.trips.find((t) => t.leg !== 'return')
  const events = state.events.filter((event: AgendaHomeEvent) => event.status !== 'cancelled')
  if (events.length === 0) return null
  const tripEvent = trip ? events.find((event) => trip.event.endsWith(`/${event.id}`)) : undefined

  return (
    <HomeCard module="agenda" title="Agenda" to="/calendar">
      {trip && (
        <Line time={clock(trip.departAt)}>
          {`Départ pour ${(tripEvent?.summary ?? '').toLowerCase()} · ${trip.durationMinutes} min ${TRIP_MODES[trip.mode] ?? ''}`.trim()}
        </Line>
      )}
      {events.map((event) => (
        <Line key={`${event.id}${event.startAt ?? ''}`} time={rowTime(event, state.date) || (event.allDay ? 'Journée' : '')}>
          {event.summary}
        </Line>
      ))}
    </HomeCard>
  )
}

const TasksDigestCard = () => {
  const { state, loading, error } = useTasksCardState('today')
  if (loading || error || !state) return null
  const tasks = state.tasks.filter((task) => !task.done)
  const routines = state.routines.filter((routine) => !routine.checked)
  if (tasks.length === 0 && routines.length === 0) return null
  const today = localDay(new Date())

  return (
    <HomeCard module="tasks" title="Tâches" to="/tasks">
      {tasks.map((task) => {
        const urgent = task.urgency === 'high' || task.urgency === 'critical' ? URGENCIES[task.urgency] : null
        const moment = task.scheduledMoment && task.scheduledMoment !== 'time' ? MOMENTS[task.scheduledMoment] : null

        return (
          <Line
            key={task.id}
            time={task.scheduledMoment === 'time' ? (task.scheduledTime ?? '') : ''}
            sub={postponedLabel(task.postponedSince, today) ?? undefined}
            aside={
              <Box sx={{ display: 'flex', gap: 0.5 }}>
                {urgent && <Pill color={urgencyColor(task.urgency as string)}>{urgent.label.charAt(0).toUpperCase() + urgent.label.slice(1)}</Pill>}
                {moment && <Pill>{moment}</Pill>}
              </Box>
            }
          >
            {task.title}
          </Line>
        )
      })}
      {routines.map((routine) => (
        <Line key={routine.id} time={routine.time}>
          {routine.title}
        </Line>
      ))}
    </HomeCard>
  )
}

const CuisineDigestCard = () => {
  const { state, loading, error } = useCuisineCardState('today')
  if (loading || error || !state) return null
  const meals = state.slots.flatMap(({ slot, meals: planned }) => planned.map((meal) => ({ slot, meal })))
  if (meals.length === 0) return null

  return (
    <HomeCard module="cuisine" title="Cuisine" to="/meals">
      {meals.map(({ slot, meal }) => (
        <Line key={meal.id} aside={<Typography variant="body2" color="text.secondary">{SLOTS[slot]}</Typography>}>
          {meal.title}
        </Line>
      ))}
    </HomeCard>
  )
}

const hoursOf = (hhmm: string): number => Number(hhmm.slice(0, 2)) + Number(hhmm.slice(3, 5)) / 60

/** « 16:8 »: the hours of fast, then of the window to eat. */
const protocolOf = (fast: HealthFast): string => {
  const eating = (hoursOf(fast.windowEnd) - hoursOf(fast.windowStart) + 24) % 24

  return `${Math.round(24 - eating)}:${Math.round(eating)}`
}

const HealthDigestCard = () => {
  const { state, loading, error } = useHealthCardState('today')
  if (loading || error || !state || isHealthEmpty(state)) return null
  const { workout, fast } = state

  return (
    <HomeCard module="health" title="Santé" to={HEALTH_SUIVI_PATH}>
      {workout && (
        <Line aside={<Typography variant="body2" color="text.secondary">{`${workout.durationMinutes} min`}</Typography>}>
          {`${workout.title} · ${workout.time}`}
        </Line>
      )}
      {fast && (
        <Line aside={<Typography variant="body2" color="text.secondary">{protocolOf(fast)}</Typography>}>
          {fast.state === 'fasting' ? `Jeûne → ${fast.windowStart}` : `Repas → ${fast.windowEnd}`}
        </Line>
      )}
    </HomeCard>
  )
}

/** The Digest du matin screen (MAG-959): Maggie's voice and word at the head, then one card per module with something to say. */
export const DigestPage = () => {
  const [params] = useSearchParams()
  const notify = useNotify()
  const { message, block, weather, loading, error, firstOpening } = useDigest(params.get('message'))

  useEffect(() => {
    if (error) notify(DIGEST_FAILED, { type: 'error' })
  }, [error, notify])

  if (loading) return <Box aria-busy="true" data-testid="digest-loading" />
  if (error) return null
  if (!message || !block) {
    return (
      <Typography color="text.secondary" sx={{ p: 2 }}>
        {DIGEST_EMPTY}
      </Typography>
    )
  }

  const hasWeather = weather !== null && (weather.maxC != null || weather.minC != null || weather.condition != null)

  return (
    <Box component="main" data-testid="digest-page" sx={{ display: 'flex', flexDirection: 'column', gap: 3, p: 2 }}>
      <Typography variant="h4" component="h1" sx={{ fontWeight: TOKENS.typography.weight.semibold }}>
        {digestTitle(block.day)}
      </Typography>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'minmax(0,520px) 1fr' }, gap: 4, alignItems: 'start' }}>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <MaggieAvatar size={64} />
            <Typography component="p" sx={{ fontSize: 40, fontWeight: 300 }}>
              Bonjour monsieur
            </Typography>
          </Box>
          {message.audio && <VoiceBubble audio={message.audio} authHeaders={authHeaders} size="large" autoPlay={firstOpening} transcriptToggle={false} />}
        </Box>
        <Typography data-testid="digest-word" sx={{ fontSize: 20, lineHeight: 1.5, whiteSpace: 'pre-line' }}>
          {digestWord(block)}
        </Typography>
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0,1fr))' }, gap: 2, alignItems: 'start' }}>
        {hasWeather && weather && <WeatherCard weather={weather} />}
        <AgendaDigestCard />
        <TasksDigestCard />
        <CuisineDigestCard />
        <HealthDigestCard />
      </Box>
    </Box>
  )
}

const authHeaders = (): Record<string, string> => {
  const token = localStorage.getItem('token')

  return token ? { Authorization: `Bearer ${token}` } : {}
}

export default DigestPage

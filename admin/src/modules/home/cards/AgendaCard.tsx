import { useEffect, useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import Link from '@mui/material/Link'
import { alpha } from '@mui/material/styles'
import { Link as RouterLink } from 'react-router-dom'
import { ActivityLaunchButton, launchTargetFor } from '../ActivityLaunchButton'
import { HomeCard } from '../HomeCard'
import { moduleColor } from '../moduleColor'
import { HomeCardError } from '../HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../homeCardStatus'
import { useAgendaCardState } from '../useAgendaCardState'
import type { HomeDay } from '../homeDay'
import { AgendaEventPanel } from './AgendaEventPanel'
import { DepartureTile, HomeTripLine } from './AgendaTrip'
import { buildTimeline, clock, countLabel, dayShort, rowSub, rowTime, untilLabel } from './agendaTimeline'
import type { AgendaHomeEvent, TimelineRow } from './agendaTimeline'

/** How often the line and « dans 48 min » are recomputed. */
const TICK_MS = 20_000

/** Past rows stay readable: dimmed, not struck out. */
const PAST_OPACITY = 0.72

/** The clock of the card: the instant of the read, then the browser's clock moving on from it. */
function useNowMs(readAt: string | null | undefined): number | null {
  const [clockState, setClockState] = useState<{ readAt: string; localMs: number; tickMs: number } | null>(null)

  useEffect(() => {
    if (!readAt) return
    const localMs = Date.now()
    const timer = setInterval(() => setClockState({ readAt, localMs, tickMs: Date.now() }), TICK_MS)

    return () => clearInterval(timer)
  }, [readAt])

  if (!readAt) return null
  // Until the first tick, or after a fresh read, the instant of the read: no frame without a line.
  if (clockState?.readAt !== readAt) return Date.parse(readAt)

  return Date.parse(readAt) + (clockState.tickMs - clockState.localMs)
}

const NowLine = ({ nowMs }: { nowMs: number }) => {
  const label = `maintenant · ${clock(nowMs)}`

  return (
    <Box
      role="separator"
      aria-label={label}
      data-testid="agenda-now-line"
      sx={(theme) => ({ display: 'flex', alignItems: 'center', gap: '10px', px: 1, py: 0.5, color: moduleColor(theme, 'agenda') })}
    >
      <Typography component="span" sx={{ fontSize: 12, fontWeight: 500, whiteSpace: 'nowrap' }}>
        {label}
      </Typography>
      <Box
        aria-hidden
        sx={{ flex: 1, height: '2px', borderRadius: '2px', background: (theme) => `linear-gradient(90deg, ${moduleColor(theme, 'agenda')}, ${alpha(moduleColor(theme, 'agenda'), 0)})` }}
      />
    </Box>
  )
}

interface RowProps {
  row: TimelineRow
  day: HomeDay
  date: string
  nowMs: number | null
  onOpen: (event: AgendaHomeEvent) => void
}

const Row = ({ row, day, date, nowMs, onOpen }: RowProps) => {
  const { event, kind } = row
  const past = kind === 'past'
  const highlighted = kind === 'next'
  const time = rowTime(event, date)
  const sub = rowSub(event, past)
  const hint =
    kind === 'next' && nowMs !== null && event.startAt
      ? untilLabel(Date.parse(event.startAt), nowMs)
      : kind === 'current'
        ? 'en cours'
        : null

  // The ▶ is a link, which cannot sit inside the row's button: it sits beside it, on the same line.
  const launch = past ? null : launchTargetFor(event.activity)

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
      <ButtonBase
        onClick={() => onOpen(event)}
        data-testid="agenda-row"
        data-kind={kind}
        sx={(theme) => ({
          display: 'grid',
          gridTemplateColumns: '48px 4px minmax(0, 1fr) auto',
          alignItems: 'center',
          justifyItems: 'start',
          gap: '12px',
          flex: 1,
          minWidth: 0,
          minHeight: 52,
          textAlign: 'left',
          padding: '6px 8px',
          borderRadius: '14px',
          // Past events are transparent and dimmed; the next one sits on the Agenda's colour.
          bgcolor: highlighted ? alpha(moduleColor(theme, 'agenda'), 0.1) : 'transparent',
          opacity: past ? PAST_OPACITY : 1,
          '&:hover': { bgcolor: theme.palette.action.hover },
          '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 },
        })}
      >
        <Typography
          component="span"
          sx={{ fontSize: 15, fontWeight: highlighted ? 500 : 400, fontVariantNumeric: 'tabular-nums', color: past ? 'text.secondary' : 'text.primary' }}
        >
          {time || (event.allDay ? 'Journée' : '')}
        </Typography>
        <Box aria-hidden sx={{ width: 4, height: 32, borderRadius: '4px', bgcolor: event.agendaColor ?? 'primary.main' }} />
        <Box sx={{ minWidth: 0, maxWidth: '100%' }}>
          <Typography noWrap sx={{ fontSize: 15, fontWeight: highlighted ? 500 : 400 }}>
            {event.summary}
          </Typography>
          {sub && (
            <Typography noWrap component="div" sx={{ fontSize: 12, color: 'text.secondary' }}>
              {sub}
            </Typography>
          )}
        </Box>
        {hint ? (
          <Typography
            component="span"
            data-testid="agenda-row-tag"
            sx={(theme) => ({ fontSize: 12, fontWeight: 500, whiteSpace: 'nowrap', color: moduleColor(theme, 'agenda') })}
          >
            {hint}
          </Typography>
        ) : (
          <span />
        )}
      </ButtonBase>
      {launch && <ActivityLaunchButton module={launch.module} activity={event.summary} day={day} to={launch.path} />}
    </Box>
  )
}

/** The Agenda card of the Accueil (MAG-562, on the events and agendas resources since MAG-589): the day as a list of hours, the « maintenant » line between what is done and what follows. */
export const AgendaCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useAgendaCardState(day)
  const nowMs = useNowMs(state?.now)
  const [openId, setOpenId] = useState<string | null>(null)

  const timeline = useMemo(() => buildTimeline(state?.events ?? [], nowMs, state?.trips ?? []), [state, nowMs])
  // The fiche follows the card: a refresh updates it, a deleted event closes it.
  const opened = state?.events.find((event) => event.id === openId) ?? null

  const status = homeCardStatus({ state, loading, error }, (s) => s.events.length === 0)
  useReportHomeCard('agenda', status)

  const rows = (list: TimelineRow[]) =>
    list.map((row) => (
      <Box component="li" key={`${row.event.id}${row.event.startAt ?? ''}`} sx={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
        {row.trips.map((trip) =>
          trip.id === timeline.nextTripId && nowMs !== null ? (
            <DepartureTile key={trip.id} trip={trip} nowMs={nowMs} />
          ) : (
            <HomeTripLine key={trip.id} trip={trip} title={row.event.summary} nowMs={nowMs} />
          ),
        )}
        <Row row={row} day={day} date={state?.date ?? ''} nowMs={nowMs} onOpen={(e) => setOpenId(e.id)} />
      </Box>
    ))

  if (status === 'empty') return null

  return (
    <HomeCard
      module="agenda"
      title={state ? `Agenda · ${dayShort(state.date)}` : 'Agenda'}
      to="/calendar"
      trailing={state && state.events.length > 0 ? countLabel(timeline) : undefined}
      background={(theme) => `linear-gradient(160deg, ${alpha(moduleColor(theme, 'agenda'), 0.12)} 0%, ${theme.palette.background.paper} 55%)`}
    >
      {error && <HomeCardError onRetry={() => void retry()} />}
      {loading && (!state || state.events.length === 0) && !error && (
        <Box aria-busy="true" aria-label="Chargement de l'agenda">
          <Skeleton height={28} />
          <Skeleton height={28} />
          <Skeleton height={28} />
        </Box>
      )}
      {state && state.events.length > 0 && (
        <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }} aria-label="Événements du jour">
          {rows(timeline.allDay)}
          {rows(timeline.past)}
          {timeline.hasNowLine && nowMs !== null && (
            <li>
              <NowLine nowMs={nowMs} />
            </li>
          )}
          {rows(timeline.following)}
        </Box>
      )}
      {state && state.events.length > 0 && (
        <Link component={RouterLink} to="/calendar" variant="body2" underline="hover" sx={{ display: 'inline-block', mt: 1 }}>
          Voir la journée ›
        </Link>
      )}
      <AgendaEventPanel event={opened} onClose={() => setOpenId(null)} />
    </HomeCard>
  )
}

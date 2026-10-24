import { useEffect, useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import CheckIcon from '@mui/icons-material/Check'
import { HomeCard } from '../HomeCard'
import { HomeCardError } from '../HomeCardError'
import { homeCardStatus, useReportHomeCard } from '../homeCardStatus'
import { useHomeCardState } from '../useHomeCardState'
import type { HomeDay } from '../homeDay'
import { AgendaEventPanel } from './AgendaEventPanel'
import { buildTimeline, clock, rowTime, untilLabel } from './agendaTimeline'
import type { AgendaHomeEvent, AgendaHomeState, TimelineRow } from './agendaTimeline'

/** How often the line and « dans 48 min » are recomputed. */
const TICK_MS = 20_000

/** Past rows stay readable: dimmed, not struck out. */
const PAST_OPACITY = 0.6

/**
 * The clock of the card: the server's `now`, then the browser's clock moving on from it —
 * a screen with a wrong clock still draws the line where the owner's day is.
 */
function useNowMs(serverNow: string | null | undefined): number | null {
  const [clockState, setClockState] = useState<{ serverNow: string; localMs: number; tickMs: number } | null>(null)

  useEffect(() => {
    if (!serverNow) return
    const localMs = Date.now()
    const timer = setInterval(() => setClockState({ serverNow, localMs, tickMs: Date.now() }), TICK_MS)

    return () => clearInterval(timer)
  }, [serverNow])

  if (!serverNow) return null
  // Until the first tick, or after a fresh read, the server's own instant: no frame without a line.
  if (clockState?.serverNow !== serverNow) return Date.parse(serverNow)

  return Date.parse(serverNow) + (clockState.tickMs - clockState.localMs)
}

const NowLine = ({ nowMs }: { nowMs: number }) => {
  const label = `maintenant · ${clock(nowMs)}`

  return (
    <Box
      role="separator"
      aria-label={label}
      data-testid="agenda-now-line"
      sx={(theme) => ({ display: 'flex', alignItems: 'center', gap: 1, my: 0.5, px: 1, color: theme.palette.error.main })}
    >
      <Typography variant="caption" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
        {label}
      </Typography>
      <Box aria-hidden sx={{ flex: 1, height: '2px', borderRadius: 1, bgcolor: 'currentColor' }} />
    </Box>
  )
}

interface RowProps {
  row: TimelineRow
  date: string
  nowMs: number | null
  onOpen: (event: AgendaHomeEvent) => void
}

const Row = ({ row, date, nowMs, onOpen }: RowProps) => {
  const { event, kind } = row
  const past = kind === 'past'
  const highlighted = kind === 'next'
  const place = event.locationShort
  const time = rowTime(event, date)
  const hint =
    kind === 'next' && nowMs !== null && event.startAt
      ? untilLabel(Date.parse(event.startAt), nowMs)
      : kind === 'current'
        ? 'en cours'
        : null

  return (
    <ButtonBase
      onClick={() => onOpen(event)}
      data-testid="agenda-row"
      data-kind={kind}
      sx={(theme) => ({
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'flex-start',
        gap: 1.5,
        width: '100%',
        textAlign: 'left',
        px: 1,
        py: 0.75,
        borderRadius: `${theme.shape.borderRadius}px`,
        // Past events are transparent and dimmed; the next one sits on its agenda's colour.
        bgcolor: highlighted ? `${event.agendaColor ?? theme.palette.primary.main}22` : 'transparent',
        opacity: past ? PAST_OPACITY : 1,
        '&:hover': { bgcolor: theme.palette.action.hover },
        '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 },
      })}
    >
      <Typography variant="body2" sx={{ minWidth: 52, fontVariantNumeric: 'tabular-nums', color: 'text.secondary', pt: '1px' }}>
        {time || (event.allDay ? 'Journée' : '')}
      </Typography>
      <Box
        aria-hidden
        sx={{ width: 4, alignSelf: 'stretch', minHeight: 20, borderRadius: 2, bgcolor: event.agendaColor ?? 'primary.main', flexShrink: 0 }}
      />
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: highlighted ? 600 : 500 }}>
          {event.summary}
          {past && (
            <Box component="span" sx={{ color: 'text.secondary', fontWeight: 400 }}>
              {' · fait'}
              <CheckIcon aria-hidden sx={{ fontSize: 14, verticalAlign: 'text-bottom', ml: 0.5 }} />
            </Box>
          )}
        </Typography>
        {(place || hint) && (
          <Typography variant="caption" color="text.secondary" component="div" noWrap>
            {[place, hint].filter(Boolean).join(' · ')}
          </Typography>
        )}
      </Box>
    </ButtonBase>
  )
}

/** The Agenda card of the Accueil (MAG-562): the day as a list of hours, the « maintenant » line between what is done and what follows. */
export const AgendaCard = ({ day }: { day: HomeDay }) => {
  const { state, loading, error, retry } = useHomeCardState<AgendaHomeState>('agenda', day)
  const nowMs = useNowMs(state?.now)
  const [openId, setOpenId] = useState<string | null>(null)

  const timeline = useMemo(() => buildTimeline(state?.events ?? [], nowMs), [state, nowMs])
  // The fiche follows the card: a refresh updates it, a deleted event closes it.
  const opened = state?.events.find((event) => event.id === openId) ?? null

  const status = homeCardStatus({ state, loading, error }, (s) => s.events.length === 0)
  useReportHomeCard('agenda', status)

  const rows = (list: TimelineRow[]) =>
    list.map((row) => (
      <li key={`${row.event.id}${row.event.startAt ?? ''}`}>
        <Row row={row} date={state?.date ?? ''} nowMs={nowMs} onOpen={(e) => setOpenId(e.id)} />
      </li>
    ))

  if (status === 'empty') return null

  return (
    <HomeCard module="agenda" title="Agenda" to="/calendar">
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
      <AgendaEventPanel event={opened} onClose={() => setOpenId(null)} />
    </HomeCard>
  )
}

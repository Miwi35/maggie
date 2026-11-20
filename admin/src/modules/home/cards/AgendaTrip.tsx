import Box from '@mui/material/Box'
import { alpha, useTheme } from '@mui/material/styles'
import NavigationOutlined from '@mui/icons-material/NavigationOutlined'
import PlaceOutlined from '@mui/icons-material/PlaceOutlined'
import { AGENDA_ACCENT } from '../../calendar/calendarTheme'
import { timeLeft } from '../../calendar/dayAgenda'
import { STEP_ICONS } from '../../calendar/TripLine'
import { openNavigation, type Trip, type TripStep } from '../../calendar/trips'
import { clock, tripLine } from './agendaTimeline'

const StepChip = ({ step }: { step: TripStep }) => {
  const Icon = STEP_ICONS[step.kind as keyof typeof STEP_ICONS] ?? PlaceOutlined

  return (
    <Box
      sx={(theme) => ({
        display: 'inline-flex',
        alignItems: 'center',
        gap: '5px',
        height: 26,
        px: '8px',
        borderRadius: '999px',
        bgcolor: alpha(theme.palette.text.primary, 0.06),
        color: 'text.primary',
        fontSize: 13,
      })}
    >
      <Icon aria-hidden sx={{ fontSize: 14 }} />
      {step.minutes} min
      {step.label && (
        <Box component="span" sx={(theme) => ({ color: theme.palette.text.disabled })}>
          {' '}
          {step.label}
        </Box>
      )}
    </Box>
  )
}

/** The next departure of the day, above its event: « Partir à 17:04 », time left, door to door, steps, « Y aller ». */
export const DepartureTile = ({ trip, nowMs }: { trip: Trip; nowMs: number }) => {
  const theme = useTheme()
  const minutes = Math.ceil((Date.parse(trip.departAt) - nowMs) / 60_000)

  return (
    <Box
      component="section"
      aria-label="Prochain départ"
      data-testid="home-departure"
      sx={{
        m: '4px 0 2px',
        p: '14px',
        borderRadius: '18px',
        bgcolor: 'rgba(15,14,23,0.55)',
        boxShadow: `inset 0 0 0 1px ${alpha(AGENDA_ACCENT, 0.28)}`,
        display: 'flex',
        flexDirection: 'column',
        gap: '10px',
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 1 }}>
        <Box sx={{ fontSize: 24, fontWeight: 500, fontVariantNumeric: 'tabular-nums' }}>Partir à {clock(trip.departAt)}</Box>
        <Box sx={{ fontSize: 14, color: AGENDA_ACCENT }}>{timeLeft(minutes)}</Box>
      </Box>
      <Box sx={{ fontSize: 13, color: theme.palette.text.secondary }}>
        {trip.originLabel ? `depuis ${trip.originLabel} · ` : ''}
        {trip.durationMinutes} min porte à porte
      </Box>
      {trip.steps.length > 0 && (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
          {trip.steps.map((step, index) => (
            <StepChip key={index} step={step} />
          ))}
        </Box>
      )}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        {trip.navigationUrl && (
          <Box
            component="button"
            type="button"
            onClick={() => openNavigation(trip.navigationUrl)}
            sx={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '8px',
              height: 44,
              px: '18px',
              border: 0,
              borderRadius: '999px',
              cursor: 'pointer',
              fontFamily: 'inherit',
              fontSize: 15,
              fontWeight: 500,
              bgcolor: '#F3F1F8',
              color: '#0F0E17',
            }}
          >
            <NavigationOutlined aria-hidden sx={{ fontSize: 16 }} />
            Y aller
          </Box>
        )}
        {trip.computedAt && <Box sx={{ fontSize: 12, color: theme.palette.text.disabled }}>à jour {clock(trip.computedAt)}</Box>}
      </Box>
    </Box>
  )
}

/** Any other trip of the day: one line above its event, « Partir à 18:05 · depuis X », dimmed once left. */
export const HomeTripLine = ({ trip, title, nowMs }: { trip: Trip; title: string; nowMs: number | null }) => {
  const theme = useTheme()
  const { verb, from, past } = tripLine(trip, nowMs)

  return (
    <Box
      role="group"
      aria-label={`Trajet vers ${title}`}
      data-testid="home-trip-line"
      sx={{
        display: 'flex',
        alignItems: 'baseline',
        gap: '4px',
        minHeight: 22,
        px: '8px',
        fontSize: 13,
        opacity: past ? 0.6 : 1,
      }}
    >
      <Box component="span" sx={{ fontWeight: past ? 400 : 500, color: past ? theme.palette.text.secondary : 'text.primary', whiteSpace: 'nowrap' }}>
        {verb}{' '}
      </Box>
      {from && (
        <Box component="span" sx={{ color: theme.palette.text.secondary, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {from}
        </Box>
      )}
    </Box>
  )
}

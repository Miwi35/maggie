import Box from '@mui/material/Box'
import { alpha, useTheme } from '@mui/material/styles'
import DirectionsBikeOutlined from '@mui/icons-material/DirectionsBikeOutlined'
import DirectionsBusOutlined from '@mui/icons-material/DirectionsBusOutlined'
import DirectionsCarOutlined from '@mui/icons-material/DirectionsCarOutlined'
import DirectionsWalkOutlined from '@mui/icons-material/DirectionsWalkOutlined'
import PlaceOutlined from '@mui/icons-material/PlaceOutlined'
import { timeLeft } from './dayAgenda'
import { departureText, hourOf, isReturn, openNavigation, type Trip, type TripStep } from './trips'

export const STEP_ICONS = {
  walk: DirectionsWalkOutlined,
  drive: DirectionsCarOutlined,
  transit: DirectionsBusOutlined,
  bicycle: DirectionsBikeOutlined,
} as const

const StepChip = ({ step, labelled }: { step: TripStep; labelled: boolean }) => {
  const theme = useTheme()
  const Icon = STEP_ICONS[step.kind as keyof typeof STEP_ICONS] ?? PlaceOutlined

  return (
    <Box sx={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'center', gap: '2px' }}>
      <Box
        className="trip-chip"
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: '5px',
          height: 26,
          px: '9px',
          borderRadius: '999px',
          bgcolor: alpha(theme.palette.text.primary, 0.06),
          color: 'text.primary',
          fontSize: 13,
        }}
      >
        <Icon sx={{ fontSize: 14 }} />
        {step.minutes} min
      </Box>
      {labelled && step.label && (
        <Box sx={{ fontSize: 12, color: theme.palette.text.disabled }}>{step.label}</Box>
      )}
    </Box>
  )
}

const Steps = ({ trip, labelled = false }: { trip: Trip; labelled?: boolean }) => (
  <>
    {trip.steps.map((step, index) => (
      <StepChip key={index} step={step} labelled={labelled} />
    ))}
  </>
)

interface TripLineProps {
  trip: Trip
  /** The event's title: the line is named « Trajet vers … ». */
  title: string
  /** The first trip still to leave: drawn as a card. */
  next?: boolean
  past?: boolean
  now?: Date
}

/** A trip of the day list: a line at its hour, or the card of the next departure (MAG-858). */
export const TripLine = ({ trip, title, next = false, past = false, now = new Date() }: TripLineProps) => {
  const theme = useTheme()
  const label = `Trajet vers ${title}`

  if (next) {
    const minutes = Math.ceil((new Date(trip.departAt).getTime() - now.getTime()) / 60_000)

    return (
      <Box
        className="day-trip day-trip--next"
        aria-label={label}
        role="group"
        sx={{
          m: '6px 0 6px 82px',
          p: '12px 16px',
          borderRadius: '16px',
          bgcolor: 'background.paper',
          boxShadow: `inset 0 0 0 1px ${alpha(theme.palette.primary.light, 0.28)}`,
          display: 'flex',
          alignItems: 'center',
          gap: 2,
        }}
      >
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Box sx={{ display: 'flex', alignItems: 'baseline', gap: '10px', flexWrap: 'wrap' }}>
            <Box sx={{ fontSize: 18, fontWeight: 500 }}>Partir à {hourOf(trip.departAt)}</Box>
            <Box sx={{ color: theme.palette.primary.light, fontSize: 14 }}>{timeLeft(minutes)}</Box>
          </Box>
          <Box sx={{ color: 'text.secondary', fontSize: 13 }}>
            depuis {trip.originLabel} · {trip.durationMinutes} min porte à porte
          </Box>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: '10px', mt: '8px' }}>
            <Steps trip={trip} labelled />
          </Box>
        </Box>
        <Box
          component="button"
          type="button"
          onClick={() => openNavigation(trip.navigationUrl)}
          sx={{
            height: 40,
            px: '20px',
            border: 0,
            borderRadius: '999px',
            cursor: 'pointer',
            fontFamily: 'inherit',
            fontSize: 14,
            fontWeight: 500,
            bgcolor: theme.palette.text.primary,
            color: theme.palette.background.default,
          }}
        >
          Y aller
        </Box>
      </Box>
    )
  }

  return (
    <Box
      className="day-trip"
      aria-label={label}
      role="group"
      sx={{
        ml: '96px',
        minHeight: 40,
        display: 'flex',
        flexWrap: 'wrap',
        alignItems: 'center',
        gap: '10px',
        fontSize: 14,
        opacity: past ? 0.6 : 1,
      }}
    >
      {isReturn(trip) ? (
        <Box sx={{ fontWeight: 500 }}>{departureText(trip)}</Box>
      ) : (
        <>
          <Box sx={{ fontWeight: 500 }}>
            {past ? 'Parti' : 'Partir'} à {hourOf(trip.departAt)}
          </Box>
          <Box sx={{ color: 'text.secondary' }}>depuis {trip.originLabel}</Box>
        </>
      )}
      <Steps trip={trip} />
    </Box>
  )
}

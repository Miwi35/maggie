import { useEffect, useState } from 'react'
import { alpha } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { SelectField, TimeField } from './ConfigurationFields'
import { FASTING_PROTOCOLS } from './healthProfileApi'
import type { FastingProtocol, HealthProfile } from './healthProfileApi'
import type { HealthDay } from './healthDaysApi'
import type { Loaded } from './nutritionApi'
import { cardSx, parisClock } from './nutritionBalance'
import { formatDay } from './suiviSeries'
import {
  fastBars,
  fastHours,
  fastingStatus,
  fastScale,
  nightFastLabel,
  NO_FASTING,
  NO_FASTING_LABEL,
  windowLabel,
} from './fasting'
import type { FastingDay } from './fasting'

type ProtocolChoice = FastingProtocol | typeof NO_FASTING

const PROTOCOL_OPTIONS: readonly (readonly [ProtocolChoice, string])[] = [
  ...FASTING_PROTOCOLS.map((protocol) => [protocol, protocol] as const),
  [NO_FASTING, NO_FASTING_LABEL],
]

const CHART_HEIGHT = 120

/** The Paris time, read again every minute: the countdown of the header. */
function useParisNow(): string {
  const [now, setNow] = useState(() => parisClock())

  useEffect(() => {
    const timer = setInterval(() => setNow(parisClock()), 60_000)

    return () => clearInterval(timer)
  }, [])

  return now
}

const dateOf = (key: string): Date => {
  const [year, month, day] = key.split('-').map(Number)

  return new Date(year, month - 1, day)
}

interface FastingChartProps {
  days: Loaded<HealthDay[]>
  day: string
  protocol: FastingProtocol
}

/** « Jeûnes · 14 jours »: the night fasts, a dashed line at the protocol's hours, the day shown in coral. */
export const FastingChart = ({ days, day, protocol }: FastingChartProps) => {
  const bars = fastBars(days.status === 'ready' ? days.data : [], day)
  const scale = fastScale(bars, protocol)
  const share = (hours: number) => `${((hours / scale) * 100).toFixed(1)}%`

  return (
    <Box component="section" aria-label="Jeûnes, 14 jours" sx={{ display: 'flex', flexDirection: 'column', gap: 1, pt: 1 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
        <Typography component="span" sx={{ fontSize: 14, color: 'text.secondary' }}>
          Jeûnes · 14 jours
        </Typography>
        <Typography component="span" data-testid="fasting-chart-protocol" sx={{ fontSize: 12, color: 'text.secondary' }}>
          {protocol}
        </Typography>
      </Box>
      {days.status === 'error' && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Erreur : les jeûnes n’ont pas pu être chargés.</Typography>
      )}
      {days.status === 'ready' && (
        <>
          <Box sx={{ position: 'relative', height: CHART_HEIGHT }}>
            <Box
              data-testid="fasting-goal"
              aria-hidden
              sx={(theme) => ({
                position: 'absolute',
                left: 0,
                right: 0,
                bottom: share(fastHours(protocol)),
                borderTop: `1px dashed ${alpha(theme.palette.text.primary, 0.25)}`,
              })}
            />
            <Box sx={{ position: 'absolute', inset: 0, display: 'flex', gap: 0.75, alignItems: 'flex-end' }}>
              {bars.map((bar) => (
                <Box
                  key={bar.date}
                  data-testid="fasting-bar"
                  data-current={bar.date === day ? 'true' : undefined}
                  role="img"
                  aria-label={`${formatDay(dateOf(bar.date))} : ${bar.minutes === null ? 'pas de jeûne mesuré' : nightFastLabel(bar.minutes)}`}
                  sx={(theme) => ({
                    flex: '1 1 0',
                    minWidth: 0,
                    height: bar.minutes === null ? 0 : share(bar.minutes / 60),
                    borderRadius: '4px 4px 1px 1px',
                    bgcolor: bar.date === day ? theme.palette.primary.main : alpha(theme.palette.primary.main, 0.55),
                  })}
                />
              ))}
            </Box>
          </Box>
          <Box sx={{ display: 'flex', gap: 0.75 }} aria-hidden>
            {bars.map((bar) => (
              <Typography key={bar.date} component="span" sx={{ flex: '1 1 0', minWidth: 0, textAlign: 'center', fontSize: 11, color: 'text.secondary' }}>
                {Number(bar.date.slice(8))}
              </Typography>
            ))}
          </Box>
        </>
      )}
    </Box>
  )
}

interface FastingCardProps {
  profile: HealthProfile | null
  fasting: Loaded<FastingDay>
  days: Loaded<HealthDay[]>
  /** The day the page shows; the countdown only runs for today. */
  day: string
  today: string
  /** Saves the profile: false when the API refused it (the profile is then back to its previous value). */
  onSave: (patch: Pick<Partial<HealthProfile>, 'fastingProtocol' | 'fastingWindowStart'>) => Promise<boolean>
}

const rowSx = { py: 1, borderBottom: 1, borderColor: 'divider', fontSize: 13 } as const

/**
 * « Jeûne » (MAG-770): the protocol and the window's start, written to the
 * profile as soon as they change; the day's window and the night's fast, as
 * the API computes them; then « Jeûnes · 14 jours ». « Aucun » stops fasting:
 * only the protocol is left, to take it up again.
 */
export const FastingCard = ({ profile, fasting, days, day, today, onSave }: FastingCardProps) => {
  const now = useParisNow()
  if (profile === null) return null

  // A profile from before MAG-770 carries no protocol: no fasting.
  const protocol = profile.fastingProtocol ?? null
  const loaded = fasting.status === 'ready' && protocol !== null ? fasting.data : null
  const status = loaded ? fastingStatus(loaded, day === today ? now : null) : ''

  return (
    <Box component="section" aria-label="Jeûne" data-testid="fasting-card" sx={cardSx}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 1 }}>
        <Box sx={{ fontSize: 15, fontWeight: 500 }}>Jeûne</Box>
        {status !== '' && (
          <Typography component="span" data-testid="fasting-status" sx={{ fontSize: 12, color: 'text.secondary' }}>
            {status}
          </Typography>
        )}
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1.25 }}>
        <SelectField<ProtocolChoice>
          label="Protocole"
          value={protocol ?? NO_FASTING}
          options={PROTOCOL_OPTIONS}
          testId="fasting-protocol"
          onChange={(choice) => void onSave({ fastingProtocol: choice === NO_FASTING ? null : choice })}
        />
        {protocol !== null && (
          <TimeField
            label="Début de la fenêtre"
            testId="fasting-window-start"
            value={profile.fastingWindowStart}
            onSave={(time) => onSave({ fastingWindowStart: time })}
          />
        )}
      </Box>
      {protocol !== null && fasting.status === 'error' && (
        <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>Erreur : le jeûne du jour n’a pas pu être chargé.</Typography>
      )}
      {loaded && (
        <Box component="table" sx={{ borderCollapse: 'collapse', width: '100%', fontVariantNumeric: 'tabular-nums' }}>
          <tbody>
            <tr>
              <Box component="td" sx={{ ...rowSx, color: 'text.secondary' }}>
                Fenêtre du jour
              </Box>
              <Box component="td" data-testid="fasting-window" sx={{ ...rowSx, textAlign: 'right' }}>
                {windowLabel(loaded)}
              </Box>
            </tr>
            <tr>
              <Box component="td" sx={{ ...rowSx, color: 'text.secondary' }}>
                Jeûne de la nuit
              </Box>
              <Box component="td" data-testid="fasting-night" sx={{ ...rowSx, textAlign: 'right' }}>
                {nightFastLabel(loaded.nightFastMinutes)}
              </Box>
            </tr>
          </tbody>
        </Box>
      )}
      {protocol !== null && <FastingChart days={days} day={day} protocol={protocol} />}
    </Box>
  )
}

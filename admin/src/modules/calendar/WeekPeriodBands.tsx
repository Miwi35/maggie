import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import { weekSpan, untilLabel } from './periodsApi'
import type { Period } from './periodsApi'

export interface BandAgenda {
  id: string
  color?: string | null
}

interface WeekPeriodBandsProps {
  periods: Period[]
  /** The week's first day, `YYYY-MM-DD`. */
  weekStart: string
  agendas: BandAgenda[]
  onOpen: (period: Period) => void
  /** Width in px of the grid's hour column; the bands row leaves the same room. */
  axisWidth?: number
}

/**
 * The periods of the week, one band each above the grid, over the columns of its
 * days (MAG-816). A period holds no slot: it is never an event of the grid.
 */
export const WeekPeriodBands = ({ periods, weekStart, agendas, onOpen, axisWidth = 52 }: WeekPeriodBandsProps) => {
  const bands = periods.flatMap((period, index) => {
    const span = weekSpan(period, weekStart)

    return span ? [{ period, index, ...span }] : []
  })
  if (bands.length === 0) return null

  return (
    <Box
      data-testid="week-period-bands"
      sx={{ display: 'grid', gridTemplateColumns: `${axisWidth}px repeat(7, minmax(0, 1fr))`, rowGap: 0.5, columnGap: 0, px: 0, py: 0.5, borderBottom: 1, borderColor: 'divider' }}
    >
      {bands.map(({ period, index, column, span }) => {
        const color = agendas.find((a) => String(a.id) === period.agenda)?.color ?? undefined

        return (
          <Box
            key={`${period.id}-${index}`}
            component="button"
            type="button"
            data-testid="period-band"
            data-period={period.name}
            data-column={column}
            data-span={span}
            onClick={() => onOpen(period)}
            sx={{
              gridColumnStart: column + 1,
              gridColumnEnd: `span ${span}`,
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              minWidth: 0,
              px: 1,
              height: 28,
              mx: 0.25,
              border: 0,
              borderRadius: '8px',
              cursor: 'pointer',
              font: 'inherit',
              fontSize: 13,
              textAlign: 'left',
              color: 'text.primary',
              ...(color
                ? {
                    backgroundImage: `repeating-linear-gradient(135deg, ${alpha(color, 0.12)} 0 6px, transparent 6px 12px)`,
                    boxShadow: `inset 0 0 0 1px ${alpha(color, 0.45)}`,
                  }
                : { backgroundColor: 'action.hover' }),
            }}
          >
            <Box aria-hidden sx={{ width: 10, height: 10, borderRadius: 0.5, flexShrink: 0, backgroundColor: color ?? 'divider' }} />
            <Box component="span" sx={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {period.name}
            </Box>
            <Box component="span" sx={{ color: 'text.secondary', whiteSpace: 'nowrap', ml: 'auto' }}>
              {untilLabel(period)}
            </Box>
          </Box>
        )
      })}
    </Box>
  )
}

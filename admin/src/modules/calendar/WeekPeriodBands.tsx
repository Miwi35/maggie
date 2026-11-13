import Box from '@mui/material/Box'
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
}

/**
 * The periods of the week, one band each above the grid, over the columns of its
 * days (MAG-816). A period holds no slot: it is never an event of the grid.
 */
export const WeekPeriodBands = ({ periods, weekStart, agendas, onOpen }: WeekPeriodBandsProps) => {
  const bands = periods.flatMap((period, index) => {
    const span = weekSpan(period, weekStart)

    return span ? [{ period, index, ...span }] : []
  })
  if (bands.length === 0) return null

  return (
    <Box
      data-testid="week-period-bands"
      sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', rowGap: 0.5, columnGap: 0, px: 1, py: 0.5, borderBottom: 1, borderColor: 'divider' }}
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
              gridColumn: `${column} / span ${span}`,
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              minWidth: 0,
              px: 1,
              py: 0.25,
              mx: 0.25,
              border: 0,
              borderRadius: 1,
              cursor: 'pointer',
              font: 'inherit',
              fontSize: '0.8rem',
              textAlign: 'left',
              color: 'text.primary',
              backgroundColor: 'action.hover',
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

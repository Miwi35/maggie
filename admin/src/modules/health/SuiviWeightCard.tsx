import { alpha } from '@mui/material/styles'
import type { Theme } from '@mui/material/styles'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { chartPaths, columnLabel, formatOne, formatPercent, NONE, trendDelta } from './suiviSeries'
import type { Column } from './suiviSeries'

interface SuiviWeightCardProps {
  cols: Column[]
  /** The dots of the weighings: 7 j and 30 j only. */
  withDots: boolean
  /** The selected column, the last one (today) by default. */
  selected: number
  onSelect: (index: number) => void
}

const caption = { fontSize: 12, color: (theme: Theme) => theme.palette.veilleuse.caption } as const

/**
 * « Poids » (mockup `Admin-Suivi`): the selected column's weight, trend and
 * body fat, the trend line in coral over the dots of the weighings, and the
 * axis with how the trend moved over the period.
 */
export const SuiviWeightCard = ({ cols, withDots, selected, onSelect }: SuiviWeightCardProps) => {
  const column = cols[selected] ?? cols.at(-1)
  const { line, dots } = chartPaths(cols, withDots)

  return (
    <Box
      component="section"
      aria-label="Poids"
      sx={{ borderRadius: 4, bgcolor: 'background.paper', px: 2.25, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0, minHeight: 0, fontVariantNumeric: 'tabular-nums' }}
    >
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', fontSize: 15, fontWeight: 500 }}>
        <span>Poids</span>
        <Typography component="span" data-testid="weight-label" sx={{ ...caption, fontWeight: 400 }}>
          {column?.label}
        </Typography>
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5 }}>
        <Typography component="span" data-testid="weight-value" sx={{ fontSize: 26, fontWeight: 300 }}>
          {column?.weightKg == null ? (
            NONE
          ) : (
            <>
              {formatOne(column.weightKg)}{' '}
              <Box component="span" sx={{ fontSize: 15 }}>
                kg
              </Box>
            </>
          )}
        </Typography>
        <Typography component="span" data-testid="weight-trend" sx={{ fontSize: 13, color: 'text.secondary' }}>
          <Box component="span" sx={(theme) => ({ color: theme.palette.module.sport })}>
            —
          </Box>{' '}
          tendance {column?.trendKg == null ? NONE : `${formatOne(column.trendKg)} kg`}
        </Typography>
        <Typography component="span" data-testid="weight-fat" sx={{ fontSize: 13, color: 'text.secondary' }}>
          · masse grasse {formatPercent(column?.bodyFatPercent ?? null)}
        </Typography>
      </Box>
      <Box sx={{ position: 'relative', flexGrow: 1, minHeight: 180 }}>
        <Box sx={{ position: 'absolute', inset: 0, display: 'flex', gap: '2px' }}>
          {cols.map((col, index) => (
            <Box
              key={col.start.getTime()}
              component="button"
              type="button"
              aria-label={columnLabel(col)}
              aria-pressed={index === selected}
              data-testid="weight-column"
              onClick={() => onSelect(index)}
              onMouseEnter={() => onSelect(index)}
              sx={(theme) => ({
                appearance: 'none',
                border: 0,
                m: 0,
                p: 0,
                font: 'inherit',
                cursor: 'pointer',
                flex: '1 1 0',
                minWidth: 0,
                height: '100%',
                borderRadius: 1,
                bgcolor: index === selected ? alpha(theme.palette.text.primary, 0.07) : 'transparent',
              })}
            />
          ))}
        </Box>
        <Box
          component="svg"
          width="100%"
          height="100%"
          viewBox="0 0 1000 100"
          preserveAspectRatio="none"
          fill="none"
          aria-hidden="true"
          sx={{ position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'visible' }}
        >
          <Box
            component="path"
            data-testid="weight-dots"
            d={dots}
            strokeWidth={6}
            strokeLinecap="round"
            vectorEffect="non-scaling-stroke"
            sx={(theme) => ({ stroke: theme.palette.veilleuse.caption })}
          />
          <Box
            component="path"
            data-testid="weight-trend-line"
            d={line}
            strokeWidth={3}
            strokeLinejoin="round"
            strokeLinecap="round"
            vectorEffect="non-scaling-stroke"
            sx={(theme) => ({ stroke: theme.palette.module.sport })}
          />
        </Box>
      </Box>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', ...caption }}>
        <span>{cols[0]?.short}</span>
        <span data-testid="weight-delta">{trendDelta(cols)} sur la période</span>
        <span>{cols.at(-1)?.short}</span>
      </Box>
    </Box>
  )
}

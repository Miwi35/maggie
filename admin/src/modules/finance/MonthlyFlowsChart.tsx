import { useState } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import type { MonthlyFlow } from './useFinanceDashboard'

/**
 * Categorical slots 1 and 2 of the validated palette — light and dark steps of
 * the same two hues, now `chart.categorical` in `design/tokens.json` (MAG-39).
 * Money in and money out are two identities, not a scale and not a status, so
 * they take categorical colours in fixed order.
 */
const SERIES = {
  income: { slot: 1, label: 'Recettes' },
  expense: { slot: 2, label: 'Dépenses' },
}

const CHART_HEIGHT = 180
const BAR_GAP = 2

/** "Sept." from "2026-09" — short enough to sit under a bar. */
const shortMonth = (month: string): string => {
  const [, monthNumber] = month.split('-')
  const names = [
    'Jan.', 'Fév.', 'Mars', 'Avr.', 'Mai', 'Juin',
    'Juil.', 'Août', 'Sept.', 'Oct.', 'Nov.', 'Déc.',
  ]

  return names[Number(monthNumber) - 1] ?? month
}

interface MonthlyFlowsChartProps {
  flows: MonthlyFlow[]
}

/** Twelve rolling months of money in and money out, side by side. */
export const MonthlyFlowsChart = ({ flows }: MonthlyFlowsChartProps) => {
  const theme = useTheme()
  const [hovered, setHovered] = useState<number | null>(null)
  const colour = (series: keyof typeof SERIES) =>
    chartColor(SERIES[series].slot, theme.palette.mode === 'dark' ? 'dark' : 'light')

  const peak = Math.max(
    1,
    ...flows.map((flow) => Math.max(flow.incomeCents, flow.expenseCents)),
  )

  const barHeight = (cents: number) => Math.round((cents / peak) * CHART_HEIGHT)
  const active = hovered === null ? null : flows[hovered]

  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ mb: 1 }}>
        {(Object.keys(SERIES) as (keyof typeof SERIES)[]).map((series) => (
          <Stack key={series} direction="row" spacing={0.5} alignItems="center">
            <Box
              sx={{
                width: 10,
                height: 10,
                borderRadius: '2px',
                backgroundColor: colour(series),
              }}
            />
            <Typography variant="caption" color="text.secondary">
              {SERIES[series].label}
            </Typography>
          </Stack>
        ))}
      </Stack>

      <Stack
        direction="row"
        alignItems="flex-end"
        spacing={1}
        sx={{ height: CHART_HEIGHT + 28, overflowX: 'auto' }}
      >
        {flows.map((flow, index) => (
          <Stack
            key={flow.month}
            alignItems="center"
            spacing={0.5}
            onMouseEnter={() => setHovered(index)}
            onMouseLeave={() => setHovered(null)}
            sx={{ flex: '1 0 44px', cursor: 'default' }}
          >
            <Stack
              direction="row"
              alignItems="flex-end"
              spacing={`${BAR_GAP}px`}
              sx={{ height: CHART_HEIGHT }}
            >
              <Box
                sx={{
                  width: 12,
                  height: barHeight(flow.incomeCents),
                  backgroundColor: colour('income'),
                  borderRadius: '4px 4px 0 0',
                  opacity: hovered === null || hovered === index ? 1 : 0.45,
                }}
              />
              <Box
                sx={{
                  width: 12,
                  height: barHeight(flow.expenseCents),
                  backgroundColor: colour('expense'),
                  borderRadius: '4px 4px 0 0',
                  opacity: hovered === null || hovered === index ? 1 : 0.45,
                }}
              />
            </Stack>
            <Typography variant="caption" color="text.secondary">
              {shortMonth(flow.month)}
            </Typography>
          </Stack>
        ))}
      </Stack>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
        {active
          ? `${shortMonth(active.month)} : ${formatCents(active.incomeCents)} de recettes, ${formatCents(active.expenseCents)} de dépenses — solde ${formatCents(active.netCents)}`
          : 'Survolez un mois pour le détail.'}
      </Typography>
    </Box>
  )
}

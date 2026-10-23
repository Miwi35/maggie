import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { Axis, ganttMonthLabel, LABEL_WIDTH } from './ProjectGantt'
import type { PlanningMonth, ProjectPlanning } from './projectsApi'

export type FundingSource = 'salary' | 'savings' | 'capital'

const PLOT_HEIGHT = 140
const MARK_BAND = 14
const MARK_SIZE = 8

/** Bottom to top: the salary carries the stack, the money already there sits on it. */
const SOURCES: { key: FundingSource; label: string; slot: number; field: 'salaryCents' | 'savingsCents' | 'capitalCents' }[] = [
  { key: 'salary', label: 'Salaire', slot: 1, field: 'salaryCents' },
  { key: 'savings', label: 'Économies', slot: 2, field: 'savingsCents' },
  { key: 'capital', label: 'Capital libéré', slot: 3, field: 'capitalCents' },
]

/** The tooltip and the accessible name of a month: the amounts by source, the capacity, the excess when there is one. */
export const fundingMonthDetail = (month: PlanningMonth, currency: string): string => {
  const parts = [
    ganttMonthLabel(month.month),
    ...SOURCES.map((source) => `${source.label} ${formatCents(month[source.field], currency)}`),
    `Capacité ${formatCents(month.capacityCents, currency)}`,
  ]
  // MAG-557: the rent already counts in the capacity; the resale is cash that landed this month.
  if (month.rentCents > 0) parts.push(`Loyers ${formatCents(month.rentCents, currency)}`)
  if (month.resaleCents > 0) parts.push(`Revente ${formatCents(month.resaleCents, currency)}`)
  if (month.isOverCapacity) parts.push(`Dépassement ${formatCents(month.salaryCents - month.capacityCents, currency)}`)

  return parts.join(' · ')
}

/**
 * How the projects are funded month by month (MAG-528), under the Gantt and on its month columns:
 * one stacked bar per month — salary, savings, released capital — and the salary's capacity as a
 * dashed staircase. A month where the salary goes past its capacity carries a neutral mark above
 * its bar and the excess in its tooltip: a fact to read, not a verdict, so no alert colour.
 */
export function ProjectFundingChart({ planning }: { planning: ProjectPlanning }) {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const months = planning.months
  if (months.length === 0) return null

  const colors = Object.fromEntries(SOURCES.map((source) => [source.key, chartColor(source.slot, mode)])) as Record<FundingSource, string>
  const scale = Math.max(1, ...months.map((month) => Math.max(month.capacityCents, month.salaryCents + month.savingsCents + month.capitalCents)))
  const height = (cents: number) => (cents / scale) * PLOT_HEIGHT
  const stair = months
    .map((month, index) => {
      const y = PLOT_HEIGHT - height(month.capacityCents)

      return `${index === 0 ? 'M' : 'L'}${index},${y} L${index + 1},${y}`
    })
    .join(' ')

  return (
    <Box data-testid="funding-chart" role="group" aria-label={`Financement des projets mois par mois sur ${months.length} mois`} sx={{ mt: 2 }}>
      <Stack
        direction="row"
        data-testid="funding-legend"
        sx={{ gap: 2, alignItems: 'center', position: 'sticky', left: 0, width: 'max-content', maxWidth: '100%', pb: 1 }}
      >
        {SOURCES.map((source) => (
          <Stack key={source.key} direction="row" sx={{ gap: 0.5, alignItems: 'center' }}>
            <Box sx={{ width: 12, height: 12, borderRadius: '2px', bgcolor: colors[source.key] }} />
            <Typography variant="caption" color="text.secondary">
              {source.label}
            </Typography>
          </Stack>
        ))}
        <Stack direction="row" sx={{ gap: 0.5, alignItems: 'center' }}>
          <Box sx={{ width: 14, height: 0, borderTop: `2px dashed ${theme.palette.text.primary}` }} />
          <Typography variant="caption" color="text.secondary">
            Capacité sur salaire
          </Typography>
        </Stack>
      </Stack>

      <Box sx={{ display: 'grid', gridTemplateColumns: `${LABEL_WIDTH.full}px 1fr` }}>
        <Box
          sx={{
            position: 'sticky',
            left: 0,
            zIndex: 2,
            bgcolor: 'background.paper',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'space-between',
            height: PLOT_HEIGHT + MARK_BAND,
            pr: 1,
          }}
        >
          <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1 }}>
            {formatCents(scale, planning.currency)}
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ lineHeight: 1 }}>
            {formatCents(0, planning.currency)}
          </Typography>
        </Box>

        <Box sx={{ position: 'relative', height: PLOT_HEIGHT + MARK_BAND, borderBottom: 1, borderColor: 'divider' }}>
          <Box sx={{ display: 'grid', gridTemplateColumns: `repeat(${months.length}, minmax(0, 1fr))`, height: '100%' }}>
            {months.map((month, index) => {
              const detail = fundingMonthDetail(month, planning.currency)

              return (
                <Tooltip key={month.month} title={detail} enterTouchDelay={0} leaveTouchDelay={4000} placement="top" arrow>
                  <Box
                    tabIndex={0}
                    data-testid="funding-bar"
                    data-month={month.month}
                    data-over={month.isOverCapacity ? 'true' : undefined}
                    sx={{
                      display: 'flex',
                      flexDirection: 'column-reverse',
                      alignItems: 'center',
                      px: '1px',
                      bgcolor: index === 0 ? 'action.hover' : undefined,
                      '&:focus-visible': { outline: `2px solid ${theme.palette.text.primary}`, outlineOffset: -2 },
                    }}
                  >
                    {SOURCES.map((source) =>
                      month[source.field] > 0 ? (
                        <Box
                          key={source.key}
                          data-testid="funding-segment"
                          data-source={source.key}
                          data-month={month.month}
                          data-cents={month[source.field]}
                          sx={{ width: '100%', height: height(month[source.field]), flexShrink: 0, bgcolor: colors[source.key] }}
                        />
                      ) : null,
                    )}
                    {month.isOverCapacity && (
                      <Box
                        data-testid="funding-over"
                        data-month={month.month}
                        sx={{
                          width: MARK_SIZE,
                          height: MARK_SIZE,
                          flexShrink: 0,
                          mb: '3px',
                          transform: 'rotate(45deg)',
                          bgcolor: theme.palette.text.primary,
                        }}
                      />
                    )}
                  </Box>
                </Tooltip>
              )
            })}
          </Box>
          <svg
            viewBox={`0 0 ${months.length} ${PLOT_HEIGHT}`}
            preserveAspectRatio="none"
            aria-hidden="true"
            style={{ position: 'absolute', left: 0, bottom: 0, width: '100%', height: PLOT_HEIGHT, pointerEvents: 'none' }}
          >
            <path
              data-testid="funding-capacity"
              d={stair}
              fill="none"
              stroke={theme.palette.text.primary}
              strokeWidth="2"
              strokeDasharray="5 3"
              vectorEffect="non-scaling-stroke"
            />
          </svg>
        </Box>
      </Box>

      <Axis planning={planning} variant="full" testPrefix="funding" />
    </Box>
  )
}
